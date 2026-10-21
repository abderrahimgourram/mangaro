#!/usr/bin/env python3
"""Bounded, fail-closed PostgreSQL -> gzip -> age -> rclone streams.

No secrets in argv/logs, no full local dump, no production restore destination.
Remote ciphertext verification is deliberately separate from database restoration.
"""
import argparse
import contextlib
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import threading
import uuid


PROJECT = 'pehsxthjetlltlsqcbfa'
BUCKET = 'mangaro-secure-backups'
CHUNK = 64 * 1024


class BackupError(RuntimeError):
    pass


def private_file(path):
    path = Path(path)
    if path.is_symlink() or not path.is_file():
        raise BackupError('Expected a private regular file')
    info = path.stat()
    if info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise BackupError('Private file must be owned by this user with no group/other access')
    return path


def outside_git(path):
    resolved = Path(path).resolve()
    if any((p / '.git').exists() for p in [resolved, *resolved.parents]):
        raise BackupError('Sensitive artifacts must remain outside every Git worktree')
    return resolved


def write_private(path, data):
    path = outside_git(path)
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as output:
        json.dump(data, output, indent=2)
        output.write('\n')
        output.flush()
        os.fsync(output.fileno())


def safe_env():
    # Don't inherit an unrelated account, proxy, rclone remote, or PG destination.
    return {k: v for k, v in os.environ.items() if not k.startswith(
        ('PG', 'RCLONE_', 'AWS_', 'CLOUDFLARE_', 'HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY'))}


def pipeline(stages, env, consumer=None, input_stream=None, timeout=1800):
    """Drain stderr without retaining private SQL/errors; kill ALL stages on failure/timeout."""
    processes, drains = [], []
    expired = threading.Event()

    def terminate():
        for process in processes:
            if process.poll() is None:
                with contextlib.suppress(ProcessLookupError):
                    os.killpg(process.pid, signal.SIGKILL)

    def deadline():
        expired.set()
        terminate()

    timer = threading.Timer(timeout, deadline)
    timer.daemon = True
    timer.start()
    try:
        previous = input_stream
        for command in stages:
            if expired.is_set():
                raise BackupError('Pipeline timed out')
            process = subprocess.Popen(command, env=env, stdin=previous,
                                       stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                       start_new_session=True)
            if previous is not None and previous is not input_stream:
                previous.close()
            processes.append(process)
            # Never persist errors: PostgreSQL can include sensitive row values in errors.
            drain = threading.Thread(target=lambda stream: _discard(stream), args=(process.stderr,), daemon=True)
            drain.start()
            drains.append(drain)
            previous = process.stdout
        result = consumer(previous) if consumer else _discard(previous)
        previous.close()
        codes = [process.wait() for process in processes]
        if expired.is_set():
            raise BackupError('Pipeline timed out; no successful manifest is permitted')
        if any(codes):
            raise BackupError('Pipeline stages failed: ' + repr(codes))
        return result
    except BaseException:
        terminate()
        for process in processes:
            process.wait()
        raise
    finally:
        timer.cancel()
        for drain in drains:
            drain.join(timeout=2)
        for process in processes:
            for stream in (process.stdin, process.stdout, process.stderr):
                if stream is not None:
                    stream.close()


def _discard(stream):
    while stream.read(CHUNK):
        pass


def digest_stream(stream, limit, destination=None):
    digest, count = hashlib.sha256(), 0
    while block := stream.read(CHUNK):
        count += len(block)
        if count > limit:
            raise BackupError('Stream exceeds approved byte budget')
        digest.update(block)
        if destination is not None:
            destination.write(block)
    return {'sha256': digest.hexdigest(), 'bytes': count}


