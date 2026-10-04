-- All fixtures and writes roll back. Never disables RLS or touches existing user data.
begin;
select set_config('mangaro.sync.a',gen_random_uuid()::text,true),set_config('mangaro.sync.b',gen_random_uuid()::text,true),set_config('mangaro.sync.collection',gen_random_uuid()::text,true);
insert into auth.users(id,raw_app_meta_data,raw_user_meta_data)
select current_setting(k)::uuid,'{"provider":"google"}'::jsonb,'{}'::jsonb from unnest(array['mangaro.sync.a','mangaro.sync.b']) k;
create function pg_temp.denied(s text,expected text[] default array['42501']) returns void language plpgsql as $$
begin
 begin execute s; exception when others then if sqlstate=any(expected) then return; end if; raise; end;
 raise exception 'Expected denial: %',s;
end; $$;
set local role anon;
do $$ declare t text; begin
 foreach t in array array['cloud_library_entries','cloud_library_collections','cloud_library_entry_collections','cloud_manga_history','cloud_chapter_progress'] loop
  perform pg_temp.denied(format('select * from public.%I',t));
  perform pg_temp.denied(format('update public.%I set deleted_at=now()',t));
 end loop;
 perform pg_temp.denied('select public.cloud_pull_changes(''cloud_library_entries'',0,200)');
 perform pg_temp.denied('select public.cloud_apply_change(''cloud_library_entries'',''{}'',0)');
end $$;
reset role;
select set_config('request.jwt.claim.sub',current_setting('mangaro.sync.a'),true),set_config('request.jwt.claim.role','authenticated',true);
set local role authenticated;
do $$ declare rowdata jsonb; result jsonb; rev bigint; rev2 bigint; begin
 rowdata:=jsonb_build_object('manga_key',repeat('a',64),'source_id',123,'source_manga_url','/private/work','title_snapshot','Private work','deleted_at',null);
 result:=public.cloud_apply_change('cloud_library_entries',rowdata,0);
 if not (result->>'accepted')::boolean then raise exception 'INSERT_FAILED'; end if;
 rev:=(result->'row'->>'revision')::bigint;
 if rev<=0 then raise exception 'REVISION_NOT_STAMPED'; end if;
 result:=public.cloud_apply_change('cloud_library_entries',rowdata,0);
 if (result->>'accepted')::boolean then raise exception 'STALE_ACCEPTED'; end if;
 if (select count(*) from public.cloud_library_entries)<>1 then raise exception 'DUPLICATE'; end if;
 result:=public.cloud_apply_change('cloud_library_entries',rowdata||jsonb_build_object('deleted_at',now()),rev);
 rev2:=(result->'row'->>'revision')::bigint;
 if rev2<=rev or result->'row'->>'deleted_at' is null then raise exception 'TOMBSTONE_FAILED'; end if;
 if jsonb_array_length(public.cloud_pull_changes('cloud_library_entries',rev,200))<>1 then raise exception 'INCREMENTAL_FAILED'; end if;
 perform pg_temp.denied('update public.cloud_library_entries set revision=9999');
 perform pg_temp.denied('update public.cloud_library_entries set updated_at=now()');
 perform pg_temp.denied('delete from public.cloud_library_entries');
 perform pg_temp.denied(format('select public.cloud_apply_change(''cloud_library_entries'',%L,0)',(rowdata||jsonb_build_object('user_id',current_setting('mangaro.sync.b')))::text));
 perform pg_temp.denied(format('select public.cloud_apply_change(''cloud_library_entries'',%L,0)',(rowdata||jsonb_build_object('revision',1))::text));
 perform pg_temp.denied(format('select public.cloud_apply_change(''cloud_library_entries'',%L,0)',(rowdata||jsonb_build_object('manga_key','invalid'))::text),array['23514']);
 perform public.cloud_apply_change('cloud_library_collections',jsonb_build_object('id',current_setting('mangaro.sync.collection'),'name','Custom','sort_order',0,'deleted_at',null),0);
 perform public.cloud_apply_change('cloud_library_entry_collections',jsonb_build_object('manga_key',repeat('a',64),'collection_id',current_setting('mangaro.sync.collection'),'deleted_at',null),0);
 result:=public.cloud_apply_change('cloud_library_entry_collections',jsonb_build_object('manga_key',repeat('a',64),'collection_id',current_setting('mangaro.sync.collection'),'deleted_at',now()),(select revision from public.cloud_library_entry_collections));
 if result->'row'->>'deleted_at' is null then raise exception 'MEMBERSHIP_TOMBSTONE'; end if;
 rowdata:=jsonb_build_object('manga_key',repeat('b',64),'chapter_key',repeat('c',64),'source_chapter_url','/private/chapter','last_page_index',8,'total_pages',12,'is_read',false,'state_changed_at',now(),'deleted_at',null);
 result:=public.cloud_apply_change('cloud_chapter_progress',rowdata,0);
 perform public.cloud_apply_change('cloud_chapter_progress',rowdata,(result->'row'->>'revision')::bigint);
 if (select count(*) from public.cloud_chapter_progress)<>1 then raise exception 'PROGRESS_DUPLICATE'; end if;
 perform pg_temp.denied(format('select public.cloud_apply_change(''cloud_chapter_progress'',%L,0)',(rowdata||jsonb_build_object('chapter_key',repeat('d',64),'last_page_index',-1))::text),array['23514']);
 perform pg_temp.denied(format('select public.cloud_apply_change(''cloud_chapter_progress'',%L,0)',(rowdata||jsonb_build_object('chapter_key',repeat('d',64),'total_pages',0))::text),array['23514']);
 perform pg_temp.denied(format('select public.cloud_apply_change(''cloud_chapter_progress'',%L,0)',(rowdata||jsonb_build_object('chapter_key','local-id'))::text),array['23514']);
 perform public.cloud_apply_change('cloud_manga_history',jsonb_build_object('manga_key',repeat('f',64),'source_id',123,'source_manga_url','/history-only','title_snapshot','History only','last_read_at',now(),'deleted_at',null),0);
 if exists(select 1 from public.cloud_library_entries where manga_key=repeat('f',64)) then raise exception 'HISTORY_BECAME_LIBRARY'; end if;
