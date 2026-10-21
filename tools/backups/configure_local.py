#!/usr/bin/env python3
"""Owner-terminal-only setup. Never accepts credentials as command arguments."""
import getpass
import json
import os
from pathlib import Path
import re
import subprocess
import sys


def main():
    if not sys.stdin.isatty():
        raise SystemExit('Run in your own terminal; never provide secrets in chat or arguments.')
    os.umask(0o077)
    directory = Path.home() / '.config/mangaro-backup'
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    target = directory / 'r2.json'
    if target.exists():
        raise SystemExit('Existing configuration preserved. Review it locally; no overwrite performed.')
    print('Use only the NEW mangaro-backup-writer-v2 R2 S3 Object Read & Write credential,')
    print('restricted to mangaro-secure-backups. Never use the exposed old credential.')
    account = input('Cloudflare account ID (not a secret): ').strip()
    if not re.fullmatch('[a-f0-9]{32}', account):
        raise SystemExit('Invalid account ID')
    access = getpass.getpass('New R2 Access Key ID (hidden): ').strip()
    secret = getpass.getpass('New R2 Secret Access Key (hidden): ').strip()
    if not access or not secret:
        raise SystemExit('Missing new S3 credentials')
    # secret-tool consumes the value on stdin; it never enters argv or this file.
    for field, value in [('access', access), ('secret', secret)]:
        result = subprocess.run(
            ['secret-tool', 'store', '--label=Mangaro bucket-scoped R2 backup ' + field,
             'service', 'Mangaro Backups', 'credential', 'mangaro-backup-writer-v2',
             'account', account, 'field', field],
            input=value.encode(), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        if result.returncode:
            raise SystemExit('Credential-store write failed; no plaintext fallback used.')
    public = Path.home() / '.local/share/mangaro-backup/recovery/recipient.txt'
    recipient = public.read_text().strip()
    config = {'schema': 1, 'project_ref': 'pehsxthjetlltlsqcbfa',
              'account_id': account, 'bucket': 'mangaro-secure-backups',
              'endpoint': f'https://{account}.r2.cloudflarestorage.com',
              'credential_name': 'mangaro-backup-writer-v2', 'recipient': recipient,
              'max_ciphertext_bytes': 1024 ** 3, 'timeout_seconds': 1800,
              'security_review': {'old_token_revoked': False, 'bucket_private': False,
                                  'single_bucket_object_only': False},
              'recovery_copy_verified': False, 'production_coverage_reviewed': False}
    # Flags deliberately remain false. Local setup is not proof of remote policy.
    with target.open('x') as output:
        json.dump(config, output, indent=2)
        output.write('\n')
    print('New credentials saved in the local credential manager. Values were not printed.')
    print('Nonsecret configuration saved with0600 permissions. Remote policy review still required.')


if __name__ == '__main__':
    main()
