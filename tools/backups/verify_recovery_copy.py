#!/usr/bin/env python3
"""Verify an owner-created private recovery copy on an independent filesystem."""
import argparse
import hashlib
import io
from pathlib import Path
import secrets
import subprocess

from streaming import BackupError, outside_git, pipeline, private_file, safe_env, write_private


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--age',type=Path,required=True)
    parser.add_argument('--original',type=Path,required=True)
    parser.add_argument('--copy',type=Path,required=True)
    parser.add_argument('--result',type=Path,required=True)
    args=parser.parse_args()
    original=outside_git(private_file(args.original))
    recovery=outside_git(private_file(args.copy))
    if original.stat().st_dev==recovery.stat().st_dev:
        raise BackupError('Second key copy must be on a different filesystem, not another laptop folder')
    recipient=subprocess.check_output([str(args.age.parent/'age-keygen'),'-y',str(original)],text=True).strip()
    challenge=secrets.token_bytes(128)
    encrypted=subprocess.run([str(args.age),'-r',recipient],input=challenge,capture_output=True,check=True).stdout
    # Tiny synthetic challenge only; identity and contents are never logged.
    process=subprocess.run([str(args.age),'-d','-i',str(recovery)],input=encrypted,capture_output=True,timeout=15)
    if process.returncode or process.stdout!=challenge:
        raise BackupError('Recovery copy cannot decrypt the synthetic challenge')
    write_private(args.result,{'recipient_sha256':hashlib.sha256(recipient.encode()).hexdigest(),
                               'independent_filesystem':True,'synthetic_decryption_verified':True,
                               'limitations':['Owner must retain the copy safely offline; future availability is operational.']})
    print('Independent-filesystem recovery copy decrypted the synthetic challenge; no secret printed.')


if __name__=='__main__':
    try:
        main()
    except (BackupError,OSError,subprocess.SubprocessError):
        raise SystemExit('RECOVERY KEY REDUNDANCY NOT VERIFIED')
