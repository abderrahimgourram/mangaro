-- Non-destructive verification under real client roles. Every fixture is rolled back.
begin;
select set_config('mangaro.test.a',gen_random_uuid()::text,true),set_config('mangaro.test.b',gen_random_uuid()::text,true),set_config('mangaro.test.incomplete',gen_random_uuid()::text,true);
select set_config('mangaro.test.key',encode(gen_random_bytes(32),'hex'),true),set_config('mangaro.test.chapter',encode(gen_random_bytes(32),'hex'),true);
insert into auth.users(id,raw_app_meta_data,raw_user_meta_data)
select current_setting(k)::uuid,'{"provider":"google"}'::jsonb,'{"full_name":"Community test"}'::jsonb
from unnest(array['mangaro.test.a','mangaro.test.b','mangaro.test.incomplete']) k;
update public.profiles set username='test_'||substr(replace(user_id::text,'-',''),1,16)
 where user_id in (current_setting('mangaro.test.a')::uuid,current_setting('mangaro.test.b')::uuid);
create function pg_temp.denied(statement text,expected text[] default array['42501']) returns void language plpgsql as $$
begin
 begin execute statement; exception when others then
  if sqlstate=any(expected) then return; end if; raise;
 end;
 raise exception 'DENIAL_EXPECTED: %',statement;
end $$;
set local role anon;
do $$ begin
 perform public.community_comments_page('manga',current_setting('mangaro.test.key'));
 perform public.community_rating_summary('manga',current_setting('mangaro.test.key'));
 perform pg_temp.denied(format('insert into public.community_comments(target_type,manga_key,user_id,body) values(%L,%L,%L,%L)','manga',current_setting('mangaro.test.key'),current_setting('mangaro.test.a'),'blocked'));
 perform pg_temp.denied(format('select public.community_set_rating(%L,%L,null,5::smallint)','manga',current_setting('mangaro.test.key')));
 perform pg_temp.denied('select public.community_like_comment(gen_random_uuid(),true)');
 perform pg_temp.denied('select public.community_report_comment(gen_random_uuid())');
 perform pg_temp.denied('select * from public.community_comment_reports');
