#!/usr/bin/env python3
"""Actual loopback PostgREST/JWT/RLS acceptance of the undeployed completion proposal.

Only a freshly created synthetic database in a caller-owned disposable cluster.
No credentials, production URLs, real accounts, backfill or XP claims.
"""
import argparse
import base64
import hashlib
import hmac
import json
from pathlib import Path
import secrets
import socket
import subprocess
import time
import urllib.error
import urllib.request

from validate_mknov_compatibility import Checks, ROOT, A, B, literal


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--cluster-root', type=Path, required=True)
    p.add_argument('--port', type=int, default=55485)
    p.add_argument('--postgrest', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    args.cluster_root = args.cluster_root.resolve()
    runner = Checks(args)
    sql, eq, check = runner.sql, runner.equal, runner.check
    proposal = ROOT/'docs/stabilization/proposals/novel-completion-evidence-v2.sql'
    proc = None
    try:
        eq(Path(sql('show data_directory', database='postgres')).resolve(), args.cluster_root/'pg-data')
        eq(sql('show server_version', database='postgres'), '17.11')
        sql('create database '+runner.db, database='postgres')
        sql((ROOT/'supabase/tests/fixtures/novel_mknov_local_bootstrap.sql').read_text())
        for name in ['20261003234522_mangaro_local_first_cloud_sync.sql',
                     '20261015175820_novel_cloud_metadata_compatibility.sql']:
            sql((ROOT/'supabase/migrations'/name).read_text())
        sql("""create or replace function auth.uid() returns uuid language sql stable as $$
          select coalesce(nullif(current_setting('request.jwt.claim.sub',true),''),
            nullif(current_setting('request.jwt.claims',true),'')::jsonb->>'sub')::uuid $$;
          do $$ begin if not exists(select 1 from pg_roles where rolname='phase2_api') then
            create role phase2_api login noinherit nosuperuser nobypassrls; end if; end $$;
          grant anon,authenticated to phase2_api;""")
        sql('insert into auth.users values ('+literal(A)+'),('+literal(B)+')')
        security = runner.security_snapshot()
        sql(proposal.read_text())
        check('proposal leaves policy and grant snapshot unchanged', lambda:eq(runner.security_snapshot(), security))
        sql('create table public.completion_api_xp_sentinel(total bigint); insert into completion_api_xp_sentinel values(123)')
        # A temporary JWT signing key exists only in this isolated process environment.
        secret = secrets.token_bytes(32)
        def b64(x):
            return base64.urlsafe_b64encode(x).rstrip(b'=')
        def jwt(owner, seconds=3600):
            body=b'.'.join([b64(b'{"alg":"HS256","typ":"JWT"}'),
                            b64(json.dumps(dict(sub=owner,role='authenticated',exp=int(time.time())+seconds)).encode())])
            return (body+b'.'+b64(hmac.new(secret,body,hashlib.sha256).digest())).decode()
        # PostgREST expects the textual key, so encode once and sign with those bytes.
        secret = secret.hex().encode()
        with socket.socket() as sock:
            sock.bind(('127.0.0.1',0)); port=sock.getsockname()[1]
        env=dict(runner.env,
                 PGRST_DB_URI=f'postgresql://phase2_api@/{runner.db}?host={args.cluster_root}/pg-socket&port={args.port}',
                 PGRST_DB_SCHEMAS='public', PGRST_DB_ANON_ROLE='anon', PGRST_JWT_SECRET=secret.decode(),
                 PGRST_SERVER_HOST='127.0.0.1', PGRST_SERVER_PORT=str(port), PGRST_DB_POOL='2')
        def request(path, data=None, owner=A, token=None):
            headers={'Content-Type':'application/json'}
            if owner is not None:
                headers['Authorization']='Bearer '+(token or jwt(owner))
            req=urllib.request.Request(f'http://127.0.0.1:{port}/{path}',
                data=None if data is None else json.dumps(data).encode(), headers=headers)
            try:
                with urllib.request.urlopen(req,timeout=10) as response:
                    return response.status,json.loads(response.read() or 'null')
            except urllib.error.HTTPError as error:
                return error.code,json.loads(error.read() or 'null')
        with (args.output.parent/'completion-api-postgrest.log').open('w') as log:
            proc=subprocess.Popen([str(args.postgrest.resolve())],env=env,stdout=log,stderr=log)
            deadline=time.monotonic()+15
            while True:
                try:
                    request('cloud_novel_state?limit=0'); break
                except urllib.error.URLError:
                    assert proc.poll() is None, 'Synthetic PostgREST failed to start'
                    assert time.monotonic()<deadline, 'Synthetic PostgREST timed out'
                    time.sleep(.1)
            def apply(row, revision=0, owner=A, table='cloud_novel_progress'):
                return request('rpc/cloud_apply_change',dict(p_table=table,p_row=row,p_expected_revision=revision),owner)
            def stats(owner=A):
                code,body=request('rpc/cloud_own_novel_completion_statistics',{},owner)
                eq(code,200)
                return body
            check('HTTP capability advertises schema-one plus explicit completion evidence version one',lambda:(
                eq(request('rpc/cloud_novel_capabilities',{})[0],200),
                eq(request('rpc/cloud_novel_capabilities',{})[1]['completionEvidence'],1),
                eq(request('rpc/cloud_novel_capabilities',{})[1]['schema'],1)))
            check('HTTP anonymous private access rejected',lambda:eq(request('cloud_novel_progress',owner=None)[0],401))
            check('HTTP expired session rejected',lambda:eq(request('cloud_novel_progress',token=jwt(A,-100))[0],401))
            parent=runner.parent('novel.cenele','cenele.com',True)
            eq(apply(parent,table='cloud_novel_state')[1]['accepted'],True)
            pos=parent['state']['lastPosition']; url=pos['chapter']['url']
            row={k:v for k,v in parent.items() if k!='state'}
            row.update(chapter_key=hashlib.sha256(url.encode()).hexdigest(),chapter_url=url,
                       state=dict(position=pos,bookmarked=True))
            first=apply(row)[1]['row']
            check('HTTP existing schema-one position is not fabricated completion',lambda:eq(stats()['confirmedReadChapters'],0))
            completed=dict(row,state=dict(position=dict(pos,completed=True),bookmarked=True))
            code,body=apply(completed,first['revision']);eq(code,200);eq(body['accepted'],True)
            current=body['row']
            check('HTTP genuine explicit evidence counts one exact chapter',lambda:eq(stats()['confirmedReadChapters'],1))
            check('HTTP lost acknowledgement retry returns a conflict',lambda:eq(apply(completed,first['revision'])[1]['accepted'],False))
            check('HTTP repeated events keep exactly one row and one count',lambda:(
                eq(len(request('cloud_novel_progress')[1]),1),eq(stats()['confirmedReadChapters'],1)))
            for value in ['omitted',False,None]:
                position=dict(pos,paragraph=1)
                if value!='omitted': position['completed']=value
                legacy=dict(row,state=dict(position=position,bookmarked=False))
                code,body=apply(legacy,current['revision']);eq(code,200);eq(body['accepted'],True)
                current=body['row']
                check('HTTP legacy '+str(value)+' preserves true and legitimate cursor edit',lambda:(
                    eq(current['state']['position']['completed'],True),
                    eq(current['state']['position']['paragraph'],1)))
            check('HTTP account A B A completion statistics remain separate',lambda:eq(
                [stats(A)['confirmedReadChapters'],stats(B)['confirmedReadChapters'],stats(A)['confirmedReadChapters']],[1,0,1]))
            check('HTTP account B cannot see A progress',lambda:eq(request('cloud_novel_progress',owner=B)[1],[]))
            check('HTTP account B cannot spoof A write',lambda:eq(apply(dict(completed,user_id=A),owner=B)[0],403))
            invalid_url=url+'2'
            invalid=dict(row,chapter_url=invalid_url,chapter_key=hashlib.sha256(invalid_url.encode()).hexdigest(),
                         state=dict(position=dict(pos,chapter=dict(pos['chapter'],url=invalid_url),
                                                  completed='true'),bookmarked=True))
            check('HTTP invalid type cannot insert fabricated completion',lambda:eq(apply(invalid)[0],400))
            other=runner.parent('novel.seanovel','seanovel.org',True)
            eq(apply(other,table='cloud_novel_state')[1]['accepted'],True)
            op=other['state']['lastPosition'];ou=op['chapter']['url']
            child={k:v for k,v in other.items() if k!='state'}
            child.update(chapter_key=hashlib.sha256(ou.encode()).hexdigest(),chapter_url=ou,
                         state=dict(position=dict(op,completed=True),bookmarked=False))
            eq(apply(child)[1]['accepted'],True)
            check('HTTP same titles on different provider identities remain independent',lambda:eq(stats()['confirmedReadChapters'],2))
            removed=dict(row,deleted_at='2026-10-21T00:00:00Z')
            current=apply(removed,current['revision'])[1]['row']
            check('HTTP tombstone removes active count and preserves durable evidence',lambda:(
                eq(stats()['confirmedReadChapters'],1),eq(current['state']['position']['completed'],True)))
            check('HTTP stale offline completion cannot resurrect deletion',lambda:eq(apply(completed,first['revision'])[1]['accepted'],False))
            check('HTTP statistics preserve unknown historical completeness',lambda:eq(stats()['historicalComplete'],False))
            check('HTTP sync and statistics perform no XP award',lambda:eq(sql('select total from completion_api_xp_sentinel'),'123'))
        result=dict(status='PASS',tests=len(runner.results),passed=len(runner.results),results=runner.results,
                    proposalSHA256=hashlib.sha256(proposal.read_bytes()).hexdigest(),productionModified=False,
                    coverage='Actual isolated PostgreSQL 17.11 and PostgREST with locally signed synthetic JWTs; not production GoTrue or physical-device acceptance.')
    except Exception as error:
        args.output.write_text(json.dumps(dict(status='FAIL',results=runner.results,error=str(error)),indent=2)+'\n')
        raise
    finally:
        if proc is not None:
            proc.terminate();proc.wait(timeout=10)
    args.output.write_text(json.dumps(result,indent=2)+'\n')


if __name__ == '__main__':
    main()
