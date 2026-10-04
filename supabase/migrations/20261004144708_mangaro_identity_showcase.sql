begin;
-- Existing canonical unique index is valid in production; retain its race-safe authority.
do $$ begin
 if not exists(select 1 from pg_index where indexrelid='public.profiles_username_lower_unique'::regclass and indisunique and indisvalid and indisready) then raise exception 'Username uniqueness unavailable'; end if;
 if exists(select 1 from public.profiles where username is not null group by lower(btrim(username)) having count(*)>1) then raise exception 'Resolve duplicate usernames before deployment'; end if;
end $$;
create table public.profile_roles(user_id uuid primary key references auth.users(id) on delete cascade,
 role text not null check(role in ('user','developer')));
alter table public.profile_roles enable row level security;
revoke all on public.profile_roles from public,anon,authenticated;
grant select on public.profile_roles to anon,authenticated;
create policy profile_roles_public on public.profile_roles for select to anon,authenticated using(true);
-- Existing owner independently confirmed by the user. Never derive authorization from a handle.
do $$ begin
 if not exists(select 1 from public.profiles where user_id='ad5f6ca4-dec3-466e-a276-676284faab26' and username='jalem') then raise exception 'Confirmed owner identity changed; stop'; end if;
end $$;
insert into public.profile_roles values('ad5f6ca4-dec3-466e-a276-676284faab26','developer');
create table mangaro_private.reserved_usernames(username text primary key,allowed_user_id uuid references auth.users(id) on delete set null);
alter table mangaro_private.reserved_usernames enable row level security;
revoke all on mangaro_private.reserved_usernames from public,anon,authenticated;
insert into mangaro_private.reserved_usernames(username) values('admin'),('administrator'),('moderator'),('support'),('mangaro'),('system');
insert into mangaro_private.reserved_usernames values('jalem','ad5f6ca4-dec3-466e-a276-676284faab26');
create function mangaro_private.username_allowed(p_name text,p_user uuid) returns boolean
language sql stable security definer set search_path='' as $$
 select not exists(select 1 from mangaro_private.reserved_usernames where username=p_name and allowed_user_id is distinct from p_user);
$$;
revoke all on function mangaro_private.username_allowed(text,uuid) from public,anon,authenticated;
grant execute on function mangaro_private.username_allowed(text,uuid) to authenticated;
create function public.canonical_username(p_name text) returns text language sql immutable security invoker set search_path='' as $$
 select nullif(lower(regexp_replace(p_name,'^[[:space:]]+|[[:space:]]+$','','g')),'');
$$;
revoke all on function public.canonical_username(text) from public,anon;
grant execute on function public.canonical_username(text) to authenticated;
create function public.profile_username_rules() returns trigger language plpgsql security invoker set search_path='' as $$
begin
 new.username:=public.canonical_username(new.username);
 if new.username is not null and (tg_op='INSERT' or new.username is distinct from old.username)
  and not mangaro_private.username_allowed(new.username,new.user_id) then raise exception 'Username unavailable' using errcode='23505'; end if;
 return new;
end $$;
revoke all on function public.profile_username_rules() from public,anon,authenticated;
create trigger profiles_identity_rules before insert or update on public.profiles for each row execute function public.profile_username_rules();
create function public.username_available(p_username text) returns boolean language sql stable security invoker set search_path='' as $$
 select public.canonical_username(p_username) ~ '^[a-z0-9_]{3,24}$'
 and mangaro_private.username_allowed(public.canonical_username(p_username),(select auth.uid()))
 and not exists(select 1 from public.profiles where lower(username)=public.canonical_username(p_username) and user_id<>(select auth.uid()));
$$;
revoke all on function public.username_available(text) from public,anon;
grant execute on function public.username_available(text) to authenticated;
-- Staff max-level presentation is separate from trusted XP and never changes user_progression.
create or replace function mangaro_private.community_author_levels(p_user_ids uuid[]) returns table(user_id uuid,level smallint)
language plpgsql stable security definer set search_path='' as $$
begin
 if cardinality(p_user_ids)>20 then raise exception 'Bounded author projection' using errcode='22023'; end if;
 return query select p.user_id,case when r.role='developer' then 30::smallint else p.level end
 from public.user_progression p left join public.profile_roles r on r.user_id=p.user_id where p.user_id=any(p_user_ids);