end $$;
reset role;
select set_config('request.jwt.claim.sub',current_setting('mangaro.test.a'),true),set_config('request.jwt.claim.role','authenticated',true);
set local role authenticated;
do $$ declare comment_id uuid; reply_id uuid; n integer; invalid text; summary jsonb; page jsonb; more jsonb; begin
 insert into public.community_comments(target_type,manga_key,user_id,body)
 values('manga',current_setting('mangaro.test.key'),auth.uid(),'  valid comment  ') returning id into comment_id;
 perform set_config('mangaro.test.comment',comment_id::text,true);
 if not exists(select 1 from public.community_comments where id=comment_id and body='valid comment') then raise exception 'TRIM_FAILED'; end if;
 insert into public.community_comments(target_type,manga_key,user_id,parent_comment_id,body)
 values('manga',current_setting('mangaro.test.key'),auth.uid(),comment_id,'reply') returning id into reply_id;
 perform set_config('mangaro.test.reply',reply_id::text,true);
 perform pg_temp.denied(format('insert into public.community_comments(target_type,manga_key,user_id,parent_comment_id,body) values(%L,%L,%L,%L,%L)','manga',current_setting('mangaro.test.key'),auth.uid(),reply_id,'nested'),array['23514']);
 perform pg_temp.denied(format('insert into public.community_comments(target_type,manga_key,chapter_key,user_id,parent_comment_id,body) values(%L,%L,%L,%L,%L,%L)','chapter',current_setting('mangaro.test.key'),current_setting('mangaro.test.chapter'),auth.uid(),comment_id,'wrong target'),array['23514']);
 perform pg_temp.denied(format('insert into public.community_comments(target_type,manga_key,user_id,body) values(%L,%L,%L,%L)','manga',current_setting('mangaro.test.key'),current_setting('mangaro.test.b'),'spoof'));
 foreach invalid in array array['',E' \n\t ',repeat('a',2001)] loop
  perform pg_temp.denied(format('insert into public.community_comments(target_type,manga_key,user_id,body) values(%L,%L,%L,%L)','manga',current_setting('mangaro.test.key'),auth.uid(),invalid),array['23514']);
 end loop;
 perform pg_temp.denied(format('insert into public.community_comments(target_type,manga_key,user_id,body) values(%L,%L,%L,%L)','chapter',current_setting('mangaro.test.key'),auth.uid(),'bad shape'),array['23514']);
 perform pg_temp.denied(format('update public.community_comments set user_id=%L where id=%L',current_setting('mangaro.test.b'),comment_id));
 perform pg_temp.denied(format('update public.community_comments set created_at=now() where id=%L',comment_id));
 update public.community_comments set body='edited' where id=comment_id;
 if not exists(select 1 from public.community_comments where id=comment_id and updated_at>created_at and body='edited') then raise exception 'EDIT_FAILED'; end if;
 perform public.community_like_comment(comment_id,true); perform public.community_like_comment(comment_id,true);
 if (select count(*) from public.community_comment_likes where community_comment_likes.comment_id=current_setting('mangaro.test.comment')::uuid and user_id=auth.uid())>1 then raise exception 'DUPLICATE_LIKE'; end if;
 perform pg_temp.denied(format('insert into public.community_comment_likes(comment_id,user_id) values(%L,%L)',comment_id,auth.uid()),array['23505']);
 perform public.community_like_comment(comment_id,false);
 perform public.community_like_comment(comment_id,true);
 perform public.community_report_comment(comment_id); perform public.community_report_comment(comment_id);
 perform pg_temp.denied(format('insert into public.community_comment_reports(comment_id,reporter_id) values(%L,%L)',comment_id,auth.uid()),array['23505']);
 perform pg_temp.denied('select * from public.community_comment_reports');
 perform public.community_set_rating('manga',current_setting('mangaro.test.key'),null,3::smallint);
 perform public.community_set_rating('manga',current_setting('mangaro.test.key'),null,5::smallint);
 summary:=public.community_rating_summary('manga',current_setting('mangaro.test.key'));
 if summary->>'count'<>'1' or summary->>'current_user_rating'<>'5' then raise exception 'RATING_UPSERT_FAILED'; end if;
 perform pg_temp.denied(format('insert into public.community_ratings(target_type,manga_key,user_id,rating) values(%L,%L,%L,1)','manga',current_setting('mangaro.test.key'),auth.uid()),array['23505']);
 perform pg_temp.denied(format('select public.community_set_rating(%L,%L,null,6::smallint)','manga',current_setting('mangaro.test.key')),array['23514']);
 perform pg_temp.denied(format('select public.community_set_rating(%L,%L,null,1::smallint)','chapter',current_setting('mangaro.test.key')),array['23514']);
 perform public.community_set_rating('chapter',current_setting('mangaro.test.key'),current_setting('mangaro.test.chapter'),4::smallint);
 perform public.community_set_rating('chapter',current_setting('mangaro.test.key'),current_setting('mangaro.test.chapter'),2::smallint);
 if public.community_rating_summary('chapter',current_setting('mangaro.test.key'),current_setting('mangaro.test.chapter'))->>'count'<>'1' then raise exception 'CHAPTER_RATING_DUPLICATE'; end if;
 -- More than one page, with equal timestamps, validates the UUID tie-breaker.
 insert into public.community_comments(target_type,manga_key,user_id,body)
 select 'manga',current_setting('mangaro.test.key'),auth.uid(),'page fixture '||x from generate_series(1,22) x;
 page:=public.community_comments_page('manga',current_setting('mangaro.test.key'));
 if jsonb_array_length(page->'items')<>20 or not (page->>'has_more')::boolean then raise exception 'PAGE_LIMIT_FAILED'; end if;
 more:=public.community_comments_page('manga',current_setting('mangaro.test.key'),null,(page->'next_cursor'->>'created_at')::timestamptz,(page->'next_cursor'->>'id')::uuid);
 if jsonb_array_length(more->'items')<>3 or (more->>'has_more')::boolean then raise exception 'CURSOR_FAILED'; end if;
 if exists(select 1 from jsonb_array_elements(page->'items') a join jsonb_array_elements(more->'items') b on a->>'id'=b->>'id') then raise exception 'CURSOR_REPEATED'; end if;
 if jsonb_array_length(public.community_comments_page('manga',current_setting('mangaro.test.key'),null,null,null,comment_id)->'items')<>1 then raise exception 'REPLY_PAGE_FAILED'; end if;
 if page::text ~ '"(email|access_token|refresh_token|raw_user_meta_data|source_name|source_url)"' then raise exception 'PRIVATE_PAYLOAD'; end if;
end $$;
reset role;
do $$ begin
 if (select count(*) from public.community_comment_reports where comment_id=current_setting('mangaro.test.comment')::uuid)<>1 then raise exception 'REPORT_DEDUP_FAILED'; end if;