def load_config(path, production=False):
    data = json.loads(private_file(path).read_text())
    outside_git(path)
    if data.get('schema') != 1 or data.get('project_ref') != PROJECT or data.get('bucket') != BUCKET:
        raise BackupError('Wrong backup project/bucket/schema')
    account = data.get('account_id', '')
    if not re.fullmatch('[a-f0-9]{32}', account):
        raise BackupError('Invalid account identifier')
    if data.get('endpoint') != f'https://{account}.r2.cloudflarestorage.com':
        raise BackupError('Unexpected R2 endpoint; do not guess regions/jurisdictions')
    if data.get('credential_name') != 'mangaro-backup-writer-v2':
        raise BackupError('Only the reviewed replacement credential is supported')
    if not re.fullmatch('age1[0-9a-z]{58}', data.get('recipient', '')):
        raise BackupError('Expected an age X25519 public recipient')
    review = data.get('security_review', {})
    if not all(review.get(k) is True for k in
               ('old_token_revoked', 'bucket_private', 'single_bucket_object_only')):
        raise BackupError('Credential revocation, bucket privacy and scope review are mandatory')
    if production and not (data.get('recovery_copy_verified') is True and
                           data.get('production_coverage_reviewed') is True):
        raise BackupError('Production coverage and independent recovery-key verification are mandatory')
    if not 1 <= data.get('max_ciphertext_bytes', 0) <= 10 * 1024 ** 3:
        raise BackupError('Missing or excessive approved ciphertext budget')
    if not 1 <= data.get('timeout_seconds', 0) <= 3600:
        raise BackupError('Invalid timeout budget')
    return data


def credential_env(config):
    env = safe_env()
    for field, destination in [('access', 'ACCESS_KEY_ID'), ('secret', 'SECRET_ACCESS_KEY')]:
        result = subprocess.run(
            ['secret-tool', 'lookup', 'service', 'Mangaro Backups', 'credential',
             config['credential_name'], 'account', config['account_id'], 'field', field],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=15,
        )
        if result.returncode or not result.stdout.strip():
            raise BackupError('New bucket-scoped credential not available in local credential store')
        env['RCLONE_CONFIG_MANGAROBACKUP_' + destination] = result.stdout.decode().strip()
    env.update({'RCLONE_CONFIG_MANGAROBACKUP_TYPE': 's3',
                'RCLONE_CONFIG_MANGAROBACKUP_PROVIDER': 'Cloudflare',
                'RCLONE_CONFIG_MANGAROBACKUP_ENDPOINT': config['endpoint'],
                'RCLONE_CONFIG_MANGAROBACKUP_REGION': 'auto',
                'RCLONE_CONFIG_MANGAROBACKUP_NO_CHECK_BUCKET': 'true',
                'RCLONE_CONFIG_MANGAROBACKUP_FORCE_PATH_STYLE': 'true'})
    return env


class Store:
    def __init__(self, executable, base, env, timeout=1800, limit=1024 ** 3):
        self.binary, self.base, self.env = str(executable), base.rstrip('/'), env
        self.timeout, self.limit = timeout, limit

    def command(self, operation, key):
        if not re.fullmatch(r'[a-zA-Z0-9/_\-.]+', key) or '..' in key or key.startswith('/'):
            raise BackupError('Unsafe object key')
        return [self.binary, operation, self.base + '/' + key,
                '--config', '/dev/null', '--retries', '1', '--low-level-retries', '2',
                '--contimeout', '15s', '--timeout', '60s', '--log-level', 'ERROR',
                '--stats', '0', '--transfers', '1', '--buffer-size', '0',
                '--streaming-upload-cutoff', '1Mi', '--s3-chunk-size', '8Mi',
                '--s3-upload-concurrency', '1', '--s3-upload-cutoff', '0',
                '--s3-max-upload-parts', '10000']

    def upload(self, key, stages, source_env):
        # A fresh UUID prefix is generated by the caller. Never reuse an object key.
        existing = subprocess.run(self.command('lsjson', key) + ['--stat'], env=self.env,
                                  stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                  timeout=min(30, self.timeout))
        if existing.returncode == 0:
            entry = json.loads(existing.stdout)
            if not (entry.get('IsDir') is True and entry.get('Size') == -1):
                raise BackupError('Existing remote object must never be overwritten')
            # S3 prefixes are virtual: rclone returns this directory sentinel
            # after the exact object lookup misses, even for a nonexistent prefix.
        elif existing.returncode not in (3, 4):
            raise BackupError('Cannot verify absence of target object; refuse upload')
        upload = subprocess.Popen(self.command('rcat', key), env=self.env,
                                  stdin=subprocess.PIPE, stdout=subprocess.DEVNULL,
                                  stderr=subprocess.DEVNULL, start_new_session=True)
        timer = threading.Timer(self.timeout, lambda: _kill(upload))
        timer.daemon = True
        timer.start()
        try:
            result = pipeline(stages, source_env,
                              lambda stream: digest_stream(stream, self.limit, upload.stdin),
                              timeout=self.timeout)
            upload.stdin.close()
            if upload.wait() != 0:
                raise BackupError('Remote upload failed; staged object is not a backup receipt')
            actual = pipeline([self.command('cat', key)], self.env,
                              lambda stream: digest_stream(stream, self.limit), timeout=self.timeout)
            if actual != result:
                raise BackupError('Remote ciphertext read-back differs from uploaded stream')
            return dict(result, object=key, ciphertext_verified=True, restore_verified=False)
        finally:
            timer.cancel()
            _kill(upload)
            upload.wait()
            if not upload.stdin.closed:
                with contextlib.suppress(OSError):
                    upload.stdin.close()


