#!/usr/bin/env python3
"""Disposable Unix-socket PostgreSQL contract test. Never connects to Supabase."""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
A = '10000000-0000-0000-0000-000000000001'
B = '10000000-0000-0000-0000-000000000002'


def literal(value):
    return "'" + value.replace("'", "''") + "'"


class Checks:
    def __init__(self, args):
        self.args = args
        self.env = {k: v for k, v in os.environ.items() if not k.startswith('PG')}
        self.command = ['psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-v', 'VERBOSITY=verbose',
                        '-h', str(args.cluster_root / 'pg-socket'), '-p', str(args.port)]
        self.db = 'mangaro_mknov_' + uuid.uuid4().hex[:10]
        self.results = []
        self.proposal = (ROOT / 'docs/stabilization/proposals/novel-mknov-compatibility.sql').read_text()

    def sql(self, sql, owner=None, expected=None, database=None):
        if owner:
            sql = ('set role authenticated; set request.jwt.claim.sub=' + literal(owner) + '; ' + sql)
        p = subprocess.run(self.command + ['-d', database or self.db, '-c', sql],
                           env=self.env, text=True, capture_output=True, timeout=45)
        if expected:
            assert p.returncode != 0 and expected in p.stderr, (expected, p.returncode, p.stderr)
            return None
        assert p.returncode == 0, p.stderr
        return p.stdout.strip()

    def check(self, name, fn):
        started = time.monotonic()
        fn()
        result = dict(name=name, passed=True, seconds=round(time.monotonic() - started, 3))
        self.results.append(result)
        print(json.dumps(result), flush=True)

    @staticmethod
    def equal(actual, expected):
        assert actual == expected, (actual, expected)

    def schema_snapshot(self):
        return self.sql("""select jsonb_build_object(
          'url', pg_get_functiondef('mangaro_private.cloud_novel_url(text,text)'::regprocedure),
          'constraints',(select jsonb_agg(jsonb_build_array(conname,pg_get_constraintdef(oid)) order by conname)
             from pg_constraint where conrelid='public.cloud_novel_state'::regclass),
          'rows',(select count(*) from public.cloud_novel_state))""")

    def security_snapshot(self):
        return self.sql("""select jsonb_build_object(
         'policies',(select jsonb_agg(to_jsonb(p) order by tablename,policyname) from pg_policies p
           where tablename in ('cloud_novel_state','cloud_novel_progress')),
         'grants',(select jsonb_agg(to_jsonb(g) order by table_name,column_name,grantee,privilege_type)
           from information_schema.column_privileges g where table_schema='public'
            and table_name in ('cloud_novel_state','cloud_novel_progress')),
         'validator',(select jsonb_build_array(proacl::text,prosecdef,provolatile,proconfig) from pg_proc where oid='mangaro_private.cloud_novel_url(text,text)'::regprocedure),
         'rls',(select jsonb_agg(jsonb_build_array(relname,relrowsecurity) order by relname)
           from pg_class where oid in ('public.cloud_novel_state'::regclass,'public.cloud_novel_progress'::regclass)))""")

    @staticmethod
    def parent(source, host, position=False):
        url = 'https://' + host + ('/novel/96' if source == 'novel.mknov' else '/novel/contract-fixture')
        edition = source + ':' + url
        row = dict(edition_key=hashlib.sha256(edition.encode()).hexdigest(), source_id=source,
                   edition_id=edition, deleted_at=None,
                   state=dict(saved=True, favorite=False, readingStatus='reading', lastPosition=None,
                              title='Synthetic contract fixture', cover=None, addedAt=1))
        if position:
            row['state']['lastPosition'] = dict(chapter=dict(url=url + '/chapter/20716', title='One', order=0),
                                               paragraph=3, offset=17, updatedAt=100)
        return row

    def apply(self, row, owner=A, revision=0, table='cloud_novel_state', expected=None):
        result = self.sql('select public.cloud_apply_change(' + literal(table) + ',' +
                          literal(json.dumps(row)) + '::jsonb,' + str(revision) + ');',
                          owner=owner, expected=expected)
        return json.loads(result) if result else None

    def run(self):
        data = self.sql("select current_setting('data_directory');", database='postgres')
        assert Path(data).resolve() == (self.args.cluster_root / 'pg-data').resolve(), 'Wrong local cluster'
        self.sql('create database ' + self.db, database='postgres')
        bootstrap = (ROOT / 'supabase/tests/fixtures/novel_mknov_local_bootstrap.sql').read_text()
        self.sql(bootstrap)
        for file in ['20261003234522_mangaro_local_first_cloud_sync.sql',
                     '20261015175820_novel_cloud_metadata_compatibility.sql']:
            self.sql((ROOT / 'supabase/migrations' / file).read_text())
        version=self.sql('show server_version')
        assert version.startswith('17.'), 'This metadata fixture targets PostgreSQL 17; use the matching disposable server'
        deployed=json.loads((ROOT/'supabase/tests/fixtures/novel_mknov_deployed_contract.json').read_text())
        local=json.loads(self.sql("select jsonb_agg(jsonb_build_object('table_name',c.conrelid::regclass::text,'name',c.conname,'definition',pg_get_constraintdef(c.oid)) order by c.conname) from pg_constraint c where c.conrelid in ('public.cloud_novel_state'::regclass,'public.cloud_novel_progress'::regclass)"))
        self.check('local constraints match authorized deployed metadata snapshot',lambda:self.equal(local,deployed['constraints']))
        for function in deployed['functions']:
            name=function['name']
            expected=re.search(r'AS \$function\$([\s\S]*)\$function\$',function['definition']).group(1).strip()
            actual=self.sql("select prosrc from pg_proc where pronamespace='mangaro_private'::regnamespace and proname="+literal(name)).strip()
            self.check('deployed validator body matches '+name,lambda a=actual,e=expected:self.equal(a,e))
        self.sql('insert into auth.users(id) values (' + literal(A) + '),(' + literal(B) + ')')
        version = self.sql('show server_version')
        original, security = self.schema_snapshot(), self.security_snapshot()
        self.check('authenticated role cannot bypass RLS', lambda: self.equal(
            self.sql("select rolsuper or rolbypassrls from pg_roles where rolname='authenticated'"), 'f'))
        sources = [('novel.kolnovel','kolnovel.com'),('novel.cenele','cenele.com'),
                   ('novel.sunovels','sunovels.com'),('novel.seanovel','seanovel.org')]
        for source, host in sources:
            self.check('current old-client write ' + source, lambda s=source,h=host: self.equal(
                self.apply(self.parent(s,h))['accepted'], True))
        before = self.schema_snapshot()
        mknov = self.parent('novel.mknov','mknov.com',True)
        self.check('current schema rejects Mknov', lambda: self.apply(mknov, expected='23514'))
        assert self.proposal.rstrip().endswith('commit;')
        rollback = self.proposal.rstrip()[:-len('commit;')] + '\nrollback;'
        self.check('transaction rollback preserves function, constraints and old rows', lambda: (
            self.sql(rollback), self.equal(self.schema_snapshot(),before)))

        # Hold only this disposable table; the real proposal must fail at its five-second lock bound.
        locker = subprocess.Popen(self.command + ['-d', self.db, '-c',
            "begin; lock public.cloud_novel_state in access exclusive mode; select pg_sleep(7); rollback;"],
            env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            deadline = time.monotonic() + 3
            while self.sql("select count(*) from pg_locks where relation='public.cloud_novel_state'::regclass and mode='AccessExclusiveLock' and granted") != '1':
                assert time.monotonic() < deadline, 'Lock holder did not start'
                time.sleep(0.05)
            self.check('real proposal lock_timeout aborts at five seconds', lambda: self.sql(self.proposal,expected='55P03'))
        finally:
            locker.communicate(timeout=12)
        self.check('lock failure rolls back every schema change', lambda: self.equal(self.schema_snapshot(),before))
        timeout_sql = self.proposal.rstrip()[:-len('commit;')] + '\nselect pg_sleep(31);\ncommit;'
        self.check('real thirty-second statement timeout cancels and rolls back', lambda: self.sql(timeout_sql,expected='57014'))
        self.check('statement timeout preserves previous schema and data',lambda: self.equal(self.schema_snapshot(),before))
        injected=self.proposal.replace('alter table public.cloud_novel_state rename constraint', "select 'abort after old constraint drop'::integer;\nalter table public.cloud_novel_state rename constraint")
        self.check('error after old constraint drop rolls back entire proposal',lambda:self.sql(injected,expected='22P02'))
        self.check('failed midway DDL restores original constraint and validator',lambda:self.equal(self.schema_snapshot(),before))
        self.check('proposed extension applies atomically',lambda:self.sql(self.proposal))
        self.check('RLS, grants and table security unchanged', lambda:self.equal(self.security_snapshot(),security))
        self.check('proposal can run twice without changing policy or losing data', lambda:(
            self.sql(self.proposal),self.equal(self.security_snapshot(),security)))
        old_url=next(f['definition'] for f in deployed['functions'] if f['name']=='cloud_novel_url')
        reverse=("begin; alter table public.cloud_novel_state drop constraint cloud_novel_state_mknov_work_check; alter table public.cloud_novel_progress drop constraint cloud_novel_progress_mknov_work_check; set local lock_timeout='5s'; set local statement_timeout='30s'; "
                 "alter table public.cloud_novel_state add constraint revert_sources check(source_id in ('novel.kolnovel','novel.cenele','novel.sunovels','novel.seanovel')) not valid; "
                 "alter table public.cloud_novel_state validate constraint revert_sources; "
                 "alter table public.cloud_novel_state drop constraint cloud_novel_state_source_id_check; "
                 "alter table public.cloud_novel_state rename constraint revert_sources to cloud_novel_state_source_id_check; "
                 +old_url+"; commit;")
        self.check('committed proposal reverses safely before any Mknov write',lambda:(
            self.sql(reverse),self.equal(self.schema_snapshot(),before),self.equal(self.security_snapshot(),security)))
        self.sql(self.proposal)
        self.check('Mknov new-client position accepted with independent edition identity',lambda:self.equal(
            self.apply(mknov)['accepted'],True))
        pos = mknov['state']['lastPosition']; chapter = pos['chapter']['url']
        progress = {k:v for k,v in mknov.items() if k != 'state'}
        progress.update(chapter_key=hashlib.sha256(chapter.encode()).hexdigest(),chapter_url=chapter,
                        state=dict(position=pos,bookmarked=True))
        self.check('Mknov chapter position and bookmark roundtrip',lambda:self.equal(
            self.apply(progress,table='cloud_novel_progress')['row']['state'],progress['state']))
        for source,host in sources:
            row=self.parent(source,host); previous=self.apply(row)['row']
            saved=self.apply(row,revision=previous['revision'])
            self.check('old client write and roundtrip after extension '+source,lambda d=saved,r=row:(
                self.equal(d['accepted'],True),self.equal(d['row']['state'],r['state'])))
        self.check('A owner sees five source editions',lambda:self.equal(self.sql('select count(*) from public.cloud_novel_state',owner=A),'5'))
        self.check('B cannot read A library or progress',lambda:self.equal(self.sql(
            'select (select count(*) from public.cloud_novel_state)+(select count(*) from public.cloud_novel_progress)',owner=B),'0'))
        spoof=dict(mknov,user_id=A)
        self.check('B cannot spoof A through RPC',lambda:self.apply(spoof,owner=B,expected='42501'))
        self.check('B cannot spoof A through direct INSERT',lambda:self.sql(
            "insert into public.cloud_novel_state(user_id,edition_key,source_id,edition_id,state) select " + literal(A) +
            "::uuid," + literal(mknov['edition_key'])+','+literal(mknov['source_id'])+','+
            literal(mknov['edition_id'])+','+literal(json.dumps(mknov['state']))+'::jsonb', owner=B,expected='42501'))
        self.check('B update cannot affect invisible A row',lambda:self.equal(self.sql(
            'with changed as (update public.cloud_novel_state set state=state returning 1) select count(*) from changed',owner=B),'0'))
        self.check('A to B to A restores only A data',lambda:self.equal(self.sql('select count(*) from public.cloud_novel_state',owner=A),'5'))
        self.check('B writes only its own independent source edition',lambda:self.equal(
            self.apply(self.parent('novel.seanovel','seanovel.org'),owner=B)['accepted'],True))
        self.check('B sees one own edition and A still sees five',lambda:(
            self.equal(self.sql('select count(*) from public.cloud_novel_state',owner=B),'1'),
            self.equal(self.sql('select count(*) from public.cloud_novel_state',owner=A),'5')))
        self.check('missing authenticated identity cannot start privileged sync',lambda:self.sql(
            "set role authenticated; set request.jwt.claim.sub=''; select public.cloud_apply_change('cloud_novel_state','{}'::jsonb,0)",expected='42501'))
        self.check('anonymous role cannot read private novel metadata',lambda:self.sql(
            'set role anon; select * from public.cloud_novel_state',expected='42501'))
        self.check('source identities remain insert-only',lambda:self.sql(
            "update public.cloud_novel_state set source_id='novel.cenele'",owner=A,expected='42501'))
        current=self.apply(mknov)['row']; rev=current['revision']
        dead=dict(mknov,deleted_at='2026-10-20T00:00:00Z')
        self.check('owner tombstone mutation accepted',lambda:self.equal(self.apply(dead,revision=rev)['accepted'],True))
        self.check('stale client retry cannot resurrect tombstone',lambda:self.equal(self.apply(mknov,revision=rev)['accepted'],False))
        for url in ['http://mknov.com/novel/1','https://mknov.com.evil.org/novel/1',
                    'https://mknov.com@evil.org/novel/1','https://cenele.com/novel/1','https://mknov.com:444/novel/1']:
            self.check('reject unrelated/unsafe Mknov URL '+url,lambda u=url:self.equal(self.sql(
                "select mangaro_private.cloud_novel_url('novel.mknov',"+literal(u)+')',owner=A),'f'))
        bad=dict(mknov,state=dict(mknov['state'],storyText='Not allowed'))
        self.check('story text forbidden in metadata payload',lambda:self.apply(bad,owner=B,expected='23514'))
        position=dict(pos,completed=True)
        self.check('schema-one historical completion remains unsupported',lambda:self.equal(self.sql(
            "select mangaro_private.cloud_novel_position('novel.mknov',"+literal(json.dumps(position))+"::jsonb)",owner=A),'f'))
        old_constraint="alter table public.cloud_novel_state add constraint rollback_old_sources check(source_id in ('novel.kolnovel','novel.cenele','novel.sunovels','novel.seanovel')) not valid; alter table public.cloud_novel_state validate constraint rollback_old_sources;"
        snapshot=self.schema_snapshot()
        self.check('naive old constraint rollback refuses new Mknov rows without deleting them',lambda:self.sql('begin;'+old_constraint+'commit;',expected='23514'))
        self.check('failed rollback leaves accepted data intact',lambda:self.equal(self.schema_snapshot(),snapshot))
        self.sql((ROOT/'supabase/tests/novel_cloud_metadata.sql').read_text())
        self.check('existing authenticated source isolation and tombstone SQL regression',lambda:None)
        return dict(serverVersion=version,database=self.db,tests=len(self.results),passed=len(self.results),
                    results=self.results,productionModified=False,
                    proposalSHA256=hashlib.sha256(self.proposal.encode()).hexdigest(),
                    limitations=['Synthetic database roles/claims, not OAuth or Android network acceptance.',
                                 'Run on the recorded serverVersion; production version was 17.11 at inspection.',
                                 'Restoring old source restriction after new Mknov writes needs a data-preserving forward plan.'])


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cluster-root',type=Path,required=True)
    parser.add_argument('--port',type=int,default=55484)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();args.cluster_root=args.cluster_root.resolve()
    assert (args.cluster_root/'pg-socket').is_dir() and (args.cluster_root/'pg-data/PG_VERSION').is_file(), 'Dedicated local cluster required'
    runner=Checks(args)
    try:
        report=runner.run()
    except Exception as error:
        args.output.write_text(json.dumps(dict(status='FAIL',completed=runner.results,error=str(error)),indent=2))
        raise
    args.output.write_text(json.dumps(dict(status='PASS',**report),indent=2)+'\n')


if __name__=='__main__':
    main()
