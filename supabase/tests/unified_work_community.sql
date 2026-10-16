-- LOCAL/isolated acceptance; all identities and content are synthetic fixtures, rolled back.
begin;
create function pg_temp.assert(ok boolean,label text) returns void language plpgsql as $$ begin
 if ok is distinct from true then raise exception 'FAIL: %',label; end if; raise notice 'PASS: %',label;
end $$;
create function pg_temp.denied(statement text) returns void language plpgsql as $$ begin
 begin execute statement; exception when insufficient_privilege then raise notice 'PASS: denied %',statement; return; end;
 raise exception 'Expected authorization denial: %',statement;
end $$;
select set_config('test.a',gen_random_uuid()::text,true),set_config('test.b',gen_random_uuid()::text,true),set_config('test.comment',gen_random_uuid()::text,true),set_config('test.reply',gen_random_uuid()::text,true),set_config('test.post',gen_random_uuid()::text,true);
insert into auth.users(id,raw_app_meta_data,raw_user_meta_data)
 select current_setting(k)::uuid,'{"provider":"google"}','{}' from unnest(array['test.a','test.b']) k;
update public.profiles set username='test_'||substr(replace(user_id::text,'-',''),1,16)
 where user_id in (current_setting('test.a')::uuid,current_setting('test.b')::uuid);
insert into public.community_comments(id,target_type,manga_key,user_id,body,spoiler)
 values(current_setting('test.comment')::uuid,'manga',repeat('a',64),current_setting('test.a')::uuid,'original A',true);
insert into public.community_comments(target_type,manga_key,user_id,body)
 values('manga',repeat('b',64),current_setting('test.a')::uuid,'historical B'),('manga',repeat('c',64),current_setting('test.b')::uuid,'different work');
insert into public.community_ratings(target_type,manga_key,user_id,rating,updated_at)
 values('manga',repeat('a',64),current_setting('test.a')::uuid,4,'2020-01-01Z'),('manga',repeat('b',64),current_setting('test.a')::uuid,5,'2021-01-01Z');
insert into public.community_works values(repeat('a',64),'novel','ISOLATED TEST mapping, no live identity claim',default);
insert into public.community_editions(edition_key,work_key,verified_reference) values
 (repeat('a',64),repeat('a',64),'TEST owner-confirmed pair'),(repeat('b',64),repeat('a',64),'TEST owner-confirmed pair');
select pg_temp.assert(jsonb_array_length(public.community_comments_page('manga',repeat('a',64))->'items')=2,'same verified work sees both historical feeds');
select pg_temp.assert(public.community_comments_page('manga',repeat('a',64))=public.community_comments_page('manga',repeat('b',64)),'editions share the identical ordered feed');
select pg_temp.assert(jsonb_array_length(public.community_comments_page('manga',repeat('c',64))->'items')=1,'unverified similarly named work stays separate');
select pg_temp.assert((public.community_rating_summary('manga',repeat('b',64))->>'count')::int=1 and (public.community_rating_summary('manga',repeat('b',64))->>'average')::numeric=5,'historical duplicates count once, latest trusted timestamp wins');
select set_config('request.jwt.claim.sub',current_setting('test.a'),true);set local role authenticated;
select public.community_post_work_comment(repeat('b',64),current_setting('test.reply')::uuid,'reply through B',current_setting('test.comment')::uuid,true);
select public.community_post_work_comment(repeat('b',64),current_setting('test.reply')::uuid,'reply through B',current_setting('test.comment')::uuid,true);
select pg_temp.assert((select count(*) from public.community_comments where id=current_setting('test.reply')::uuid)=1,'ambiguous retry preserves comment ID without duplicate insert');
select pg_temp.assert((select manga_key=repeat('a',64) and spoiler from public.community_comments where id=current_setting('test.reply')::uuid),'reply preserves parent scope, author and spoiler');
select pg_temp.assert(jsonb_array_length(public.community_comments_page('manga',repeat('b',64),null,null,null,current_setting('test.comment')::uuid)->'items')=1,'cross-edition reply is visible through shared scope');
select public.community_post_work_comment(repeat('b',64),current_setting('test.post')::uuid,'new B work comment');
select pg_temp.assert(public.community_comment_context(current_setting('test.post')::uuid,repeat('a',64))->>'body'='new B work comment','canonical context lookup finds original edition row');
select public.community_set_rating('manga',repeat('a',64),null,4::smallint);
select public.community_set_rating('manga',repeat('b',64),null,5::smallint);
select pg_temp.assert(public.community_rating_summary('manga',repeat('a',64))=public.community_rating_summary('manga',repeat('b',64)) and public.community_rating_summary('manga',repeat('a',64))->>'count'='1','rating update across editions retains one effective vote');
select pg_temp.denied('insert into public.community_works values(repeat(''d'',64),''novel'',''forged'',default)');
select pg_temp.denied('update public.community_editions set work_key=repeat(''c'',64)');
select pg_temp.denied('select * from mangaro_private.community_mapping_audit');
insert into public.community_comments(target_type,manga_key,chapter_key,user_id,body)
 values('chapter',repeat('a',64),repeat('1',64),auth.uid(),'chapter A'),('chapter',repeat('b',64),repeat('1',64),auth.uid(),'chapter B');