def _kill(process):
    if process.poll() is None:
        with contextlib.suppress(ProcessLookupError):
            os.killpg(process.pid, signal.SIGKILL)


def backup_components(store, producers, source_env, recipient, age, project=PROJECT, attempt_path=None):
    prefix = 'staging/' + datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex
    manifest = {'schema': 1, 'project_ref': project, 'created_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                'recipient': recipient, 'components': [], 'restore_verified': False,
                'status': 'UPLOADED_NOT_RESTORED'}
    if any(not re.fullmatch('[a-z_]+', name) for name, _ in producers):
        raise BackupError('Invalid component name')
    if attempt_path is not None:
        # A durable, explicitly incomplete journal survives a later exporter/network failure.
        write_private(attempt_path, {'status': 'STAGING_ONLY', 'project_ref': project,
                                    'prefix': prefix, 'created_at': manifest['created_at'],
                                    'planned_objects': [prefix + '/' + name + '.sql.gz.age' for name, _ in producers]})
    budget = store.limit
    try:
        for name, command in producers:
            if not re.fullmatch('[a-z_]+', name):
                raise BackupError('Invalid component name')
            stages = [command, ['gzip', '-1', '-c'], [str(age), '-r', recipient]]
            manifest['components'].append(store.upload(prefix + '/' + name + '.sql.gz.age', stages, source_env))
            store.limit = budget - sum(c['bytes'] for c in manifest['components'])
    finally:
        store.limit = budget
    return manifest


