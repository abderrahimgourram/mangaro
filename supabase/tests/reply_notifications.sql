-- Rollback-only private-inbox, insert-abuse and spoiler-preview verification.
begin;
select set_config('mangaro.inbox.a',gen_random_uuid()::text,true),set_config('mangaro.inbox.b',gen_random_uuid()::text,true);
select set_config('mangaro.inbox.key',encode(gen_random_bytes(32),'hex'),true);
insert into auth.users(id,raw_app_meta_data,raw_user_meta_data)
select current_setting(k)::uuid,'{"provider":"google"}'::jsonb,'{"full_name":"Inbox test"}'::jsonb
from unnest(array['mangaro.inbox.a','mangaro.inbox.b']) k;
update public.profiles set username='test_'||substr(replace(user_id::text,'-',''),1,16)
where user_id in(current_setting('mangaro.inbox.a')::uuid,current_setting('mangaro.inbox.b')::uuid);
create function pg_temp.expect_inbox_denied(statement text) returns void language plpgsql as $$
begin begin execute statement; exception when insufficient_privilege then return; end; raise exception 'Expected permission denial'; end $$;
select set_config('request.jwt.claim.sub',current_setting('mangaro.inbox.a'),true);
set local role authenticated;
do $$ declare parent uuid; begin
 insert into public.community_comments(target_type,manga_key,user_id,body)
 values('manga',current_setting('mangaro.inbox.key'),auth.uid(),'parent') returning id into parent;
 perform set_config('mangaro.inbox.parent',parent::text,true);
 insert into public.community_comments(target_type,manga_key,user_id,parent_comment_id,body)
 values('manga',current_setting('mangaro.inbox.key'),auth.uid(),parent,'self');
 if public.reply_inbox_unread_count()<>0 then raise exception 'Self reply notified'; end if;
end $$;
reset role;
select set_config('request.jwt.claim.sub',current_setting('mangaro.inbox.b'),true);
set local role authenticated;
do $$ begin
 insert into public.community_comments(target_type,manga_key,user_id,parent_comment_id,body,spoiler)
 values('manga',current_setting('mangaro.inbox.key'),auth.uid(),current_setting('mangaro.inbox.parent')::uuid,'SECRET SPOILER',true);
 for i in 1..21 loop
  insert into public.community_comments(target_type,manga_key,user_id,parent_comment_id,body)
  values('manga',current_setting('mangaro.inbox.key'),auth.uid(),current_setting('mangaro.inbox.parent')::uuid,'reply '||i);
 end loop;
 if public.reply_inbox_unread_count()<>0 or exists(select 1 from public.community_reply_notifications) then raise exception 'Cross-user read'; end if;
 perform pg_temp.expect_inbox_denied('insert into public.community_reply_notifications(recipient_user_id,actor_user_id,comment_id,reply_id) values(gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),gen_random_uuid())');
 perform pg_temp.expect_inbox_denied('select public.notify_community_reply()');
 update public.community_reply_notifications set read_at=now();
end $$;
reset role;
select set_config('request.jwt.claim.sub',current_setting('mangaro.inbox.a'),true);
set local role authenticated;
do $$ declare payload jsonb; next_page jsonb; notice uuid; begin
 if public.reply_inbox_unread_count()<>22 then raise exception 'Unread count incorrect'; end if;
 payload:=public.reply_inbox_page();
 if jsonb_array_length(payload->'items')<>20 or not (payload->>'has_more')::boolean then raise exception 'Unbounded page'; end if;
 next_page:=public.reply_inbox_page((payload->'items'->19->>'created_at')::timestamptz,(payload->'items'->19->>'id')::uuid);
 if jsonb_array_length(next_page->'items')<>2 or exists(select 1 from jsonb_array_elements(payload->'items') a join jsonb_array_elements(next_page->'items') b on a->>'id'=b->>'id') then raise exception 'Pagination duplicates'; end if;
 if (payload::text||next_page::text) like '%SECRET SPOILER%' or not exists(select 1 from jsonb_array_elements((payload->'items')||(next_page->'items')) i where (i->>'spoiler')::boolean and i->>'preview' is null) then raise exception 'Spoiler leak'; end if;
 if payload::text ~ '"(email|source_id|source_url|access_token|total_xp|raw_user_meta_data)"' then raise exception 'Privacy leak'; end if;
 notice:=(payload->'items'->0->>'id')::uuid;
 update public.community_reply_notifications set read_at='2000-01-01' where id=notice;
 if public.reply_inbox_unread_count()<>21 or (select read_at<'2026-01-01' from public.community_reply_notifications where id=notice) then raise exception 'Read timestamp spoof'; end if;
 update public.community_reply_notifications set read_at=null where id=notice;
 if public.reply_inbox_unread_count()<>21 then raise exception 'Read state reset'; end if;
 update public.community_reply_notifications set read_at=now();
 if public.reply_inbox_unread_count()<>0 then raise exception 'Mark all failed'; end if;
 perform pg_temp.expect_inbox_denied(format('update public.community_reply_notifications set recipient_user_id=%L',current_setting('mangaro.inbox.b')));
 perform pg_temp.expect_inbox_denied('delete from public.community_reply_notifications');
 if public.community_comment_context(current_setting('mangaro.inbox.parent')::uuid,repeat('0',64)) is not null then raise exception 'Wrong target accepted'; end if;
end $$;
reset role;
set local role anon;
select pg_temp.expect_inbox_denied('select * from public.community_reply_notifications');
select pg_temp.expect_inbox_denied('select public.reply_inbox_page()');
reset role;
rollback;
