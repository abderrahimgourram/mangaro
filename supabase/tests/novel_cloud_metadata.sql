-- Two LOCAL authenticated PostgreSQL roles/claims, not OAuth/device acceptance. All writes roll back.
begin;
create function pg_temp.assert(ok boolean,label text) returns void language plpgsql as $$ begin if ok is distinct from true then raise exception 'FAIL: %',label; end if;raise notice 'PASS: %',label;end $$;
create function pg_temp.denied(s text) returns void language plpgsql as $$ begin
 begin execute s; exception when insufficient_privilege or check_violation then raise notice 'PASS: denied';return;end;
 raise exception 'Expected denial';end $$;
select set_config('test.a',gen_random_uuid()::text,true),set_config('test.b',gen_random_uuid()::text,true);
insert into auth.users(id) select current_setting(k)::uuid from unnest(array['test.a','test.b']) k;
select set_config('request.jwt.claim.sub',current_setting('test.a'),true);set local role authenticated;
do $$ declare edition text:='novel.sunovels:https://sunovels.com/novel/i-have-an-apocalypse-city'; chapter text:='https://sunovels.com/novel/i-have-an-apocalypse-city/1'; parent jsonb; progress jsonb; result jsonb; rev bigint; pos jsonb;
begin
 pos:=jsonb_build_object('chapter',jsonb_build_object('url',chapter,'title','one','order',0),'paragraph',3,'offset',17,'updatedAt',100);
 parent:=jsonb_build_object('edition_key',encode(sha256(convert_to(edition,'UTF8')),'hex'),'source_id','novel.sunovels','edition_id',edition,'deleted_at',null,
  'state',jsonb_build_object('saved',true,'favorite',false,'readingStatus','reading','lastPosition',pos,'title','TEST fixture','cover',null,'addedAt',1));
 result:=public.cloud_apply_change('cloud_novel_state',parent,0);
 perform pg_temp.assert((result->>'accepted')::boolean,'novel library accepts exact persisted edition and text offset');
 rev:=(result->'row'->>'revision')::bigint;
 progress:=(parent-'state')||jsonb_build_object('chapter_key',encode(sha256(convert_to(chapter,'UTF8')),'hex'),'chapter_url',chapter,'state',jsonb_build_object('position',pos,'bookmarked',true));
 result:=public.cloud_apply_change('cloud_novel_progress',progress,0);
 perform pg_temp.assert((result->>'accepted')::boolean,'edition-specific progress and bookmark persist');
 perform pg_temp.assert(jsonb_array_length(public.cloud_pull_changes('cloud_novel_progress',0,200))=1,'incremental progress pull returns only owner rows');
 perform pg_temp.denied(format('select public.cloud_apply_change(''cloud_novel_state'',%L,0)',(parent||jsonb_build_object('user_id',current_setting('test.b')))::text));
 perform pg_temp.denied(format('select public.cloud_apply_change(''cloud_novel_state'',%L,%s)',(parent||jsonb_build_object('state',(parent->'state')||jsonb_build_object('storyText','forbidden')))::text,rev));
 result:=public.cloud_apply_change('cloud_novel_state',parent||jsonb_build_object('deleted_at',now()),rev);
 perform pg_temp.assert((result->>'accepted')::boolean,'library removal persists tombstone');
 result:=public.cloud_apply_change('cloud_novel_state',parent,rev);
 perform pg_temp.assert(not (result->>'accepted')::boolean and result->'row'->>'deleted_at' is not null,'old offline device cannot resurrect tombstone');
end $$;
reset role;select set_config('request.jwt.claim.sub',current_setting('test.b'),true);set local role authenticated;
select pg_temp.assert((select count(*) from public.cloud_novel_state)=0 and (select count(*) from public.cloud_novel_progress)=0,'second account cannot read first account library, progress or bookmarks');
select pg_temp.assert(public.cloud_pull_changes('cloud_novel_progress',0,200)='[]'::jsonb,'private incremental RPC cannot leak another account');
reset role;set local role anon;
select pg_temp.denied('select * from public.cloud_novel_state');
select pg_temp.denied('select * from public.cloud_novel_progress');
rollback;