end;
$$;
create table public.public_showcase_settings(user_id uuid primary key references auth.users(id) on delete cascade,
 enabled boolean not null default false);
alter table public.public_showcase_settings enable row level security;
revoke all on public.public_showcase_settings from public,anon,authenticated;
grant select on public.public_showcase_settings to anon,authenticated;
create policy showcase_settings_read on public.public_showcase_settings for select to anon,authenticated using(enabled or user_id=(select auth.uid()));
create table public.public_favorites(user_id uuid not null references auth.users(id) on delete cascade,
 manga_key text not null check(manga_key~'^[0-9a-f]{64}$'), title text not null check(char_length(title) between 1 and 300),
 cover_path text,sort_order smallint not null check(sort_order between 0 and 19),primary key(user_id,manga_key),
 check(cover_path is null or cover_path=user_id::text||'/'||manga_key||'.webp'));
alter table public.public_favorites enable row level security;
revoke all on public.public_favorites from public,anon,authenticated;
grant select on public.public_favorites to anon,authenticated;
create policy favorites_read on public.public_favorites for select to anon,authenticated using(user_id=(select auth.uid()) or exists(select 1 from public.public_showcase_settings s where s.user_id=public_favorites.user_id and s.enabled));
create function mangaro_private.save_public_showcase(p_enabled boolean,p_favorites jsonb) returns void language plpgsql security definer set search_path='' as $$
declare u uuid:=auth.uid(); cap integer; item jsonb; n integer:=0;
begin
 if u is null then raise exception 'Authentication required' using errcode='42501'; end if;
 if p_enabled is null or p_favorites is null or jsonb_typeof(p_favorites)<>'array' or jsonb_array_length(p_favorites)>20 then raise exception 'Invalid showcase' using errcode='22023'; end if;
 insert into public.public_showcase_settings(user_id) values(u) on conflict do nothing;
 perform 1 from public.public_showcase_settings where user_id=u for update;
 select case when r.role='developer' or p.level>=25 then 20 when p.level>=15 then 15 when p.level>=5 then 10 else 5 end into cap
 from public.user_progression p left join public.profile_roles r on r.user_id=p.user_id where p.user_id=u;
 if cap is null or jsonb_array_length(p_favorites)>cap then raise exception 'Showcase limit reached' using errcode='22023'; end if;
 delete from public.public_favorites where user_id=u;
 for item in select * from jsonb_array_elements(p_favorites) loop
  if jsonb_typeof(item)<>'object' or exists(select 1 from jsonb_object_keys(item) k where k not in ('manga_key','title','cover_path')) then raise exception 'Private metadata forbidden' using errcode='22023'; end if;
  insert into public.public_favorites(user_id,manga_key,title,cover_path,sort_order)
  values(u,item->>'manga_key',btrim(item->>'title'),item->>'cover_path',n); n:=n+1;
 end loop;
 update public.public_showcase_settings set enabled=p_enabled where user_id=u;
end $$;
revoke all on function mangaro_private.save_public_showcase(boolean,jsonb) from public,anon;
grant execute on function mangaro_private.save_public_showcase(boolean,jsonb) to authenticated;
create function public.save_public_showcase(p_enabled boolean,p_favorites jsonb) returns void language sql security invoker set search_path='' as $$
 select mangaro_private.save_public_showcase(p_enabled,p_favorites);
$$;
revoke all on function public.save_public_showcase(boolean,jsonb) from public,anon;
grant execute on function public.save_public_showcase(boolean,jsonb) to authenticated;
create function mangaro_private.public_read_count(p_user uuid) returns bigint language sql stable security definer set search_path='' as $$
 select case when exists(select 1 from public.public_showcase_settings where user_id=p_user and enabled) then
 (select count(*) from (
  select chapter_key from public.cloud_chapter_progress where user_id=p_user and is_read and deleted_at is null
  union select c.chapter_key from public.reader_chapter_completions c where c.user_id=p_user
   and not exists(select 1 from public.cloud_chapter_progress p where p.user_id=p_user and p.chapter_key=c.chapter_key)
 ) completed) else null end;
