-- Target pehsxthjetlltlsqcbfa only. Additive schema; no mappings seeded.
-- Linking editions requires separately verified editorial evidence; data reconciliation is read-only.
-- No historical social rows are merged, copied or deleted. Empty mappings preserve legacy scope.
begin;
create table if not exists public.community_works (
 work_key text primary key check(work_key ~ '^[a-f0-9]{64}$'),
 kind text not null check(kind in ('novel','manga')),
 verified_reference text not null check(char_length(verified_reference) between 1 and 1024),
 created_at timestamptz not null default now()
);
create table if not exists public.community_editions (
 edition_key text primary key check(edition_key ~ '^[a-f0-9]{64}$'),
 work_key text not null references public.community_works(work_key),
 verified_reference text not null check(char_length(verified_reference) between 1 and 1024),
 revoked_at timestamptz,
 updated_at timestamptz not null default now()
);
create index if not exists community_editions_work_idx on public.community_editions(work_key) where revoked_at is null;
alter table public.community_works enable row level security;
alter table public.community_editions enable row level security;
revoke all on public.community_works,public.community_editions from public,anon,authenticated;
grant select on public.community_works,public.community_editions to anon,authenticated;
drop policy if exists community_works_read on public.community_works;
create policy community_works_read on public.community_works for select to anon,authenticated using(true);
drop policy if exists community_editions_read on public.community_editions;
create policy community_editions_read on public.community_editions for select to anon,authenticated using(true);
create table if not exists mangaro_private.community_mapping_audit (
 id bigint generated always as identity primary key, occurred_at timestamptz not null default clock_timestamp(),
 operation text not null, old_mapping jsonb, new_mapping jsonb
);
alter table mangaro_private.community_mapping_audit enable row level security;
revoke all on mangaro_private.community_mapping_audit from public,anon,authenticated;
create or replace function mangaro_private.audit_community_mapping() returns trigger
language plpgsql security invoker set search_path='' as $$
begin
 -- Serialize privileged mapping changes; prevent chains/cycles and lost audit updates.
 perform pg_advisory_xact_lock(7341915531);
 if tg_op='DELETE' then raise exception 'Revoke mapping instead of deleting it' using errcode='42501'; end if;
 if tg_table_name='community_works' then
  if exists(select 1 from public.community_editions where edition_key=new.work_key and work_key<>new.work_key and revoked_at is null) then
   raise exception 'Canonical key is already an edition' using errcode='23514'; end if;
 elsif new.revoked_at is null and new.edition_key<>new.work_key and exists(select 1 from public.community_works where work_key=new.edition_key) then
  raise exception 'Canonical roots cannot be chained' using errcode='23514';
 end if;
 insert into mangaro_private.community_mapping_audit(operation,old_mapping,new_mapping)
 values(tg_table_name||':'||tg_op,case when tg_op='UPDATE' then to_jsonb(old) end,to_jsonb(new));
 return new;
end $$;
revoke all on function mangaro_private.audit_community_mapping() from public,anon,authenticated;
drop trigger if exists community_works_mapping_audit on public.community_works;
create trigger community_works_mapping_audit before insert or update or delete on public.community_works
 for each row execute function mangaro_private.audit_community_mapping();
drop trigger if exists community_editions_mapping_audit on public.community_editions;
create trigger community_editions_mapping_audit before insert or update or delete on public.community_editions
 for each row execute function mangaro_private.audit_community_mapping();
create or replace function public.community_work_key(p_key text) returns text language sql stable security invoker set search_path='' as $$
 select coalesce((select work_key from public.community_editions where edition_key=p_key and revoked_at is null),p_key)
$$;
revoke all on function public.community_work_key(text) from public,anon,authenticated;
grant execute on function public.community_work_key(text) to anon,authenticated;
create or replace function public.community_scope_keys(p_type text,p_key text,p_chapter text) returns text[]
language plpgsql stable security invoker set search_path='' as $$
declare root text;
begin
 if p_key is null or p_key !~ '^[a-f0-9]{64}$' or p_type not in ('manga','chapter') or p_type is null
 or (p_type='chapter')<>(p_chapter is not null) then raise exception 'Invalid target' using errcode='22023'; end if;
 if p_type='chapter' then return array[p_key]; end if;
 root:=public.community_work_key(p_key);
 return array(select distinct key from (select root as key union all select edition_key from public.community_editions where work_key=root and revoked_at is null) k);
end $$;
revoke all on function public.community_scope_keys(text,text,text) from public,anon,authenticated;
grant execute on function public.community_scope_keys(text,text,text) to anon,authenticated;
-- Read-only reconciliation: latest explicit rating wins, UUID breaks exact timestamp ties.
-- Underlying votes remain the audit record. RLS is inherited from base tables.
create or replace view public.community_effective_ratings with (security_invoker=true) as
 select distinct on (r.user_id,coalesce(m.work_key,r.manga_key))
 r.id,r.target_type,coalesce(m.work_key,r.manga_key) as manga_key,r.chapter_key,r.user_id,r.rating,r.created_at,r.updated_at
 from public.community_ratings r left join public.community_editions m on m.edition_key=r.manga_key and m.revoked_at is null
 where r.target_type='manga'
 order by r.user_id,coalesce(m.work_key,r.manga_key),r.updated_at desc,r.id desc;