end $$;
reset role;
select set_config('request.jwt.claim.sub',current_setting('mangaro.sync.b'),true);
set local role authenticated;
do $$ declare t text; n bigint; begin
 foreach t in array array['cloud_library_entries','cloud_library_collections','cloud_library_entry_collections','cloud_manga_history','cloud_chapter_progress'] loop
  execute format('select count(*) from public.%I',t) into n;
  if n<>0 then raise exception 'CROSS_OWNER_READ %',t; end if;
  execute format('update public.%I set deleted_at=now()',t);
  get diagnostics n=row_count;
  if n<>0 then raise exception 'CROSS_OWNER_UPDATE %',t; end if;
 end loop;
 perform public.cloud_apply_change('cloud_library_entries',jsonb_build_object('manga_key',repeat('a',64),'source_id',123,'source_manga_url','/own','title_snapshot','Own','deleted_at',null),0);
 perform pg_temp.denied(format('select public.cloud_apply_change(''cloud_library_entry_collections'',%L,0)',jsonb_build_object('manga_key',repeat('a',64),'collection_id',current_setting('mangaro.sync.collection'),'deleted_at',null)::text),array['23503']);
 if jsonb_array_length(public.cloud_pull_changes('cloud_manga_history',0,200))<>0 then raise exception 'PRIVATE_HISTORY'; end if;
end $$;
reset role;
do $$ begin
 if (select count(*) from public.cloud_library_entries where user_id=current_setting('mangaro.sync.a')::uuid and deleted_at is not null)<>1 then raise exception 'OWNER_DATA_CHANGED'; end if;
 if exists(select 1 from public.xp_events where user_id in(current_setting('mangaro.sync.a')::uuid,current_setting('mangaro.sync.b')::uuid)) then raise exception 'SYNC_AWARDED_XP'; end if;
end $$;
rollback;
