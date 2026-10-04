begin;
create table public.community_reply_notifications (
 id uuid primary key default gen_random_uuid(),
 recipient_user_id uuid not null references auth.users(id) on delete cascade,
 actor_user_id uuid not null references auth.users(id) on delete cascade,
 comment_id uuid not null references public.community_comments(id) on delete cascade,
 reply_id uuid not null unique references public.community_comments(id) on delete cascade,
 created_at timestamptz not null default now(), read_at timestamptz,
 check(recipient_user_id <> actor_user_id)
);
alter table public.community_reply_notifications enable row level security;
revoke all on public.community_reply_notifications from public,anon,authenticated;
grant select on public.community_reply_notifications to authenticated;
grant update(read_at) on public.community_reply_notifications to authenticated;
create policy reply_inbox_read on public.community_reply_notifications for select to authenticated
 using(recipient_user_id=(select auth.uid()));
create policy reply_inbox_read_state on public.community_reply_notifications for update to authenticated
 using(recipient_user_id=(select auth.uid())) with check(recipient_user_id=(select auth.uid()));
create index reply_inbox_cursor on public.community_reply_notifications(recipient_user_id,created_at desc,id desc);
create index reply_inbox_unread on public.community_reply_notifications(recipient_user_id) where read_at is null;
-- Trigger-only elevated insert: recipient and actor are derived from verified comment records.
create function public.notify_community_reply() returns trigger language plpgsql security definer set search_path='' as $$
declare recipient uuid;
begin
 if new.parent_comment_id is null then return new; end if;
 select user_id into recipient from public.community_comments where id=new.parent_comment_id;
 if recipient is not null and recipient<>new.user_id then
  insert into public.community_reply_notifications(recipient_user_id,actor_user_id,comment_id,reply_id)
  values(recipient,new.user_id,new.parent_comment_id,new.id) on conflict(reply_id) do nothing;
 end if;
 return new;
end $$;
revoke execute on function public.notify_community_reply() from public,anon,authenticated;
create trigger community_reply_inbox after insert on public.community_comments for each row execute function public.notify_community_reply();
create function public.reply_inbox_read_timestamp() returns trigger language plpgsql security invoker set search_path='' as $$
begin
 -- A client can mark read, never fabricate timestamps or mark it unread again.
 new.read_at:=coalesce(old.read_at,now()); return new;
end $$;
revoke execute on function public.reply_inbox_read_timestamp() from public,anon,authenticated;
create trigger reply_inbox_read_timestamp before update on public.community_reply_notifications for each row execute function public.reply_inbox_read_timestamp();
create function public.reply_inbox_unread_count() returns bigint language sql stable security invoker set search_path='' as $$
 select count(*) from public.community_reply_notifications where read_at is null;
$$;
revoke execute on function public.reply_inbox_unread_count() from public,anon;
grant execute on function public.reply_inbox_unread_count() to authenticated;
create function public.reply_inbox_page(p_before_created timestamptz default null,p_before_id uuid default null)
 returns jsonb language plpgsql stable security invoker set search_path='' as $$
declare result jsonb;
begin
 if (p_before_created is null)<>(p_before_id is null) then raise exception 'Invalid cursor' using errcode='22023'; end if;
 with candidates as (
 select n.* from public.community_reply_notifications n
 where p_before_created is null or (n.created_at,n.id)<(p_before_created,p_before_id)
 order by n.created_at desc,n.id desc limit 21
 ), page as(select * from candidates order by created_at desc,id desc limit 20), payload as (
 select n.created_at,n.id,jsonb_build_object(
 'id',n.id,'created_at',n.created_at,'read_at',n.read_at,'comment_id',n.comment_id,'reply_id',n.reply_id,
 'target_type',c.target_type,'manga_key',c.manga_key,'chapter_key',c.chapter_key,
 'actor_id',p.user_id,'display_name',p.display_name,'username',p.username,'avatar_path',p.avatar_path,
 'google_avatar_url',p.google_avatar_url,'author_updated_at',p.updated_at,
 'spoiler',r.spoiler,'preview',case when r.spoiler then null else left(r.body,140) end
 ) item from page n join public.community_comments c on c.id=n.comment_id
 join public.community_comments r on r.id=n.reply_id left join public.profiles p on p.user_id=n.actor_user_id
 ) select jsonb_build_object('items',coalesce((select jsonb_agg(item order by created_at desc,id desc) from payload),'[]'::jsonb),
 'has_more',(select count(*)>20 from candidates)) into result;
 return result;
end $$;
revoke execute on function public.reply_inbox_page(timestamptz,uuid) from public,anon;
grant execute on function public.reply_inbox_page(timestamptz,uuid) to authenticated;
-- Same public comment fields as existing pages; exact ID lets an inbox open an older thread.
create function public.community_comment_context(p_id uuid,p_manga_key text,p_chapter_key text default null) returns jsonb language sql stable security invoker set search_path='' as $$
 select jsonb_build_object('id',c.id,'user_id',c.user_id,'body',c.body,'spoiler',c.spoiler,
 'created_at',c.created_at,'updated_at',c.updated_at,'parent_comment_id',c.parent_comment_id,
 'display_name',p.display_name,'username',p.username,'avatar_path',p.avatar_path,'google_avatar_url',p.google_avatar_url,'author_updated_at',p.updated_at,
 'level',coalesce(l.level,1),'like_count',(select count(*) from public.community_comment_likes where comment_id=c.id),
 'reply_count',(select count(*) from public.community_comments where parent_comment_id=c.id),
 'liked_by_me',exists(select 1 from public.community_comment_likes where comment_id=c.id and user_id=(select auth.uid())))
 from public.community_comments c left join public.profiles p on p.user_id=c.user_id
 left join public.community_author_levels(array[c.user_id]) l on l.user_id=c.user_id where c.id=p_id and c.manga_key=p_manga_key and c.chapter_key is not distinct from p_chapter_key;
$$;
revoke execute on function public.community_comment_context(uuid,text,text) from public;
grant execute on function public.community_comment_context(uuid,text,text) to anon,authenticated;
commit;
