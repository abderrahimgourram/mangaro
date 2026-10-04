-- Read-only Community presentation. No Auth, XP, profile, sync or social-write rule changes.
begin;
create function public.community_public_profile(p_user_id uuid) returns jsonb
language sql stable security invoker set search_path = '' as $$
 select jsonb_build_object(
  'user_id',p.user_id,'display_name',p.display_name,'username',p.username,
  'avatar_path',p.avatar_path,'google_avatar_url',p.google_avatar_url,
  'cover_path',p.cover_path,'bio',p.bio,'updated_at',p.updated_at,
  'level',coalesce(l.level,1),
  'comment_count',(select count(*) from public.community_comments c where c.user_id=p.user_id),
  'rating_count',(select count(*) from public.community_ratings r where r.user_id=p.user_id)
 ) from public.profiles p
 left join public.community_author_levels(array[p_user_id]) l on l.user_id=p.user_id
 where p.user_id=p_user_id;
$$;
revoke all on function public.community_public_profile(uuid) from public,anon,authenticated;
grant execute on function public.community_public_profile(uuid) to anon,authenticated;

-- Server-wide most-liked order, still bounded to 20 rows and opaque keyset cursors.
-- Newest/reply pagination keeps using the original endpoint unchanged.
create function public.community_comments_popular_page(
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
   'id',c.id,'user_id',c.user_id,'body',c.body,'created_at',c.created_at,'updated_at',c.updated_at,'parent_comment_id',c.parent_comment_id,
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
revoke all on function public.community_comments_popular_page(text,text,text,timestamptz,uuid,uuid,integer,bigint) from public,anon,authenticated;
grant execute on function public.community_comments_popular_page(text,text,text,timestamptz,uuid,uuid,integer,bigint) to anon,authenticated;
commit;