revoke all on public.community_effective_ratings from public,anon,authenticated;
grant select on public.community_effective_ratings to anon,authenticated;
CREATE OR REPLACE FUNCTION public.community_comments_page(p_target_type text, p_manga_key text, p_chapter_key text DEFAULT NULL::text, p_before_created timestamp with time zone DEFAULT NULL::timestamp with time zone, p_before_id uuid DEFAULT NULL::uuid, p_parent_id uuid DEFAULT NULL::uuid, p_limit integer DEFAULT 20)
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE
 SET search_path TO ''
AS $function$
declare result jsonb;
begin
 if p_limit is null or p_limit not between 1 and 20 or (p_before_created is null) <> (p_before_id is null) then
  raise exception 'Invalid page/cursor' using errcode='22023';
 end if;
 with candidates as (
  select c.* from public.community_comments c
  where c.target_type=p_target_type and c.manga_key=any(public.community_scope_keys(p_target_type,p_manga_key,p_chapter_key)) and c.chapter_key is not distinct from p_chapter_key
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
  'comment_count',(select count(*) from public.community_comments where target_type=p_target_type and manga_key=any(public.community_scope_keys(p_target_type,p_manga_key,p_chapter_key)) and chapter_key is not distinct from p_chapter_key)
 ) into result;
 return result;
end;
$function$
;
CREATE OR REPLACE FUNCTION public.community_comments_popular_page(p_target_type text, p_manga_key text, p_chapter_key text DEFAULT NULL::text, p_before_created timestamp with time zone DEFAULT NULL::timestamp with time zone, p_before_id uuid DEFAULT NULL::uuid, p_parent_id uuid DEFAULT NULL::uuid, p_limit integer DEFAULT 20, p_before_likes bigint DEFAULT NULL::bigint)
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE
 SET search_path TO ''
AS $function$
declare result jsonb;
begin
 if p_limit is null or p_limit not between 1 and 20 or p_parent_id is not null
  or (p_before_created is null)<>(p_before_id is null)
  or (p_before_created is null)<>(p_before_likes is null)
  or p_before_likes<0 then raise exception 'Invalid page/cursor' using errcode='22023'; end if;
 with scored as (
  select c.*,(select count(*) from public.community_comment_likes l where l.comment_id=c.id) like_count
  from public.community_comments c where c.target_type=p_target_type and c.manga_key=any(public.community_scope_keys(p_target_type,p_manga_key,p_chapter_key))
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
  'comment_count',(select count(*) from public.community_comments where target_type=p_target_type and manga_key=any(public.community_scope_keys(p_target_type,p_manga_key,p_chapter_key)) and chapter_key is not distinct from p_chapter_key)
 ) into result;
 return result;
end;
$function$
;
CREATE OR REPLACE FUNCTION public.community_comment_context(p_id uuid, p_manga_key text, p_chapter_key text DEFAULT NULL::text)
 RETURNS jsonb
 LANGUAGE sql
 STABLE
 SET search_path TO ''
AS $function$
 select jsonb_build_object('id',c.id,'user_id',c.user_id,'body',c.body,'spoiler',c.spoiler,
 'created_at',c.created_at,'updated_at',c.updated_at,'parent_comment_id',c.parent_comment_id,
 'display_name',p.display_name,'username',p.username,'avatar_path',p.avatar_path,'google_avatar_url',p.google_avatar_url,'author_updated_at',p.updated_at,
 'role',coalesce((select role from public.profile_roles where user_id=c.user_id),'user'),'level',coalesce(l.level,1),'like_count',(select count(*) from public.community_comment_likes where comment_id=c.id),
 'reply_count',(select count(*) from public.community_comments where parent_comment_id=c.id),
 'liked_by_me',exists(select 1 from public.community_comment_likes where comment_id=c.id and user_id=(select auth.uid())))
 from public.community_comments c left join public.profiles p on p.user_id=c.user_id
 left join public.community_author_levels(array[c.user_id]) l on l.user_id=c.user_id where c.id=p_id and c.manga_key=any(public.community_scope_keys(case when p_chapter_key is null then 'manga' else 'chapter' end,p_manga_key,p_chapter_key)) and c.chapter_key is not distinct from p_chapter_key;
$function$
;
create or replace function public.community_rating_summary(p_target_type text,p_manga_key text,p_chapter_key text default null) returns jsonb
language plpgsql stable security invoker set search_path='' as $$
begin
 perform public.community_scope_keys(p_target_type,p_manga_key,p_chapter_key);
 if p_target_type='manga' then
  return (select jsonb_build_object('average',avg(rating)::double precision,'count',count(*),
   'current_user_rating',max(rating) filter(where user_id=(select auth.uid())))
   from (select distinct on (user_id) user_id,rating from public.community_ratings
    where target_type='manga' and manga_key=any(public.community_scope_keys('manga',p_manga_key,null))
    order by user_id,updated_at desc,id desc) effective);
 end if;
 return (select jsonb_build_object('average',avg(rating)::double precision,'count',count(*),
  'current_user_rating',max(rating) filter(where user_id=(select auth.uid())))
  from public.community_ratings where target_type='chapter' and manga_key=p_manga_key and chapter_key=p_chapter_key);
