#!/usr/bin/env python3
"""Reject database artifacts and recognizable credentials in the staged diff."""
from pathlib import Path
import re
import subprocess


def unsafe(name, content):
    p = Path(name)
    if (p.suffix in {'.age', '.dump', '.pgdump', '.jks', '.keystore'} or
            p.name in {'rclone.conf', '.pgpass', '.pg_service.conf'} or
            '/recovery/identity' in '/' + name or name.endswith('.sql.gz')):
        return True
    patterns = [rb'AGE-SECRET-KEY-1[0-9A-Z]{40,}',
                rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----',
                rb'postgres(?:ql)?://[^\s:/]+:[^\s@]+@',
                rb'(?:AKIA|ASIA)[A-Z0-9]{16}',
                rb'eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}']
    return any(re.search(pattern, content) for pattern in patterns)


def main():
    names = subprocess.check_output(['git', 'diff', '--cached', '--name-only', '--diff-filter=ACMR', '-z']).decode().split('\0')
    rejected = []
    for name in filter(None, names):
        content = subprocess.check_output(['git', 'show', ':' + name])
        if unsafe(name, content):
            rejected.append(name)
    if rejected:
        raise SystemExit('Staged sensitive artifacts detected; no values printed. Files: ' + repr(rejected))
    print('Staged artifact/credential pattern audit passed. This is not a universal secret detector.')


if __name__ == '__main__':
    main()
