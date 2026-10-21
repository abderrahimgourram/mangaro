import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

from streaming import (BackupError, Store, digest_stream, load_config, outside_git,
                       pipeline, private_file, safe_env, write_private)
from audit_staged import unsafe


class StreamTests(unittest.TestCase):
    def test_binary_digest_preserves_bytes_and_size(self):
        output = io.BytesIO()
        result = digest_stream(io.BytesIO(b'\x00abc' * 100000), 500000, output)
        self.assertEqual(result['bytes'], 400000)
        self.assertEqual(output.getvalue(), b'\x00abc' * 100000)

    def test_byte_budget_fails(self):
        with self.assertRaises(BackupError):
            digest_stream(io.BytesIO(b'1234'), 3)

    def test_upstream_failure_cannot_be_hidden_by_successful_downstream(self):
        with self.assertRaises(BackupError):
            pipeline([[sys.executable, '-c', 'import sys;print("partial");sys.exit(9)'],
                      [sys.executable, '-c', 'import sys;sys.stdout.buffer.write(sys.stdin.buffer.read())']],
                     safe_env(), timeout=5)

    def test_downstream_failure_cannot_be_hidden(self):
        with self.assertRaises(BackupError):
            pipeline([[sys.executable, '-c', 'print("test")'],
                      [sys.executable, '-c', 'raise SystemExit(7)']], safe_env(), timeout=5)

    def test_stderr_is_drained_without_private_error_output(self):
        result = pipeline([[sys.executable, '-c', 'import sys;sys.stderr.write("private"*100000);print("ok")']],
                          safe_env(), lambda s: s.read(), timeout=5)
        self.assertEqual(result, b'ok\n')

    def test_timeout_stops_children(self):
        started = time.monotonic()
        with self.assertRaises(BackupError):
            pipeline([[sys.executable, '-c', 'import time;time.sleep(30)']], safe_env(), timeout=0.2)
        self.assertLess(time.monotonic() - started, 5)

    def test_symlink_and_world_readable_identity_rejected(self):
        with tempfile.TemporaryDirectory() as name:
            p = Path(name) / 'key'
            p.write_text('not an actual key')
            p.chmod(0o644)
            with self.assertRaises(BackupError):
                private_file(p)
            p.chmod(0o600)
            link = Path(name) / 'link'
            link.symlink_to(p)
            with self.assertRaises(BackupError):
                private_file(link)
            self.assertEqual(private_file(p), p)

    def test_sensitive_paths_inside_git_rejected(self):
        with tempfile.TemporaryDirectory() as name:
            (Path(name) / '.git').mkdir()
            with self.assertRaises(BackupError):
                outside_git(Path(name) / 'identity.txt')

    def test_manifest_never_overwrites(self):
        with tempfile.TemporaryDirectory() as name:
            p = Path(name) / 'receipt.json'
            write_private(p, {'restore_verified': False})
            self.assertEqual(p.stat().st_mode & 0o777, 0o600)
            with self.assertRaises(FileExistsError):
                write_private(p, {'restore_verified': True})

    def test_no_inherited_cross_account_destinations(self):
        with patch.dict(os.environ, {'PGHOST': 'production', 'RCLONE_CONFIG': 'wrong', 'AWS_SECRET_ACCESS_KEY': 'fixture'}):
            env = safe_env()
        self.assertNotIn('PGHOST', env)
        self.assertNotIn('AWS_SECRET_ACCESS_KEY', env)
        self.assertNotIn('RCLONE_CONFIG', env)

    def test_exact_endpoint_and_replacement_credential_required(self):
        for change in [{'endpoint': 'https://example.org'}, {'credential_name': 'mangaro-backup-writer'},
                       {'project_ref': 'unrelated'}, {'bucket': 'unrelated'},
                       {'recovery_copy_verified': False}, {'production_coverage_reviewed': False},
                       {'security_review': {'old_token_revoked': False}}, {'max_ciphertext_bytes': -1}]:
            with self.subTest(change=change), tempfile.TemporaryDirectory() as name:
                data = self.valid_config()
                data.update(change)
                p = Path(name) / 'config.json'
                write_private(p, data)
                with self.assertRaises(BackupError):
                    load_config(p, production=True)

    def test_reviewed_configuration_passes_validation_not_remote_verification(self):
        with tempfile.TemporaryDirectory() as name:
            p = Path(name) / 'config.json'
            write_private(p, self.valid_config())
            self.assertEqual(load_config(p, production=True)['bucket'], 'mangaro-secure-backups')

    def test_object_traversal_and_unbounded_buffering_rejected(self):
        store = Store('rclone', 'test:bucket', {})
        for key in ['../secret', '/absolute', 'a/../../b', 'a:unexpected']:
            with self.assertRaises(BackupError):
                store.command('cat', key)
        args = store.command('rcat', 'staging/unique/database.age')
        self.assertIn('8Mi', args)
        self.assertEqual(args[args.index('--s3-upload-concurrency') + 1], '1')
        self.assertNotIn('--size', args)  # Ciphertext size is unknown until EOF.

    def test_secret_artifacts_rejected_even_if_force_staged(self):
        for name in ['x.age','x.dump','.pgpass','rclone.conf','recovery/identity.txt']:
            self.assertTrue(unsafe(name,b''))
        self.assertTrue(unsafe('notes.txt',b'AGE-SECRET-KEY-1'+b'X'*58))
        self.assertTrue(unsafe('notes.txt',b'postgresql'+b'://owner:password@example.test/db'))
        self.assertFalse(unsafe('source.py',b"name='AWS_SECRET_ACCESS_KEY'"))

    @staticmethod
    def valid_config():
        return {'schema': 1, 'project_ref': 'pehsxthjetlltlsqcbfa', 'account_id': 'a'*32,
                'bucket': 'mangaro-secure-backups', 'endpoint': 'https://'+'a'*32+'.r2.cloudflarestorage.com',
                'credential_name': 'mangaro-backup-writer-v2', 'recipient': 'age1'+'a'*58,
                'max_ciphertext_bytes': 1024**3, 'timeout_seconds': 60,
                'security_review': {'old_token_revoked': True, 'bucket_private': True, 'single_bucket_object_only': True},
                'recovery_copy_verified': True, 'production_coverage_reviewed': True}


if __name__ == '__main__':
    unittest.main()