def restore_component(store, component, identity, age, consumer, restore_env):
    identity = outside_git(private_file(identity))
    actual = pipeline([store.command('cat', component['object'])], store.env,
                      lambda stream: digest_stream(stream, store.limit), timeout=store.timeout)
    if actual != {k: component[k] for k in ('sha256', 'bytes')}:
        raise BackupError('Stored ciphertext digest/size mismatch; refuse restore')
    # Store.env has only remote credentials, restore_env only local PG credentials.
    # Fetch/decrypt and restore are bridged by bounded pipes, never by a dump file.
    fetch = subprocess.Popen(store.command('cat', component['object']), env=store.env,
                             stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, start_new_session=True)
    timer = threading.Timer(store.timeout, lambda: _kill(fetch))
    timer.daemon = True
    timer.start()
    try:
        pipeline([[str(age), '-d', '-i', str(identity)], ['gzip', '-d', '-c'], consumer],
                 restore_env, input_stream=fetch.stdout, timeout=store.timeout)
        fetch.stdout.close()
        if fetch.wait() != 0:
            raise BackupError('Remote retrieval failed; restore is not verified')
    finally:
        timer.cancel()
        _kill(fetch)
        fetch.wait()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--pg-bin', type=Path, required=True)
    parser.add_argument('--rclone', type=Path, required=True)
    parser.add_argument('--age', type=Path, required=True)
    parser.add_argument('--manifest', type=Path, required=True)
    args = parser.parse_args()
    config = load_config(args.config, production=True)
    if args.manifest.exists():
        raise BackupError('Never overwrite a historical manifest')
    attempt_path = args.manifest.with_suffix('.attempt.json')
    if attempt_path.exists():
        raise BackupError('Never reuse a previous attempt journal')
    outside_git(args.manifest)
    service_file = outside_git(private_file(Path.home() / '.pg_service.conf'))
    password_file = outside_git(private_file(Path.home() / '.pgpass'))
    # The service must be configured locally from this exact project's Connect panel.
    import configparser
    services = configparser.ConfigParser(interpolation=None)
    services.read(service_file)
    section = services['mangaro-production-backup']
    host, user = section.get('host', ''), section.get('user', '')
    direct = host == f'db.{PROJECT}.supabase.co' and user == 'postgres'
    pooler = bool(re.fullmatch(r'[a-z0-9.-]+\.pooler\.supabase\.com', host)) and user == f'postgres.{PROJECT}'
    if not (direct or pooler) or section.get('port', '5432') != '5432' or section.get('sslmode') != 'verify-full':
        raise BackupError('Expected exact project direct/session-pooler identity and verified TLS on5432')
    if section.get('dbname') != 'postgres' or section.get('password'):
        raise BackupError('Unexpected database or plaintext service-file password')
    env = safe_env()
    env.update({'PGSERVICEFILE': str(service_file), 'PGSERVICE': 'mangaro-production-backup',
                'PGPASSFILE': str(password_file), 'PGCONNECT_TIMEOUT': '15',
                'PGOPTIONS': '-c default_transaction_read_only=on -c lock_timeout=5000 -c statement_timeout=1800000'})
    query = "select json_build_object('version',current_setting('server_version'),'role',current_user,'database',current_database(),'readOnly',current_setting('transaction_read_only'))"
    audit = subprocess.run([str(args.pg_bin / 'psql'), '-X', '-w', '-qAt', '-v', 'ON_ERROR_STOP=1', '-c', query],
                           env=env, capture_output=True, timeout=30)
    if audit.returncode:
        raise BackupError('Read-only source connection preflight failed')
    server = json.loads(audit.stdout)
    if server['database'] != 'postgres' or server['readOnly'] != 'on' or not server['version'].startswith('17.'):
        raise BackupError('Wrong source database/version/read-only state')
    store = Store(args.rclone, 'mangarobackup:' + BUCKET, credential_env(config),
                  config['timeout_seconds'], config['max_ciphertext_bytes'])
    producers = [('roles', [str(args.pg_bin / 'pg_dumpall'), '-w', '--roles-only', '--no-role-passwords']),
                 ('database', [str(args.pg_bin / 'pg_dump'), '-w', '--format=plain', '--lock-wait-timeout=5s'])]
    manifest = backup_components(store, producers, env, config['recipient'], args.age, attempt_path=attempt_path)
    manifest['source_server'] = server
    manifest['tools'] = {}
    for name, command in [('pg_dump', [str(args.pg_bin / 'pg_dump'), '--version']),
                          ('age', [str(args.age), '--version']), ('rclone', [str(args.rclone), 'version'])]:
        version = subprocess.run(command, env=safe_env(), capture_output=True, text=True, timeout=15, check=True)
        manifest['tools'][name] = version.stdout.splitlines()[0]
    manifest['scope'] = 'Full pg_dump default schema/data selection plus password-free roles; managed objects and restore coverage require explicit review'
    manifest['storage_object_bytes'] = False
    manifest['external_auth_settings'] = False
    write_private(args.manifest, manifest)
    # An encrypted offsite receipt lets an offline key holder locate the components.
    receipt_key = manifest['components'][0]['object'].rsplit('/', 1)[0] + '/receipt.json.age'
    store.upload(receipt_key, [['cat', str(args.manifest)], [str(args.age), '-r', config['recipient']]], safe_env())
    print('Encrypted components retrieved and digest-verified. Database restore NOT VERIFIED.')


if __name__ == '__main__':
    try:
        main()
    except BackupError as error:
        raise SystemExit('Backup refused/failed: ' + str(error))
    except (OSError, KeyError, ValueError, subprocess.SubprocessError) as error:
        # Never print subprocess output, environment contents or arbitrary exception payloads.
        raise SystemExit('Backup refused/failed (' + type(error).__name__ + '); no successful restore claimed.')