end $$;
CREATE OR REPLACE FUNCTION public.community_set_rating(p_target_type text, p_manga_key text, p_chapter_key text, p_rating smallint)
 RETURNS void
 LANGUAGE plpgsql
 SET search_path TO ''
AS $function$
begin
 perform public.community_scope_keys(p_target_type,p_manga_key,p_chapter_key);
 if p_target_type='manga' then
  p_manga_key:=public.community_work_key(p_manga_key);
  perform pg_advisory_xact_lock(hashtextextended(auth.uid()::text||p_manga_key,73419));
  insert into public.community_ratings(target_type,manga_key,chapter_key,user_id,rating)
   values(p_target_type,p_manga_key,p_chapter_key,(select auth.uid()),p_rating)
   on conflict(user_id,manga_key) where target_type='manga' do update set rating=excluded.rating;
 else
  insert into public.community_ratings(target_type,manga_key,chapter_key,user_id,rating)
   values(p_target_type,p_manga_key,p_chapter_key,(select auth.uid()),p_rating)
   on conflict(user_id,manga_key,chapter_key) where target_type='chapter' do update set rating=excluded.rating;
 end if;
end;
$function$
;
CREATE OR REPLACE FUNCTION public.community_weekly_ranking()
 RETURNS jsonb
 LANGUAGE sql
 STABLE
 SET search_path TO ''
AS $function$
 with works as (
   select manga_key,avg(rating)::double precision average,count(*) rating_count
   from public.community_effective_ratings where target_type='manga' group by manga_key
 ), parameters as (
   select coalesce((select avg(rating)::double precision from public.community_effective_ratings where target_type='manga'),3.0) mean,
          greatest(2.0,ceil(coalesce(percentile_cont(0.5) within group(order by rating_count),2.0))) confidence
   from works
 ), scored as (
   select w.*, (rating_count*average+p.confidence*p.mean)/(rating_count+p.confidence) score
   from works w cross join parameters p where rating_count>=2
 ), ranked as (
   select * from scored order by score desc,rating_count desc,average desc,manga_key limit 100
 )
 select jsonb_build_object('confidence',(select confidence from parameters),'mean',(select mean from parameters),
   'works',coalesce((select jsonb_agg(jsonb_build_object('manga_key',manga_key,'average',average,'count',rating_count,'score',score)
     order by score desc,rating_count desc,average desc,manga_key) from ranked),'[]'::jsonb));
$function$
;
-- New reply entry point resolves the stored parent scope, retaining the original identity.
-- No user_id parameter. Existing owner/profile RLS, spoiler/XP/moderation triggers remain authoritative.
create or replace function public.community_post_work_comment(p_manga_key text,p_request_id uuid,p_body text,p_parent_id uuid default null,p_spoiler boolean default false) returns void
language plpgsql security invoker set search_path='' as $$
declare key text:=p_manga_key; parent public.community_comments; existing public.community_comments; uid uuid:=auth.uid(); clean text;
begin
 if uid is null then raise exception 'Authentication required' using errcode='42501'; end if;
 perform public.community_scope_keys('manga',p_manga_key,null);
 clean:=regexp_replace(p_body,'^[[:space:]]+|[[:space:]]+$','','g');
 if p_parent_id is not null then
  select * into parent from public.community_comments where id=p_parent_id;
  if not found or parent.target_type<>'manga' or parent.parent_comment_id is not null or
    public.community_work_key(parent.manga_key)<>public.community_work_key(p_manga_key) then
   raise exception 'Unrelated reply parent' using errcode='23514'; end if;
  key:=parent.manga_key;
 end if;
 begin
  insert into public.community_comments(id,target_type,manga_key,user_id,parent_comment_id,body,spoiler)
   values(p_request_id,'manga',key,uid,p_parent_id,clean,p_spoiler);
 exception when unique_violation then
  select * into existing from public.community_comments where id=p_request_id;
  if not found or existing.user_id<>uid or existing.target_type<>'manga' or existing.manga_key<>key or
    existing.parent_comment_id is distinct from p_parent_id or existing.body is distinct from clean or existing.spoiler is distinct from p_spoiler then
   raise exception 'Request identity collision' using errcode='23505'; end if;
 end;
end $$;
revoke all on function public.community_post_work_comment(text,uuid,text,uuid,boolean) from public,anon,authenticated;
grant execute on function public.community_post_work_comment(text,uuid,text,uuid,boolean) to authenticated;
create or replace function public.community_work_capabilities() returns jsonb language sql stable security invoker set search_path='' as $$
 select jsonb_build_object('schema',1,'shared_work_community',true,'chapter_scope','edition')
$$;
revoke all on function public.community_work_capabilities() from public,anon,authenticated;
grant execute on function public.community_work_capabilities() to anon,authenticated;
commit;
