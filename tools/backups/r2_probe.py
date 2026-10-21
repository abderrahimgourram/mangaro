#!/usr/bin/env python3
"""Real R2 synthetic test, only after reviewed replacement credentials are configured."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

from streaming import (BackupError, BUCKET, Store, backup_components, credential_env,
                       load_config, restore_component, safe_env, write_private)


def anonymous_denied(status, body):
    if status in (401,403):
        return True
    if status != 400:
        return False
    try:
        error=ET.fromstring(body)
        # Actual R2 unsigned requests report400/InvalidArgument:Authorization.
        return error.findtext('Code')=='InvalidArgument' and error.findtext('Message')=='Authorization'
    except ET.ParseError:
        return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config',type=Path,required=True)
    parser.add_argument('--rclone',type=Path,required=True)
    parser.add_argument('--age',type=Path,required=True)
    parser.add_argument('--identity',type=Path,required=True)
    parser.add_argument('--result',type=Path,required=True)
    args=parser.parse_args()
    config=load_config(args.config,production=False)
    env=credential_env(config)
    store=Store(args.rclone,'mangarobackup:'+BUCKET,env,timeout=120,limit=1024**2)
    listing=subprocess.run([str(args.rclone),'lsjson','mangarobackup:'+BUCKET,'--config','/dev/null','--max-depth','1'],
                           env=env,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=30)
    if listing.returncode:
        raise BackupError('Authorized bucket listing failed')
    payload='Mangaro disposable encrypted R2 connectivity test'
    manifest=backup_components(store,[('probe',[sys.executable,'-c','print('+repr(payload)+')'])],
                               safe_env(),config['recipient'],args.age,project='synthetic-only')
    component=manifest['components'][0]
    stat=subprocess.run(store.command('lsjson',component['object'])+['--stat'],env=env,
                        capture_output=True,timeout=30)
    if stat.returncode or json.loads(stat.stdout)['Size']!=component['bytes']:
        raise BackupError('Remote object size is not verified')
    restore_component(store,component,args.identity,args.age,
                      [sys.executable,'-c','import sys;assert sys.stdin.read().strip()=='+repr(payload)],safe_env())
    request=urllib.request.Request(config['endpoint']+'/'+BUCKET+'/'+component['object'])
    try:
        with urllib.request.urlopen(request,timeout=20):
            raise BackupError('Unsigned object GET unexpectedly succeeded; stop uploads')
    except urllib.error.HTTPError as error:
        if not anonymous_denied(error.code,error.read(4096)):
            raise BackupError('Anonymous access classification is not verified')
    # Delete ONLY the just-created, digest/decryption-verified disposable synthetic object.
    subprocess.run(store.command('deletefile',component['object']),env=env,check=True,
                   stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=30)
    manifest.update(restore_verified=False,synthetic_content_verified=True,anonymous_s3_get_rejected=True,
                    disposable_object_removed=True,
                    limitations=['S3 probe is not a control-plane token-policy, custom-domain or r2.dev privacy audit.',
                                 'No production database exported or restored.'])
    write_private(args.result,manifest)
    print('Real R2 encrypted synthetic roundtrip verified; disposable object removed.')


if __name__=='__main__':
    try:
        main()
    except (BackupError,OSError,ValueError,KeyError,subprocess.SubprocessError):
        raise SystemExit('R2 verification blocked/failed; no credential values or response bodies printed.')
