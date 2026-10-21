#!/usr/bin/env python3
"""Install two checksum-pinned Linux x86-64 tools into an explicit private cache."""
import argparse
import hashlib
import os
from pathlib import Path
import platform
import tarfile
import urllib.request
import zipfile


ASSETS = [
    ('https://github.com/FiloSottile/age/releases/download/v1.3.2/age-v1.3.2-linux-amd64.tar.gz',
     'cbe24006683f8eb669266162894b9a522a1af52f2665fbc63a4bb032ed26ac10'),
    ('https://github.com/rclone/rclone/releases/download/v1.75.2/rclone-v1.75.2-linux-amd64.zip',
     '349ac8fba6ff65d6247043f1750cdcb518ec5d500ef91463a10d37c0ccdf3702'),
]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache', type=Path, required=True)
    args = parser.parse_args()
    if platform.system() != 'Linux' or platform.machine() != 'x86_64':
        raise SystemExit('Only the reviewed Linux x86-64 artifacts are supported')
    os.umask(0o077)
    args.cache.mkdir(mode=0o700, parents=True, exist_ok=True)
    binary = args.cache / 'bin'
    binary.mkdir(mode=0o700, exist_ok=True)
    for url, expected in ASSETS:
        archive = args.cache / url.rsplit('/', 1)[1]
        if not archive.exists():
            with urllib.request.urlopen(url, timeout=60) as source, archive.open('xb') as target:
                while block := source.read(65536):
                    target.write(block)
        with archive.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        if digest != expected:
            raise SystemExit('Tool archive checksum mismatch; refusing extraction')
        if archive.name.endswith('.gz'):
            with tarfile.open(archive) as source:
                for name in ['age', 'age-keygen']:
                    (binary / name).write_bytes(source.extractfile('age/' + name).read())
                    (binary / name).chmod(0o700)
        else:
            with zipfile.ZipFile(archive) as source:
                name = next(n for n in source.namelist() if n.endswith('/rclone'))
                (binary / 'rclone').write_bytes(source.read(name))
                (binary / 'rclone').chmod(0o700)
    print('Pinned age1.3.2/rclone1.75.2 verified and extracted into the explicit cache.')


if __name__ == '__main__':
    main()
