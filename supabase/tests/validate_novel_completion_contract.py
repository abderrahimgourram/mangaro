#!/usr/bin/env python3
"""Local-only proposal tests. No backfill, production credentials or network SQL."""
import argparse
import hashlib
import json
from pathlib import Path
import sys
from concurrent.futures import ThreadPoolExecutor


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--cluster-root', type=Path, required=True)
    p.add_argument('--port', type=int, default=55485)
    p.add_argument('--support-root', type=Path, default=Path(__file__).parent)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    args.cluster_root = args.cluster_root.resolve()
    sys.path.insert(0, str(args.support_root.resolve()))
    from validate_mknov_compatibility import Checks, ROOT, A, B, literal
    runner = Checks(args)
    eq = runner.equal
    sql = runner.sql
    check = runner.check
    target = Path(__file__).resolve().parents[2]
    proposal = (target/'docs/stabilization/proposals/novel-completion-evidence-v2.sql').read_text()
    try:
        eq(Path(sql('show data_directory', database='postgres')).resolve(), args.cluster_root/'pg-data')
        eq(sql('show server_version', database='postgres'), '17.11')
        sql('create database '+runner.db, database='postgres')
        sql((ROOT/'supabase/tests/fixtures/novel_mknov_local_bootstrap.sql').read_text())
        for f in ['20261003234522_mangaro_local_first_cloud_sync.sql', '20261015175820_novel_cloud_metadata_compatibility.sql']:
            sql((ROOT/'supabase/migrations'/f).read_text())
        sql('insert into auth.users values ('+literal(A)+'),('+literal(B)+')')
        security = runner.security_snapshot()
        url_before = sql("select prosrc from pg_proc where oid='mangaro_private.cloud_novel_url(text,text)'::regprocedure")
        parent = runner.parent('novel.cenele','cenele.com',True)
        runner.apply(parent)
        pos = parent['state']['lastPosition']
        chapter = pos['chapter']['url']
        progress = {k:v for k,v in parent.items() if k!='state'}
        progress.update(chapter_key=hashlib.sha256(chapter.encode()).hexdigest(), chapter_url=chapter,
                        state=dict(position=pos,bookmarked=True))
        initial = runner.apply(progress,table='cloud_novel_progress')['row']
        check('legacy server does not accept unnegotiated completion',lambda:eq(sql(
            'select mangaro_private.cloud_novel_position(\'novel.cenele\','+literal(json.dumps(dict(pos,completed=True)))+'::jsonb)',owner=A),'f'))
        sql(proposal)
        check('completion contract does not alter source URL validation',lambda:eq(sql(
            "select prosrc from pg_proc where oid='mangaro_private.cloud_novel_url(text,text)'::regprocedure"),url_before))
        check('RLS and existing column grants unchanged',lambda:eq(runner.security_snapshot(),security))
        sql(proposal)
        check('proposal idempotently reapplies without losing original data',lambda:eq(sql('select count(*) from cloud_novel_progress',owner=A),'1'))
        def stats(owner=A):
            return json.loads(sql('select public.cloud_own_novel_completion_statistics()',owner=owner))
        check('existing positions do not fabricate historical completion',lambda:eq(stats(),dict(confirmedReadChapters=0,historicalComplete=False,evidenceContract=1)))
        # A synthetic XP sentinel proves the proposal, sync and aggregate never award XP.
        sql('create table public.phase2_xp_sentinel(user_id uuid primary key,total_xp bigint); insert into public.phase2_xp_sentinel values ('+literal(A)+',123),('+literal(B)+',456)')
        xp = sql('select jsonb_agg(t order by user_id) from phase2_xp_sentinel t')
        completed = dict(progress,state=dict(position=dict(pos,completed=True),bookmarked=True))
        accepted = runner.apply(completed,revision=initial['revision'],table='cloud_novel_progress')['row']
        check('explicit reader-end evidence counts once',lambda:eq(stats()['confirmedReadChapters'],1))
        check('lost acknowledgement retry returns a revision conflict',lambda:eq(runner.apply(completed,revision=initial['revision'],table='cloud_novel_progress')['accepted'],False))
        check('retry does not count twice',lambda:eq(stats()['confirmedReadChapters'],1))
        old_client = dict(progress,state=dict(position=dict(pos,paragraph=1),bookmarked=False))
        accepted = runner.apply(old_client,revision=accepted['revision'],table='cloud_novel_progress')['row']
        check('old schema-one client cannot erase newer completion',lambda:eq(accepted['state']['position']['completed'],True))
        check('old client position and bookmark changes remain valid',lambda:(eq(accepted['state']['position']['paragraph'],1),eq(accepted['state']['bookmarked'],False)))
        stale = accepted['revision']
        explicit_false = dict(progress,state=dict(position=dict(pos,completed=False),bookmarked=True))
        accepted = runner.apply(explicit_false,revision=stale,table='cloud_novel_progress')['row']
        check('later reread false preserves genuine completed evidence',lambda:eq(accepted['state']['position']['completed'],True))
        check('out-of-order revision cannot overwrite progress',lambda:eq(runner.apply(old_client,revision=stale,table='cloud_novel_progress')['accepted'],False))
        null_position = dict(progress,state=dict(position=None,bookmarked=False))
        accepted = runner.apply(null_position,revision=accepted['revision'],table='cloud_novel_progress')['row']
        check('legacy null position cannot discard genuine completion',lambda:eq(accepted['state']['position']['completed'],True))
        check('account B statistics never include account A',lambda:eq(stats(B)['confirmedReadChapters'],0))
        check('account A to B to A preserves owner-only total',lambda:eq([stats(A)['confirmedReadChapters'],stats(B)['confirmedReadChapters'],stats(A)['confirmedReadChapters']],[1,0,1]))
        check('anonymous aggregate access denied',lambda:sql('set role anon; select public.cloud_own_novel_completion_statistics()',expected='42501'))
        check('authenticated role without identity gets no aggregate',lambda:eq(sql("set role authenticated; set request.jwt.claim.sub=''; select public.cloud_own_novel_completion_statistics() is null"),'t'))
        check('cross-account completion write rejected',lambda:runner.apply(dict(completed,user_id=A),owner=B,table='cloud_novel_progress',expected='42501'))
        for value in ['true',1,{},[]]:
            check('invalid completion type rejected '+json.dumps(value),lambda v=value:eq(sql(
                "select mangaro_private.cloud_novel_position_v2('novel.cenele',"+literal(json.dumps(dict(pos,completed=v)))+'::jsonb)',owner=A),'f'))
        check('new contract still rejects content bodies',lambda:eq(sql(
            "select mangaro_private.cloud_novel_position_v2('novel.cenele',"+literal(json.dumps(dict(pos,completed=True,prose='forbidden')))+'::jsonb)',owner=A),'f'))
        # A different chapter and another provider retain independent exact identities.
        for idx,source,host in [(1,'novel.cenele','cenele.com'),(2,'novel.seanovel','seanovel.org')]:
            par=runner.parent(source,host,True)
            if source!='novel.cenele': runner.apply(par)
            url=par['state']['lastPosition']['chapter']['url']+str(idx)
            position=dict(pos,chapter=dict(pos['chapter'],url=url),completed=True)
            row={k:v for k,v in par.items() if k!='state'}
            row.update(chapter_key=hashlib.sha256(url.encode()).hexdigest(),chapter_url=url,state=dict(position=position,bookmarked=False))
            runner.apply(row,table='cloud_novel_progress')
        check('different source-qualified chapters count independently',lambda:eq(stats()['confirmedReadChapters'],3))
        dead = dict(progress,deleted_at='2026-10-21T00:00:00Z')
        accepted=runner.apply(dead,revision=accepted['revision'],table='cloud_novel_progress')['row']
        check('tombstone hides removed history without destroying evidence',lambda:(eq(stats()['confirmedReadChapters'],2),eq(accepted['state']['position']['completed'],True)))
        check('stale retry cannot resurrect tombstoned history',lambda:eq(runner.apply(completed,revision=stale,table='cloud_novel_progress')['accepted'],False))
        check('statistics never claim unknowable historical completeness',lambda:eq(stats()['historicalComplete'],False))
        check('sync and statistics do not change any synthetic XP value',lambda:eq(sql('select jsonb_agg(t order by user_id) from phase2_xp_sentinel t'),xp))
        check('capability is additive and compatible with exact schema-one table checks',lambda:(
            eq(json.loads(sql('select cloud_novel_capabilities()',owner=A))['schema'],1),
            eq(json.loads(sql('select cloud_novel_capabilities()',owner=A))['tables'],['cloud_novel_state','cloud_novel_progress'])))
        sql(runner.proposal)
        m=runner.parent('novel.mknov','mknov.com',True)
        runner.apply(m)
        mpos=dict(m['state']['lastPosition'],completed=True)
        murl=mpos['chapter']['url']
        mrow={k:v for k,v in m.items() if k!='state'}
        mrow.update(chapter_key=hashlib.sha256(murl.encode()).hexdigest(),chapter_url=murl,state=dict(position=mpos,bookmarked=True))
        check('Mknov migration after completion proposal accepts exact completed identity',lambda:eq(runner.apply(mrow,table='cloud_novel_progress')['accepted'],True))
        murl2=murl+'1'
        mrow2=dict(mrow,chapter_key=hashlib.sha256(murl2.encode()).hexdigest(),chapter_url=murl2,
                   state=dict(position=dict(mpos,chapter=dict(mpos['chapter'],url=murl2)),bookmarked=False))
        with ThreadPoolExecutor(max_workers=2) as pool:
            attempts=list(pool.map(lambda _:runner.apply(mrow2,table='cloud_novel_progress'),range(2)))
        check('concurrent devices completing one new chapter accept one insert and one explicit conflict',lambda:eq(sorted(r['accepted'] for r in attempts),[False,True]))
        check('concurrent retries yield one source-qualified chapter count',lambda:eq(stats()['confirmedReadChapters'],4))
        before_reverse=sql('select jsonb_agg(t order by user_id,edition_key,chapter_key) from cloud_novel_progress t')
        check('legacy-only completion downgrade refuses evidence instead of deleting it',lambda:sql(
            "begin; alter table cloud_novel_progress add constraint phase2_legacy_completion check (not ((state->'position') ? 'completed')) not valid; alter table cloud_novel_progress validate constraint phase2_legacy_completion; commit;",expected='23514'))
        check('failed downgrade preserves every completion and reading position',lambda:eq(sql(
            'select jsonb_agg(t order by user_id,edition_key,chapter_key) from cloud_novel_progress t'),before_reverse))
        # Reverse deployment order also works, with a fresh independent database.
        reverse=Checks(args)
        reverse.sql('create database '+reverse.db,database='postgres')
        reverse.sql((ROOT/'supabase/tests/fixtures/novel_mknov_local_bootstrap.sql').read_text())
        for f in ['20261003234522_mangaro_local_first_cloud_sync.sql','20261015175820_novel_cloud_metadata_compatibility.sql']:
            reverse.sql((ROOT/'supabase/migrations'/f).read_text())
        reverse.sql('insert into auth.users values ('+literal(A)+')')
        reverse.sql(reverse.proposal)
        reverse.sql(proposal)
        reverse.apply(m)
        check('completion proposal after Mknov migration accepts exact completed identity',lambda:eq(reverse.apply(mrow,table='cloud_novel_progress')['accepted'],True))
        result=dict(status='PASS',tests=len(runner.results),passed=len(runner.results),results=runner.results,
                    proposalSHA256=hashlib.sha256(proposal.encode()).hexdigest(),productionModified=False,
                    limitations=['SQL proposal only; Android schema-one codec intentionally still omits completed.',
                                 'Synthetic XP sentinel; existing production XP implementation untouched, not rerun here.',
                                 'No backfill or authoritative historical completeness claim; no OAuth acceptance.'])
    except Exception as error:
        args.output.write_text(json.dumps(dict(status='FAIL',results=runner.results,error=str(error)),indent=2));raise
    args.output.write_text(json.dumps(result,indent=2)+'\n')


if __name__ == '__main__':
    main()
