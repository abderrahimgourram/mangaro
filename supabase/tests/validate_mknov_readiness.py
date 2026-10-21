#!/usr/bin/env python3
"""Synthetic backup/restore + actual PostgREST/JWT tests. No production connection.

Requires two dedicated PostgreSQL 17 clusters with Unix sockets and a pinned
PostgREST binary. Credentials are generated in memory and never reported.
"""
import argparse
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import time
import urllib.error
import urllib.request
import uuid

from validate_mknov_compatibility import Checks, A, B, literal, ROOT


def equal(a, b):
    assert a == b, ('mismatch', [k for k in a if a.get(k) != b.get(k)] if isinstance(a, dict) and isinstance(b, dict) else (str(a)[:200],str(b)[:200]))


class Readiness(Checks):
    def __init__(self, args):
        super().__init__(args)
        self.artifacts = args.output.parent / ('synthetic-backup-' + uuid.uuid4().hex[:8])
        self.artifacts.mkdir(mode=0o700)

    def snapshot(self, database=None, command=None):
        query = """select jsonb_build_object(
        'data',(select jsonb_agg(to_jsonb(t) order by user_id,edition_key) from public.cloud_novel_state t),
        'progress',(select jsonb_agg(to_jsonb(t) order by user_id,edition_key,chapter_key) from public.cloud_novel_progress t),
        'comicProgress',(select jsonb_agg(to_jsonb(t) order by user_id,manga_key,chapter_key) from public.cloud_chapter_progress t),
        'accounts',(select jsonb_agg(to_jsonb(t) order by id) from auth.users t),
        'constraints',(select jsonb_agg(jsonb_build_array(c.conrelid::regclass::text,c.conname,pg_get_constraintdef(c.oid)) order by c.conname)
          from pg_constraint c where c.connamespace in ('public'::regnamespace,'auth'::regnamespace)),
        'policies',(select jsonb_agg(to_jsonb(p) order by tablename,policyname) from pg_policies p),
        'columns',(select jsonb_agg((to_jsonb(c) - 'table_catalog' - 'udt_catalog' - 'domain_catalog') order by table_schema,table_name,ordinal_position) from information_schema.columns c where table_schema in ('public','auth','mangaro_private')),
        'acl',(select jsonb_agg(jsonb_build_array(n.nspname,c.relname,(select jsonb_agg(jsonb_build_array(r.rolname,case when c.relkind='S' then has_sequence_privilege(r.rolname,c.oid,'USAGE') else has_table_privilege(r.rolname,c.oid,'SELECT') end,case when c.relkind='S' then has_sequence_privilege(r.rolname,c.oid,'UPDATE') else has_table_privilege(r.rolname,c.oid,'UPDATE') end,case when c.relkind='S' then has_sequence_privilege(r.rolname,c.oid,'SELECT') else has_table_privilege(r.rolname,c.oid,'DELETE') end) order by r.rolname) from pg_roles r where (r.rolname in ('anon','authenticated') or r.oid=c.relowner)),c.relrowsecurity) order by n.nspname,c.relname) from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname in ('public','auth','mangaro_private') and c.relkind in ('r','S')),
        'columnAcl',(select jsonb_agg(jsonb_build_array(c.relname,a.attname,a.attacl::text) order by c.relname,a.attname) from pg_attribute a join pg_class c on c.oid=a.attrelid where c.relnamespace='public'::regnamespace and a.attnum>0),
        'functions',(select jsonb_agg(jsonb_build_array(n.nspname,p.proname,pg_get_function_identity_arguments(p.oid),pg_get_functiondef(p.oid),p.proacl::text) order by n.nspname,p.proname) from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname in ('public','auth','mangaro_private')),
        'indexes',(select jsonb_agg(jsonb_build_array(schemaname,tablename,indexname,indexdef) order by indexname) from pg_indexes where schemaname in ('public','auth')),
        'triggers',(select jsonb_agg(pg_get_triggerdef(t.oid) order by t.tgname) from pg_trigger t where not t.tgisinternal and t.tgrelid in (select oid from pg_class where relnamespace='public'::regnamespace)),
        'roles',(select jsonb_agg(jsonb_build_array(rolname,rolsuper,rolbypassrls,rolcanlogin) order by rolname) from pg_roles where rolname in ('anon','authenticated','phase2_api')),
        'sequence',(select last_value from mangaro_private.cloud_sync_revision_seq))"""
        if command:
            p = subprocess.run(command + ['-d', database, '-c', query], env=self.env, capture_output=True, text=True, check=True)
            return json.loads(p.stdout)
        return json.loads(self.sql(query, database=database))

    def run(self):
        equal(Path(self.sql("show data_directory", database='postgres')).resolve(), self.args.cluster_root / 'pg-data')
        equal(self.sql('show server_version', database='postgres'), '17.11')
        self.sql('create database ' + self.db, database='postgres')
        self.sql((ROOT / 'supabase/tests/fixtures/novel_mknov_local_bootstrap.sql').read_text())
        for f in ['20261003234522_mangaro_local_first_cloud_sync.sql', '20261015175820_novel_cloud_metadata_compatibility.sql']:
            self.sql((ROOT / 'supabase/migrations' / f).read_text())
        # Match Supabase Auth claim lookup for real PostgREST request.jwt.claims.
        self.sql("""create or replace function auth.uid() returns uuid language sql stable as $$
         select coalesce(nullif(current_setting('request.jwt.claim.sub',true),''),
           nullif(current_setting('request.jwt.claims',true),'')::jsonb->>'sub')::uuid $$;
         do $$ begin if not exists(select 1 from pg_roles where rolname='phase2_api') then
          create role phase2_api login noinherit nosuperuser nobypassrls; end if; end $$;
         grant anon,authenticated to phase2_api;""")
        self.sql('insert into auth.users values (' + literal(A) + '),(' + literal(B) + ')')
        providers = [('novel.kolnovel','kolnovel.com'), ('novel.cenele','cenele.com'),
                     ('novel.sunovels','sunovels.com'), ('novel.seanovel','seanovel.org')]
        for source, host in providers:
            row = self.parent(source, host, True)
            out = self.apply(row)
            position = row['state']['lastPosition']
            chapter = position['chapter']['url']
            progress = {k: v for k, v in row.items() if k != 'state'}
            progress.update(chapter_key=hashlib.sha256(chapter.encode()).hexdigest(), chapter_url=chapter,
                            state=dict(position=position, bookmarked=True))
            self.apply(progress, table='cloud_novel_progress')
            self.apply(row, owner=B)
            dead = dict(row, deleted_at='2026-10-21T00:00:00Z')
            self.apply(dead, revision=out['row']['revision'])
        self.apply(dict(manga_key='a'*64,chapter_key='b'*64,source_chapter_url='/synthetic/chapter',last_page_index=9,total_pages=10,is_read=True,completed_at='2026-10-21T00:00:00Z',state_changed_at='2026-10-21T00:00:00Z',deleted_at=None),table='cloud_chapter_progress')
        before = self.snapshot()
        backup = self.artifacts / 'pre-migration.dump'
        globals_file = self.artifacts / 'globals.sql'
        self.check('pg_dump custom archive produced from synthetic pre-migration database', lambda: subprocess.run(
            ['pg_dump', '-h', str(self.args.cluster_root/'pg-socket'), '-p', str(self.args.port), '-Fc', '-f', str(backup), self.db], env=self.env, check=True))
        listing = subprocess.check_output(['pg_restore', '-l', str(backup)], text=True, env=self.env)
        self.check('backup archive contains data, constraints, policies and grants', lambda: [
            equal(token in listing, True) for token in ['TABLE DATA public cloud_novel_state', 'TABLE DATA public cloud_novel_progress', 'POLICY', 'ACL', 'CONSTRAINT']])
        globals_sql = subprocess.check_output(['pg_dumpall', '-h', str(self.args.cluster_root/'pg-socket'), '-p', str(self.args.port), '--globals-only', '--no-role-passwords'], text=True, env=self.env)
        globals_file.write_text(globals_sql)
        backup.chmod(0o600); globals_file.chmod(0o600)
        self.check('backup has no stored role passwords', lambda: equal(' PASSWORD ' in globals_sql, False))
        self.sql(self.proposal)
        self.check('migration does not rewrite any existing provider rows or bookmarks', lambda: (
            equal(self.snapshot()['data'], before['data']), equal(self.snapshot()['progress'], before['progress']),
            equal(self.snapshot()['comicProgress'], before['comicProgress'])))
        m = self.parent('novel.mknov', 'mknov.com', True)
        self.apply(m)
        self.check('old supported provider rows remain owner-readable after migration', lambda: equal(self.sql('select count(*) from cloud_novel_progress', owner=A), '4'))
        self.check('new account cannot read previous account positions', lambda: equal(self.sql('select count(*) from cloud_novel_progress', owner=B), '0'))
        # Restore into a different, explicitly identified isolated PostgreSQL cluster.
        restore_cmd = ['psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-h', str(self.args.restore_cluster_root/'pg-socket'), '-p', str(self.args.restore_port)]
        data = subprocess.check_output(restore_cmd + ['-d','postgres','-c','show data_directory'], text=True, env=self.env).strip()
        equal(Path(data).resolve(), self.args.restore_cluster_root/'pg-data')
        user = self.sql('select current_user', database='postgres')
        # initdb already creates the current local cluster owner; restore its attributes,
        # all test roles/memberships, but do not attempt to create that one role twice.
        globals_restore = '\n'.join(line for line in globals_sql.splitlines() if line != 'CREATE ROLE ' + user + ';')
        subprocess.run(restore_cmd + ['-d','postgres'], input=globals_restore, text=True, env=self.env, check=True, capture_output=True)
        restored = self.db + '_restore'
        subprocess.run(restore_cmd + ['-d','postgres','-c','create database '+restored], env=self.env, check=True)
        self.check('real pg_restore into second cluster succeeds without ignored errors', lambda: subprocess.run(
            ['pg_restore','--exit-on-error','-h',str(self.args.restore_cluster_root/'pg-socket'),'-p',str(self.args.restore_port),'-d',restored,str(backup)], env=self.env, check=True))
        after_restore = self.snapshot(restored, restore_cmd)
        (self.artifacts/'before.json').write_text(json.dumps(before,indent=2))
        (self.artifacts/'restored.json').write_text(json.dumps(after_restore,indent=2))
        self.check('restored data, keys, columns, constraints, RLS, grants, functions, indexes and revisions match exactly', lambda: equal(after_restore, before))
        subprocess.run(restore_cmd + ['-d',restored,'-c',self.proposal], env=self.env, check=True, capture_output=True)
        self.check('restored backup accepts the same forward migration', lambda: equal(self.snapshot(restored, restore_cmd)['constraints'], self.snapshot()['constraints']))
        self.http_checks(m)
        return dict(status='PASS', tests=len(self.results), passed=len(self.results), results=self.results,
                    database=self.db, serverVersion='17.11', postgrestVersion='16.5', productionModified=False,
                    proposalSHA256=hashlib.sha256(self.proposal.encode()).hexdigest(),
                    backupSHA256=hashlib.sha256(backup.read_bytes()).hexdigest(),
                    syntheticBackupDirectory=str(self.artifacts), limitations=[
                        'Synthetic JWT issuer, not GoTrue OAuth/login/refresh acceptance.',
                        'PostgREST 16.5 local; production PostgREST version not exposed by project-scoped metadata.',
                        'No production backup or restore performed; management backup entitlement/retention remains unverified.',
                        'SQL payloads mirror Android schema-1 codec; actual old Android binaries not driven through this API.'])

    def http_checks(self, m):
        secret = secrets.token_hex(32).encode()
        def b64(data):
            return base64.urlsafe_b64encode(data).rstrip(b'=')
        def jwt(owner, expiry=3600, key=secret):
            parts = [b64(b'{"alg":"HS256","typ":"JWT"}'), b64(json.dumps(dict(sub=owner,role='authenticated',exp=int(time.time())+expiry)).encode())]
            data = b'.'.join(parts)
            return (data+b'.'+b64(hmac.new(key,data,hashlib.sha256).digest())).decode()
        with socket.socket() as sock:
            sock.bind(('127.0.0.1',0)); port=sock.getsockname()[1]
        env=dict(self.env, PGRST_DB_URI=f'postgresql://phase2_api@/{self.db}?host={self.args.cluster_root}/pg-socket&port={self.args.port}',
                 PGRST_DB_SCHEMAS='public',PGRST_DB_ANON_ROLE='anon',PGRST_JWT_SECRET=secret.decode(),
                 PGRST_SERVER_HOST='127.0.0.1',PGRST_SERVER_PORT=str(port),PGRST_DB_POOL='2')
        def request(path, data=None, owner=A, token=None, method=None):
            headers={'Content-Type':'application/json'}
            if owner is not None: headers['Authorization']='Bearer '+(token or jwt(owner,key=secret))
            req=urllib.request.Request(f'http://127.0.0.1:{port}/'+path, data=json.dumps(data).encode() if data is not None else None,headers=headers,method=method)
            try:
                with urllib.request.urlopen(req,timeout=10) as response: return response.status,json.loads(response.read() or 'null')
            except urllib.error.HTTPError as error: return error.code,json.loads(error.read() or 'null')
        with (self.artifacts/'postgrest.log').open('w') as log:
            proc=subprocess.Popen([str(self.args.postgrest)],env=env,stdout=log,stderr=log)
            try:
                deadline=time.monotonic()+15
                while True:
                    try: request('cloud_novel_state?limit=0'); break
                    except urllib.error.URLError:
                        assert proc.poll() is None, 'PostgREST startup failed; inspect synthetic log'
                        assert time.monotonic()<deadline, 'PostgREST startup timed out'
                        time.sleep(.1)
                self.check('HTTP capability schema remains one for old clients',lambda:equal(request('rpc/cloud_novel_capabilities',{})[1]['schema'],1))
                self.check('HTTP authenticated A reads its own five editions',lambda:equal(len(request('cloud_novel_state')[1]),5))
                self.check('HTTP B sees only its own four editions',lambda:equal(len(request('cloud_novel_state',owner=B)[1]),4))
                self.check('HTTP A to B to A never leaks account-scoped rows',lambda:equal({r['user_id'] for r in request('cloud_novel_state')[1]}, {A}))
                self.check('HTTP anonymous cannot read private novel metadata',lambda:equal(request('cloud_novel_state',owner=None)[0],401))
                self.check('HTTP expired JWT rejected before SQL work',lambda:equal(request('cloud_novel_state',token=jwt(A,-100,key=secret))[0],401))
                self.check('HTTP invalid JWT signature rejected',lambda:equal(request('cloud_novel_state',token=jwt(A,key=b'wrong-key-at-least-thirty-two-bytes'))[0],401))
                def apply(row, revision=0, owner=A, table='cloud_novel_state'):
                    return request('rpc/cloud_apply_change',dict(p_table=table,p_row=row,p_expected_revision=revision),owner=owner)
                self.check('HTTP B cannot spoof A ownership',lambda:equal(apply(dict(m,user_id=A),owner=B)[0],403))
                current=next(x for x in request('cloud_novel_state')[1] if x['source_id']=='novel.mknov')
                pos=m['state']['lastPosition']; ch=pos['chapter']['url']
                progress={k:v for k,v in m.items() if k!='state'}
                progress.update(chapter_key=hashlib.sha256(ch.encode()).hexdigest(),chapter_url=ch,state=dict(position=pos,bookmarked=True))
                self.check('HTTP valid Mknov position and bookmark accepted',lambda:equal(apply(progress,table='cloud_novel_progress')[1]['accepted'],True))
                rows=request('cloud_novel_progress')[1]
                self.check('HTTP actual chapter identity and offsets preserved',lambda:equal(next(r for r in rows if r['source_id']=='novel.mknov')['state'],progress['state']))
                self.check('HTTP revision conflict on stale/lost-ack retry is explicit',lambda:equal(apply(progress,table='cloud_novel_progress')[1]['accepted'],False))
                self.check('HTTP replay never inserts duplicate chapter rows',lambda:equal(len(request('cloud_novel_progress')[1]),5))
                bad=dict(progress,chapter_url='https://mknov.com/novel/97/chapter/20716')
                bad['chapter_key']=hashlib.sha256(bad['chapter_url'].encode()).hexdigest()
                self.check('HTTP chapter from wrong Mknov work rejected',lambda:equal(apply(bad,table='cloud_novel_progress')[0],400))
                wrong_parent=dict(m,state=dict(m['state'],lastPosition=dict(pos,chapter=dict(pos['chapter'],url=bad['chapter_url']))))
                self.check('HTTP parent cannot restore a chapter from a different Mknov work',lambda:equal(apply(wrong_parent,current['revision'])[0],400))
                for url in ['https://api.mknov.com/novel/96','https://mknov.com/novel/96?x=1',
                            'https://mknov.com/novel/0','https://mknov.com/novel/96#x','https://mknov.com/novel/96/../97']:
                    self.check('strict canonical Mknov URL rejects '+url,lambda u=url:equal(self.sql("select mangaro_private.cloud_novel_url('novel.mknov',"+literal(u)+')',owner=A),'f'))
                legacy=dict(m,state=dict(m['state'],lastPosition=dict(pos,paragraph=1)))
                self.check('HTTP old schema-one client update still accepted',lambda:equal(apply(legacy,current['revision'])[1]['accepted'],True))
                invalid=dict(m,state=dict(m['state'],lastPosition=dict(pos,completed=True)))
                current=next(x for x in request('cloud_novel_state')[1] if x['source_id']=='novel.mknov')
                self.check('Mknov migration alone does not pretend completion schema is supported',lambda:equal(apply(invalid,current['revision'])[0],400))
            finally:
                proc.terminate();proc.wait(timeout=10)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--cluster-root',type=Path,required=True);p.add_argument('--port',type=int,default=55485)
    p.add_argument('--restore-cluster-root',type=Path,required=True);p.add_argument('--restore-port',type=int,default=55486)
    p.add_argument('--postgrest',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    args=p.parse_args()
    args.cluster_root=args.cluster_root.resolve();args.restore_cluster_root=args.restore_cluster_root.resolve()
    assert args.cluster_root!=args.restore_cluster_root
    runner=Readiness(args)
    try: result=runner.run()
    except Exception as error:
        args.output.write_text(json.dumps(dict(status='FAIL',results=runner.results,error=str(error)),indent=2));raise
    args.output.write_text(json.dumps(result,indent=2)+'\n')


if __name__=='__main__': main()