$$;
revoke all on function mangaro_private.public_read_count(uuid) from public;
grant execute on function mangaro_private.public_read_count(uuid) to anon,authenticated;
create or replace function public.community_public_profile(p_user_id uuid) returns jsonb language sql stable security invoker set search_path='' as $$
 select jsonb_build_object('user_id',p.user_id,'display_name',p.display_name,'username',p.username,
 'avatar_path',p.avatar_path,'google_avatar_url',p.google_avatar_url,'cover_path',p.cover_path,'bio',p.bio,'updated_at',p.updated_at,
 'role',coalesce(r.role,'user'),'level',coalesce(l.level,1),
 'comment_count',(select count(*) from public.community_comments where user_id=p.user_id),
 'rating_count',(select count(*) from public.community_ratings where user_id=p.user_id),
 'chapters_read',mangaro_private.public_read_count(p.user_id),
 'favorites',coalesce((select jsonb_agg(jsonb_build_object('manga_key',f.manga_key,'title',f.title,'cover_path',f.cover_path) order by f.sort_order)
 from public.public_favorites f where f.user_id=p.user_id and exists(select 1 from public.public_showcase_settings s where s.user_id=p.user_id and s.enabled)),'[]'::jsonb))
 from public.profiles p left join public.profile_roles r on r.user_id=p.user_id
 left join public.community_author_levels(array[p_user_id]) l on l.user_id=p.user_id where p.user_id=p_user_id;
$$;
insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types)
 values('showcase-covers','showcase-covers',false,262144,array['image/webp']);
create policy showcase_cover_read on storage.objects for select to anon,authenticated using(bucket_id='showcase-covers' and
 ((storage.foldername(name))[1]=(select auth.uid())::text or exists(select 1 from public.public_showcase_settings s where s.user_id::text=(storage.foldername(name))[1] and s.enabled)));
create policy showcase_cover_insert on storage.objects for insert to authenticated with check(bucket_id='showcase-covers' and name~('^'||(select auth.uid())::text||'/[0-9a-f]{64}\.webp$'));
create policy showcase_cover_update on storage.objects for update to authenticated using(bucket_id='showcase-covers' and (storage.foldername(name))[1]=(select auth.uid())::text)
 with check(bucket_id='showcase-covers' and name~('^'||(select auth.uid())::text||'/[0-9a-f]{64}\.webp$'));
create policy showcase_cover_delete on storage.objects for delete to authenticated using(bucket_id='showcase-covers' and (storage.foldername(name))[1]=(select auth.uid())::text);
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
   'role',coalesce((select role from public.profile_roles where user_id=c.user_id),'user'),'level',coalesce(progression.level,1),'like_count',(select count(*) from public.community_comment_likes l where l.comment_id=c.id),
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
   'role',coalesce((select role from public.profile_roles where user_id=c.user_id),'user'),'level',coalesce(progression.level,1),'like_count',c.like_count,
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

create or replace function public.community_comment_context(p_id uuid,p_manga_key text,p_chapter_key text default null) returns jsonb language sql stable security invoker set search_path='' as $$
 select jsonb_build_object('id',c.id,'user_id',c.user_id,'body',c.body,'spoiler',c.spoiler,
 'created_at',c.created_at,'updated_at',c.updated_at,'parent_comment_id',c.parent_comment_id,
 'display_name',p.display_name,'username',p.username,'avatar_path',p.avatar_path,'google_avatar_url',p.google_avatar_url,'author_updated_at',p.updated_at,
 'role',coalesce((select role from public.profile_roles where user_id=c.user_id),'user'),'level',coalesce(l.level,1),'like_count',(select count(*) from public.community_comment_likes where comment_id=c.id),
 'reply_count',(select count(*) from public.community_comments where parent_comment_id=c.id),
 'liked_by_me',exists(select 1 from public.community_comment_likes where comment_id=c.id and user_id=(select auth.uid())))
 from public.community_comments c left join public.profiles p on p.user_id=c.user_id
 left join public.community_author_levels(array[c.user_id]) l on l.user_id=c.user_id where c.id=p_id and c.manga_key=p_manga_key and c.chapter_key is not distinct from p_chapter_key;
$$;
commit;