end $$;
select set_config('request.jwt.claim.sub',current_setting('mangaro.test.b'),true);
set local role authenticated;
do $$ declare n integer; cid uuid:=current_setting('mangaro.test.comment')::uuid; begin
 update public.community_comments set body='intruder' where id=cid; get diagnostics n=row_count; if n<>0 then raise exception 'NONOWNER_EDIT'; end if;
 delete from public.community_comments where id=cid; get diagnostics n=row_count; if n<>0 then raise exception 'NONOWNER_DELETE'; end if;
 delete from public.community_comment_likes where comment_id=cid and user_id=current_setting('mangaro.test.a')::uuid; get diagnostics n=row_count; if n<>0 then raise exception 'NONOWNER_UNLIKE'; end if;
 update public.community_ratings set rating=1 where user_id=current_setting('mangaro.test.a')::uuid; get diagnostics n=row_count; if n<>0 then raise exception 'NONOWNER_RATING'; end if;
 perform pg_temp.denied(format('insert into public.community_comment_likes(comment_id,user_id) values(%L,%L)',cid,current_setting('mangaro.test.a')));
 perform pg_temp.denied(format('insert into public.community_comment_reports(comment_id,reporter_id) values(%L,%L)',cid,current_setting('mangaro.test.a')));
 perform pg_temp.denied(format('insert into public.community_ratings(target_type,manga_key,user_id,rating) values(%L,%L,%L,1)','manga',current_setting('mangaro.test.key'),current_setting('mangaro.test.a')));
 perform public.community_set_rating('manga',current_setting('mangaro.test.key'),null,3::smallint);
end $$;
reset role;
select set_config('request.jwt.claim.sub',current_setting('mangaro.test.incomplete'),true);
set local role authenticated;
do $$ begin
 perform pg_temp.denied(format('insert into public.community_comments(target_type,manga_key,user_id,body) values(%L,%L,%L,%L)','manga',current_setting('mangaro.test.key'),auth.uid(),'incomplete profile'));
 perform pg_temp.denied(format('select public.community_set_rating(%L,%L,null,5::smallint)','manga',current_setting('mangaro.test.key')));
 perform pg_temp.denied(format('select public.community_like_comment(%L,true)',current_setting('mangaro.test.comment')));
 perform pg_temp.denied(format('select public.community_report_comment(%L)',current_setting('mangaro.test.comment')));
end $$;
reset role;
select set_config('request.jwt.claim.sub','',true),set_config('request.jwt.claim.role','anon',true);
set local role anon;
do $$ declare s jsonb; begin
 s:=public.community_rating_summary('manga',current_setting('mangaro.test.key'));
 if s->>'count'<>'2' or (s->>'average')::numeric<>4 or s->>'current_user_rating' is not null then raise exception 'GUEST_SUMMARY'; end if;
 if jsonb_array_length(public.community_comments_page('manga',current_setting('mangaro.test.key'))->'items')<>20 then raise exception 'GUEST_READ'; end if;
end $$;
reset role;
select set_config('request.jwt.claim.sub',current_setting('mangaro.test.a'),true);
set local role authenticated;
delete from public.community_comments where id=current_setting('mangaro.test.comment')::uuid;
do $$ begin
 if exists(select 1 from public.community_comments where id=current_setting('mangaro.test.reply')::uuid) then raise exception 'REPLY_DELETE_CASCADE'; end if;
end $$;
reset role;
do $$ begin
 if exists(select 1 from public.community_comment_reports where comment_id=current_setting('mangaro.test.comment')::uuid) then raise exception 'REPORT_DELETE_CASCADE'; end if;
 if exists(select 1 from pg_class where oid in ('public.community_comments'::regclass,'public.community_comment_likes'::regclass,'public.community_ratings'::regclass,'public.community_comment_reports'::regclass) and not relrowsecurity) then raise exception 'RLS_DISABLED'; end if;
 if has_function_privilege('anon','public.rls_auto_enable()','execute') or has_function_privilege('authenticated','public.rls_auto_enable()','execute') then raise exception 'AUTO_RLS_HELPER_EXPOSED'; end if;
 if not exists(select 1 from pg_event_trigger where evtname='ensure_rls' and evtenabled<>'D') then raise exception 'AUTO_RLS_TRIGGER_DISABLED'; end if;
end $$;
rollback;
select 'PASS: guest reads/write denials, complete-profile writes, ownership/immutability, shallow same-target replies, validation, rating/like/report deduplication, keyset pagination, privacy, cascade, explicit RLS, platform hardening; all fixtures rolled back' result;
