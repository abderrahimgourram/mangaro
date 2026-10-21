#!/usr/bin/env python3
"""Create two local-only clusters, run the phase-2 suites, stop both on every exit."""
import argparse
import os
from pathlib import Path
import subprocess
import sys


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--root', type=Path, required=True)
    p.add_argument('--postgres-bin', type=Path, required=True)
    p.add_argument('--postgrest', type=Path, required=True)
    args = p.parse_args()
    args.root = args.root.resolve()
    args.root.mkdir(mode=0o700)  # Refuse existing data directories; never recycle a cluster.
    binary = args.postgres_bin.resolve()
    env = {k:v for k,v in os.environ.items() if not k.startswith(('PG','PGRST_'))}
    env['PATH'] = str(binary) + os.pathsep + env['PATH']
    started = []
    suite = Path(__file__).resolve().parent
    try:
        for name,port in [('primary',55485),('restore',55486)]:
            root = args.root/name
            root.mkdir(mode=0o700); (root/'pg-socket').mkdir(mode=0o700)
            subprocess.run([str(binary/'initdb'),'-D',str(root/'pg-data'),'--auth-local=trust',
                            '--auth-host=reject','--no-locale','-E','UTF8'],env=env,check=True,stdout=(root/'init.log').open('w'))
            subprocess.run([str(binary/'pg_ctl'),'-D',str(root/'pg-data'),'-l',str(root/'server.log'),
                            '-o',f"-k {root/'pg-socket'} -p {port} -c listen_addresses='' -c max_connections=20 -c shared_buffers=32MB",'start'],env=env,check=True)
            started.append(root)
        shared=['--cluster-root',str(args.root/'primary'),'--port','55485']
        commands=[
            [sys.executable,str(suite/'validate_mknov_compatibility.py'),*shared,'--output',str(args.root/'compatibility.json')],
            [sys.executable,str(suite/'validate_mknov_readiness.py'),*shared,'--restore-cluster-root',str(args.root/'restore'),
             '--restore-port','55486','--postgrest',str(args.postgrest.resolve()),'--output',str(args.root/'readiness.json')],
        ]
        if (suite/'validate_novel_completion_contract.py').is_file():
            commands.append([sys.executable,str(suite/'validate_novel_completion_contract.py'),*shared,'--output',str(args.root/'completion.json')])
        commands.append([sys.executable,str(suite/'validate_completion_api.py'),*shared,
                         '--postgrest',str(args.postgrest.resolve()),'--output',str(args.root/'completion-api.json')])
        for i,command in enumerate(commands):
            with (args.root/f'suite-{i}.log').open('w') as log:
                subprocess.run(command,env=env,check=True,stdout=log,stderr=log,timeout=180)
    finally:
        for root in reversed(started):
            subprocess.run([str(binary/'pg_ctl'),'-D',str(root/'pg-data'),'-m','fast','stop'],env=env,check=True)


if __name__ == '__main__':
    main()