select pg_temp.assert(jsonb_array_length(public.community_comments_page('chapter',repeat('a',64),repeat('1',64))->'items')=1,'chapter discussion never follows work mapping');
reset role;select set_config('request.jwt.claim.sub',current_setting('test.b'),true);set local role authenticated;
select pg_temp.assert(public.community_rating_summary('manga',repeat('a',64))->>'current_user_rating' is null,'second user cannot inherit first user rating');
select pg_temp.denied(format('insert into public.community_ratings(target_type,manga_key,user_id,rating) values(''manga'',%L,%L,1)',repeat('a',64),current_setting('test.a')));
do $$ declare n int; begin update public.community_comments set body='spoofed' where id=current_setting('test.comment')::uuid;get diagnostics n=row_count;perform pg_temp.assert(n=0,'cross-user edit rejected by real RLS'); end $$;
select public.community_set_rating('manga',repeat('b',64),null,4::smallint);
select pg_temp.assert(public.community_rating_summary('manga',repeat('b',64))->>'count'='2','two actual users produce two effective votes');
reset role;
select pg_temp.assert((select count(*) from public.community_ratings where user_id=current_setting('test.a')::uuid)=2,'historical rating rows are preserved');
update public.community_editions set revoked_at=clock_timestamp() where edition_key=repeat('b',64);
select pg_temp.assert(jsonb_array_length(public.community_comments_page('manga',repeat('b',64))->'items')=2,'mapping correction reversibly restores original edition scope');
select pg_temp.assert((select body='original A' and spoiler and user_id=current_setting('test.a')::uuid from public.community_comments where id=current_setting('test.comment')::uuid),'mapping correction preserves historical ID, author and metadata');
select pg_temp.assert((select count(*) from mangaro_private.community_mapping_audit)>=4,'mapping changes retain privileged audit records');
select pg_temp.assert((select 'security_invoker=true'=any(reloptions) from pg_class where oid='public.community_effective_ratings'::regclass),'rating projection respects invoker RLS');

update public.community_editions set revoked_at=null where edition_key=repeat('b',64);
insert into public.community_comments(target_type,manga_key,user_id,body)
 select 'manga',repeat(case when g%2=0 then 'a' else 'b' end,64),current_setting('test.a')::uuid,'page fixture '||g from generate_series(1,25) g;
do $$ declare first_page jsonb; second_page jsonb; cursor jsonb; begin
 first_page:=public.community_comments_page('manga',repeat('a',64),null,null,null,null,20);
 cursor:=first_page->'next_cursor';
 second_page:=public.community_comments_page('manga',repeat('b',64),null,(cursor->>'created_at')::timestamptz,(cursor->>'id')::uuid,null,20);
 perform pg_temp.assert(first_page->>'has_more'='true' and jsonb_array_length(second_page->'items')>0,'shared keyset pagination continues across edition boundaries');
 perform pg_temp.assert(not exists(select 1 from jsonb_array_elements(first_page->'items') a join jsonb_array_elements(second_page->'items') b on a->>'id'=b->>'id'),'shared pages never duplicate comment IDs');
 perform pg_temp.assert(public.community_comments_popular_page('manga',repeat('a',64))=public.community_comments_popular_page('manga',repeat('b',64)),'most-liked feed uses the same canonical scope');
end $$;
set local role authenticated;
do $$ begin
 begin perform public.community_post_work_comment(repeat('c',64),gen_random_uuid(),'wrong parent',current_setting('test.comment')::uuid);
  raise exception 'Unrelated parent accepted'; exception when check_violation then raise notice 'PASS: unrelated work reply rejected'; end;
 begin perform public.community_set_rating('manga',repeat('a',64),null,6::smallint);
  raise exception 'Six stars accepted'; exception when check_violation then raise notice 'PASS: rating scale remains one to five'; end;
end $$;
reset role;
rollback;
