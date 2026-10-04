begin;
-- Explicit author-controlled metadata; existing comments remain ordinary comments.
alter table public.community_comments add column spoiler boolean not null default false;
grant insert(spoiler), update(spoiler) on public.community_comments to authenticated;
-- Keep the same trigger-only validator, identity restrictions, RLS and bounded invoker reads.
create or replace function public.community_validate_comment() returns trigger
language plpgsql security invoker set search_path='' as $$
declare parent public.community_comments;
begin
 if tg_op='UPDATE' then
  if row(new.id,new.user_id,new.target_type,new.manga_key,new.chapter_key,new.parent_comment_id,new.created_at)
    is distinct from row(old.id,old.user_id,old.target_type,old.manga_key,old.chapter_key,old.parent_comment_id,old.created_at) then
   raise exception 'Immutable comment identity' using errcode='42501';
  end if;
  -- Ensure edited state reflects a real change, including within one transaction.
  if new.body is distinct from old.body or new.spoiler is distinct from old.spoiler then new.updated_at := greatest(clock_timestamp(),old.updated_at+interval '1 microsecond');
  else new.updated_at := old.updated_at; end if;
 end if;
 new.body := regexp_replace(new.body,'^[[:space:]]+|[[:space:]]+$','','g');
 if new.parent_comment_id is not null then
  select * into parent from public.community_comments where id=new.parent_comment_id;
  if not found or parent.parent_comment_id is not null or parent.target_type<>new.target_type or parent.manga_key<>new.manga_key
     or parent.chapter_key is distinct from new.chapter_key then
   raise exception 'Reply parent/target invalid' using errcode='23514';
  end if;
 end if;
 return new;
end;
$$;

create or replace function public.community_comments_page(
 p_target_type text,p_manga_key text,p_chapter_key text default null,
 p_before_created timestamptz default null,p_before_id uuid default null,
 p_parent_id uuid default null,p_limit integer default 20) returns jsonb
language plpgsql stable security invoker set search_path='' as $$
declare result jsonb;
begin
 if p_limit is null or p_limit not between 1 and 20 or (p_before_created is null) <> (p_before_id is null) then
  raise exception 'Invalid page/cursor' using errcode='22023';
 end if;
 with candidates as (
  select c.* from public.community_comments c
  where c.target_type=p_target_type and c.manga_key=p_manga_key and c.chapter_key is not distinct from p_chapter_key
    and c.parent_comment_id is not distinct from p_parent_id
    and (p_before_created is null or (c.created_at,c.id)<(p_before_created,p_before_id))
  order by c.created_at desc,c.id desc limit p_limit+1
 ), page as (select * from candidates order by created_at desc,id desc limit p_limit),
 payload as (
  select c.created_at,c.id,jsonb_build_object(
   'id',c.id,'user_id',c.user_id,'body',c.body,'spoiler',c.spoiler,'created_at',c.created_at,'updated_at',c.updated_at,'parent_comment_id',c.parent_comment_id,
   'display_name',p.display_name,'username',p.username,'avatar_path',p.avatar_path,'google_avatar_url',p.google_avatar_url,'author_updated_at',p.updated_at,
   'level',coalesce(progression.level,1),'like_count',(select count(*) from public.community_comment_likes l where l.comment_id=c.id),
   'reply_count',(select count(*) from public.community_comments r where r.parent_comment_id=c.id),
   'liked_by_me',exists(select 1 from public.community_comment_likes l where l.comment_id=c.id and l.user_id=(select auth.uid()))
  ) item from page c left join public.profiles p on p.user_id=c.user_id
   left join public.community_author_levels(array(select user_id from page)) progression on progression.user_id=c.user_id
 )
 select jsonb_build_object(
  'items',coalesce((select jsonb_agg(item order by created_at desc,id desc) from payload),'[]'::jsonb),
  'has_more',(select count(*)>p_limit from candidates),
  'next_cursor',case when (select count(*)>p_limit from candidates) then
    (select jsonb_build_object('created_at',created_at,'id',id) from page order by created_at asc,id asc limit 1) else null end,
  'comment_count',(select count(*) from public.community_comments where target_type=p_target_type and manga_key=p_manga_key and chapter_key is not distinct from p_chapter_key)
 ) into result;
 return result;
end;
$$;

create or replace function public.community_comments_popular_page(
 p_target_type text,p_manga_key text,p_chapter_key text default null,
 p_before_created timestamptz default null,p_before_id uuid default null,
 p_parent_id uuid default null,p_limit integer default 20,p_before_likes bigint default null
) returns jsonb language plpgsql stable security invoker set search_path = '' as $$
declare result jsonb;
begin
 if p_limit is null or p_limit not between 1 and 20 or p_parent_id is not null
  or (p_before_created is null)<>(p_before_id is null)
  or (p_before_created is null)<>(p_before_likes is null)
  or p_before_likes<0 then raise exception 'Invalid page/cursor' using errcode='22023'; end if;
 with scored as (
  select c.*,(select count(*) from public.community_comment_likes l where l.comment_id=c.id) like_count
  from public.community_comments c where c.target_type=p_target_type and c.manga_key=p_manga_key
   and c.chapter_key is not distinct from p_chapter_key and c.parent_comment_id is null
 ), candidates as (
  select * from scored where p_before_created is null or (like_count,created_at,id)<(p_before_likes,p_before_created,p_before_id)
  order by like_count desc,created_at desc,id desc limit p_limit+1
 ), page as (select * from candidates order by like_count desc,created_at desc,id desc limit p_limit),
 payload as (
  select c.like_count,c.created_at,c.id,jsonb_build_object(
   'id',c.id,'user_id',c.user_id,'body',c.body,'spoiler',c.spoiler,'created_at',c.created_at,'updated_at',c.updated_at,'parent_comment_id',c.parent_comment_id,
   'display_name',p.display_name,'username',p.username,'avatar_path',p.avatar_path,'google_avatar_url',p.google_avatar_url,'author_updated_at',p.updated_at,
   'level',coalesce(progression.level,1),'like_count',c.like_count,
   'reply_count',(select count(*) from public.community_comments r where r.parent_comment_id=c.id),
   'liked_by_me',exists(select 1 from public.community_comment_likes l where l.comment_id=c.id and l.user_id=(select auth.uid()))
  ) item from page c left join public.profiles p on p.user_id=c.user_id
  left join public.community_author_levels(array(select user_id from page)) progression on progression.user_id=c.user_id
 ) select jsonb_build_object(
  'items',coalesce((select jsonb_agg(item order by like_count desc,created_at desc,id desc) from payload),'[]'::jsonb),
  'has_more',(select count(*)>p_limit from candidates),
  'next_cursor',case when (select count(*)>p_limit from candidates) then
   (select jsonb_build_object('created_at',created_at,'id',id,'like_count',like_count) from page order by like_count,created_at,id limit 1) else null end,
  'comment_count',(select count(*) from public.community_comments where target_type=p_target_type and manga_key=p_manga_key and chapter_key is not distinct from p_chapter_key)
 ) into result;
 return result;
end;
$$;
commit;
