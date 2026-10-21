#!/usr/bin/env python3
"""Real local S3/age/PostgreSQL drill; deliberately does NOT claim R2 connectivity."""
import argparse
from argparse import Namespace
import hashlib
import json
import os
from pathlib import Path
import resource
import secrets
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

from streaming import (BackupError, Store, backup_components, pipeline,
                       restore_component, safe_env, write_private)

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'supabase/tests'))
from validate_mknov_compatibility import A, B, literal
from validate_mknov_readiness import Readiness


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--root', type=Path, required=True)
    p.add_argument('--pg-bin', type=Path, required=True)
    p.add_argument('--bin', type=Path, required=True)
    p.add_argument('--identity', type=Path, required=True)
    args = p.parse_args()
    os.umask(0o077)
    args.root = args.root.resolve()
    args.root.mkdir(mode=0o700)  # A reused root must fail, never wipe old data.
    if shutil.disk_usage(args.root).free < 1024**3:
        raise BackupError('Need at least1GiB free for bounded synthetic clusters and objects')
    env = safe_env()
    env['PATH'] = str(args.pg_bin) + os.pathsep + env['PATH']
    results, started = [], []

    def record(name):
        results.append({'name': name, 'passed': True})
        print(name + ': PASS', flush=True)

    server = None
    try:
        for name, port, user in [('source', 55541, 'mangaro_source_admin'), ('restore', 55542, 'mangaro_restore_admin')]:
            d = args.root / name
            d.mkdir(mode=0o700)
            (d / 'pg-socket').mkdir(mode=0o700)
            subprocess.run([str(args.pg_bin/'initdb'), '-D', str(d/'pg-data'), '-U', user,
                            '--auth-local=trust', '--auth-host=reject', '--no-locale', '-E', 'UTF8'],
                           env=env, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            subprocess.run([str(args.pg_bin/'pg_ctl'), '-D', str(d/'pg-data'), '-l', str(d/'server.log'),
                            '-o', f"-k {d/'pg-socket'} -p {port} -c listen_addresses='' -c max_connections=12 -c shared_buffers=16MB", 'start'],
                           env=env, check=True, stdout=subprocess.DEVNULL)
            started.append(d)
        source = dict(env, PGHOST=str(args.root/'source/pg-socket'), PGPORT='55541', PGUSER='mangaro_source_admin')
        source['PATH'] = str(args.pg_bin) + os.pathsep + source['PATH']
        fixture = Readiness(Namespace(cluster_root=args.root/'source', port=55541, output=args.root/'fixture.json'))
        fixture.command[0] = str(args.pg_bin/'psql')
        fixture.env = source
        fixture.sql('create database ' + fixture.db, database='postgres')
        source['PGDATABASE'] = fixture.db
        fixture.sql((ROOT/'supabase/tests/fixtures/novel_mknov_local_bootstrap.sql').read_text())
        for file in ['20261003234522_mangaro_local_first_cloud_sync.sql', '20261015175820_novel_cloud_metadata_compatibility.sql']:
            fixture.sql((ROOT/'supabase/migrations'/file).read_text())
        fixture.sql((ROOT/'docs/stabilization/proposals/novel-mknov-compatibility.sql').read_text())
        fixture.sql('insert into auth.users values ('+literal(A)+'),('+literal(B)+')')
        for sid, host in [('novel.kolnovel','kolnovel.com'), ('novel.cenele','cenele.com'),
                          ('novel.sunovels','sunovels.com'), ('novel.seanovel','seanovel.org'), ('novel.mknov','mknov.com')]:
            row = fixture.parent(sid, host, True)
            fixture.apply(row, owner=A)
            fixture.apply(fixture.parent(sid,host), owner=B)
            child = {k:row[k] for k in ['edition_key','source_id','edition_id','deleted_at']}
            url = row['state']['lastPosition']['chapter']['url']
            child.update(chapter_key=hashlib.sha256(url.encode()).hexdigest(),chapter_url=url,
                         state={'position':row['state']['lastPosition'],'bookmarked':True})
            fixture.apply(child,table='cloud_novel_progress')
        fixture.apply(dict(manga_key='a'*64, chapter_key='b'*64, source_chapter_url='/synthetic/chapter',
                           last_page_index=9,total_pages=10,is_read=True,completed_at='2026-10-21T00:00:00Z',
                           state_changed_at='2026-10-21T00:00:00Z',deleted_at=None),table='cloud_chapter_progress')
        fixture.sql("update public.cloud_novel_state set deleted_at='2026-10-21T00:00:00Z' where source_id='novel.sunovels'")
        fixture.sql("create schema supabase_migrations; create table supabase_migrations.schema_migrations(version text primary key); insert into supabase_migrations.schema_migrations values('synthetic_backup_fixture')")
        before = fixture.snapshot()
        record('Existing Mangaro fixtures include both accounts, five providers, bookmarks, comic read state, revisions and tombstones')

        obj = args.root/'s3-objects'
        (obj/'mangaro-secure-backups').mkdir(mode=0o700, parents=True)
        sock = socket.socket()
        sock.bind(('127.0.0.1',0))
        port = sock.getsockname()[1]
        sock.close()
        access, secret = secrets.token_hex(16), secrets.token_hex(32)
        serverenv = dict(env, RCLONE_AUTH_KEY='"'+access+','+secret+'"')
        server = subprocess.Popen([str(args.bin/'rclone'),'serve','s3',str(obj),'--addr',f'127.0.0.1:{port}',
                                   '--config','/dev/null','--log-level','ERROR','--vfs-cache-mode','off',
                                   '--multipart-streaming-buffer-limit','16Mi','--multipart-streaming-buffer-total','32Mi'],
                                  env=serverenv, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        endpoint=f'http://127.0.0.1:{port}'
        deadline=time.monotonic()+10
        while True:
            try:
                urllib.request.urlopen(endpoint,timeout=1)
                raise AssertionError('Anonymous local S3 access was unexpectedly allowed')
            except urllib.error.HTTPError as error:
                # rclone's experimental S3 fixture rejects absent SigV4 with
                # UnsupportedAlgorithm/400; don't accept arbitrary server errors.
                code = ET.fromstring(error.read()).findtext('Code')
                assert error.code in (401,403) or (error.code == 400 and code == 'UnsupportedAlgorithm')
                break
            except (OSError,urllib.error.URLError):
                assert time.monotonic()<deadline and server.poll() is None
                time.sleep(.05)
        record('Signed local S3 fixture rejects an unsigned request; not an R2 privacy audit')
        remoteenv=dict(env,RCLONE_CONFIG_MANGAROBACKUP_TYPE='s3',RCLONE_CONFIG_MANGAROBACKUP_PROVIDER='Other',
                       RCLONE_CONFIG_MANGAROBACKUP_ENDPOINT=endpoint,RCLONE_CONFIG_MANGAROBACKUP_ACCESS_KEY_ID=access,
                       RCLONE_CONFIG_MANGAROBACKUP_SECRET_ACCESS_KEY=secret,RCLONE_CONFIG_MANGAROBACKUP_REGION='us-east-1',
                       RCLONE_CONFIG_MANGAROBACKUP_NO_CHECK_BUCKET='true')
        store=Store(args.bin/'rclone','mangarobackup:mangaro-secure-backups',remoteenv,timeout=90,limit=64*1024**2)
        recipient=subprocess.check_output([str(args.bin/'age-keygen'),'-y',str(args.identity)],text=True).strip()
        # Force real multipart streaming with incompressible input; no large source file.
        for size in [1024,1024**2,17*1024**2]:
            command=[sys.executable,'-c',f'import os,sys; n={size}\nwhile n:\n b=os.urandom(min(n,65536));sys.stdout.buffer.write(b);n-=len(b)']
            m=backup_components(store,[('probe',command)],env,recipient,args.bin/'age',project='synthetic-only')
            receipt=m['components'][0]
            restore_component(store,receipt,args.identity,args.bin/'age',
                              [sys.executable,'-c',f'import sys;assert len(sys.stdin.buffer.read())=={size}'],env)
            record(f'Local S3 encrypted stream/read-back/decrypt {size}bytes')
        exporters=[('roles',[str(args.pg_bin/'pg_dumpall'),'-w','--roles-only','--no-role-passwords']),
                   ('database',[str(args.pg_bin/'pg_dump'),'-w','--format=plain','--lock-wait-timeout=5s'])]
        source['PGOPTIONS']='-c default_transaction_read_only=on'
        manifest=backup_components(store,exporters,source,recipient,args.bin/'age',project='synthetic-only')
        record('Real PostgreSQL role and complete database exports streamed through gzip and age to local S3')
        try:
            store.upload(manifest['components'][0]['object'], [[sys.executable,'-c','print("replacement")']], env)
            raise AssertionError('Historical object overwrite accepted')
        except BackupError:
            record('Existing object cannot be reused or overwritten by a later upload')
        target=dict(env,PGHOST=str(args.root/'restore/pg-socket'),PGPORT='55542',PGUSER='mangaro_restore_admin',PGDATABASE='postgres')
        psql=[str(args.pg_bin/'psql'),'-X','-w','-q','-v','ON_ERROR_STOP=1','--single-transaction']
        restore_component(store,manifest['components'][0],args.identity,args.bin/'age',psql,target)
        subprocess.run([str(args.pg_bin/'createdb'),fixture.db],env=target,check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        target['PGDATABASE']=fixture.db
        restore_component(store,manifest['components'][1],args.identity,args.bin/'age',psql,target)
        record('Actual retrieved ciphertext decrypted and fail-on-error restored in a second PostgreSQL17 cluster')
        command=[str(args.pg_bin/'psql'),'-X','-qAt','-v','ON_ERROR_STOP=1','-h',target['PGHOST'],'-p','55542','-U',target['PGUSER']]
        after=fixture.snapshot(database=fixture.db,command=command)
        assert before==after, 'Restored fixture differs; no private rows printed'
        record('Restored rows, FK/CHECK constraints, RLS, effective grants, column ACLs, indexes, functions, triggers and sequence match')
        def target_query(sql):
            return subprocess.check_output(command+['-d',fixture.db,'-c',sql],env=env,text=True).strip()
        assert target_query('select version from supabase_migrations.schema_migrations')=='synthetic_backup_fixture'
        assert target_query("set role authenticated;set request.jwt.claim.sub='"+A+"'; select count(*) from public.cloud_novel_state")=='5'
        assert target_query("set role authenticated;set request.jwt.claim.sub='"+B+"'; select count(*) from public.cloud_novel_progress")=='0'
        assert target_query("set role authenticated;set request.jwt.claim.sub='"+A+"'; select count(*) from public.cloud_novel_progress")=='5'
        record('Migration history preserved; restored account B cannot read account A progress')
        bad=dict(manifest['components'][1],sha256='0'*64)
        try:
            restore_component(store,bad,args.identity,args.bin/'age',psql,target)
            raise AssertionError('Corrupt receipt accepted')
        except BackupError:
            record('Incorrect ciphertext receipt rejected before any restore')
        try:
            backup_components(store,[('failed',[sys.executable,'-c','print("partial");raise SystemExit(2)'])],env,recipient,args.bin/'age')
            raise AssertionError('Failed producer returned successful receipt')
        except BackupError:
            record('Partial failed export cannot become a completed backup manifest')
        wrong = args.root/'wrong-identity.txt'
        with wrong.open('xb') as output:
            subprocess.run([str(args.bin/'age-keygen')],stdout=output,stderr=subprocess.DEVNULL,check=True)
        wrong.chmod(0o600)
        try:
            restore_component(store,manifest['components'][1],wrong,args.bin/'age',
                              [sys.executable,'-c','import sys;sys.stdin.buffer.read()'],env)
            raise AssertionError('Wrong identity accepted')
        except BackupError:
            record('Wrong recovery identity fails decryption and cannot certify a restore')
        manifest.update(restore_verified=True,verification_transport='LOCAL LOOPBACK S3; NOT CLOUDFLARE R2',
                        tests=len(results),results=results,production_modified=False,
                        peak_child_rss_kib=resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss,
                        limitations=['No real R2 credential or production database used. Offline recovery copy not verified.'])
        write_private(args.root/'result.json',manifest)
    finally:
        if server:
            server.terminate()
            server.wait(timeout=10)
        for d in reversed(started):
            subprocess.run([str(args.pg_bin/'pg_ctl'),'-D',str(d/'pg-data'),'-m','fast','stop'],
                           env=env,check=True,stdout=subprocess.DEVNULL)


if __name__=='__main__':
    main()
