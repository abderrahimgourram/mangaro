begin;
-- All Phase 2 helpers are SECURITY INVOKER. No privileged read/write RPCs.
create function public.community_profile_complete() returns boolean
language sql stable security invoker set search_path = '' as $$
 select exists(select 1 from public.profiles where user_id=(select auth.uid()) and username is not null);
$$;
revoke all on function public.community_profile_complete() from public, anon, authenticated;
grant execute on function public.community_profile_complete() to authenticated;

create table public.community_comments (
 id uuid primary key default gen_random_uuid(),
 target_type text not null check(target_type in ('manga','chapter')),
 manga_key text not null check(manga_key ~ '^[a-f0-9]{64}$'),
 chapter_key text,
 user_id uuid not null references auth.users(id) on delete cascade,
 parent_comment_id uuid references public.community_comments(id) on delete cascade,
 body text not null check(body = regexp_replace(body,'^[[:space:]]+|[[:space:]]+$','','g') and char_length(body) between 1 and 2000),
 created_at timestamptz not null default now(), updated_at timestamptz not null default now(),
 constraint community_comments_target_shape check (
  (target_type='manga' and chapter_key is null) or (target_type='chapter' and chapter_key is not null and chapter_key ~ '^[a-f0-9]{64}$'))
);
create index community_comments_page_idx on public.community_comments(target_type,manga_key,chapter_key,parent_comment_id,created_at desc,id desc);
create index community_comments_user_idx on public.community_comments(user_id);
create index community_comments_parent_idx on public.community_comments(parent_comment_id) where parent_comment_id is not null;

create table public.community_comment_likes (
 comment_id uuid not null references public.community_comments(id) on delete cascade,
 user_id uuid not null references auth.users(id) on delete cascade,
 created_at timestamptz not null default now(), primary key(comment_id,user_id)
);
create index community_likes_user_idx on public.community_comment_likes(user_id);
create table public.community_ratings (
 id uuid primary key default gen_random_uuid(),
 target_type text not null check(target_type in ('manga','chapter')),
 manga_key text not null check(manga_key ~ '^[a-f0-9]{64}$'), chapter_key text,
 user_id uuid not null references auth.users(id) on delete cascade,
 rating smallint not null check(rating between 1 and 5),
 created_at timestamptz not null default now(), updated_at timestamptz not null default now(),
 constraint community_ratings_target_shape check (
  (target_type='manga' and chapter_key is null) or (target_type='chapter' and chapter_key is not null and chapter_key ~ '^[a-f0-9]{64}$'))
);
create unique index community_manga_rating_unique on public.community_ratings(user_id,manga_key) where target_type='manga';
create unique index community_chapter_rating_unique on public.community_ratings(user_id,manga_key,chapter_key) where target_type='chapter';
create index community_ratings_target_idx on public.community_ratings(target_type,manga_key,chapter_key);
create table public.community_comment_reports (
 id uuid primary key default gen_random_uuid(),
 comment_id uuid not null references public.community_comments(id) on delete cascade,
 reporter_id uuid not null references auth.users(id) on delete cascade,
 reason text check(reason is null or (reason=btrim(reason) and char_length(reason)<=500)),
 created_at timestamptz not null default now(), unique(comment_id,reporter_id)
);
create index community_reports_reporter_idx on public.community_comment_reports(reporter_id);

alter table public.community_comments enable row level security;
alter table public.community_comment_likes enable row level security;
alter table public.community_ratings enable row level security;
alter table public.community_comment_reports enable row level security;
revoke all on public.community_comments,public.community_comment_likes,public.community_ratings,public.community_comment_reports from public,anon,authenticated;
grant select on public.community_comments,public.community_comment_likes,public.community_ratings to anon,authenticated;
grant insert(target_type,manga_key,chapter_key,user_id,parent_comment_id,body),update(body) on public.community_comments to authenticated;
grant delete on public.community_comments to authenticated;
grant insert(comment_id,user_id),delete on public.community_comment_likes to authenticated;
grant insert(target_type,manga_key,chapter_key,user_id,rating),update(rating) on public.community_ratings to authenticated;
grant insert(comment_id,reporter_id,reason) on public.community_comment_reports to authenticated;
-- Reports have no client SELECT/UPDATE/DELETE privileges or public policies.
create policy community_comments_read on public.community_comments for select to anon,authenticated using(true);
create policy community_comments_insert on public.community_comments for insert to authenticated with check(user_id=(select auth.uid()) and (select public.community_profile_complete()));
create policy community_comments_update on public.community_comments for update to authenticated using(user_id=(select auth.uid()) and (select public.community_profile_complete())) with check(user_id=(select auth.uid()) and (select public.community_profile_complete()));
create policy community_comments_delete on public.community_comments for delete to authenticated using(user_id=(select auth.uid()) and (select public.community_profile_complete()));
create policy community_likes_read on public.community_comment_likes for select to anon,authenticated using(true);
create policy community_likes_insert on public.community_comment_likes for insert to authenticated with check(user_id=(select auth.uid()) and (select public.community_profile_complete()));
create policy community_likes_delete on public.community_comment_likes for delete to authenticated using(user_id=(select auth.uid()) and (select public.community_profile_complete()));
create policy community_ratings_read on public.community_ratings for select to anon,authenticated using(true);
create policy community_ratings_insert on public.community_ratings for insert to authenticated with check(user_id=(select auth.uid()) and (select public.community_profile_complete()));
create policy community_ratings_update on public.community_ratings for update to authenticated using(user_id=(select auth.uid()) and (select public.community_profile_complete())) with check(user_id=(select auth.uid()) and (select public.community_profile_complete()));
create policy community_reports_insert on public.community_comment_reports for insert to authenticated with check(reporter_id=(select auth.uid()) and (select public.community_profile_complete()));

create function public.community_validate_comment() returns trigger
language plpgsql security invoker set search_path='' as $$
declare parent public.community_comments;
begin
 if tg_op='UPDATE' then
  if row(new.id,new.user_id,new.target_type,new.manga_key,new.chapter_key,new.parent_comment_id,new.created_at)
    is distinct from row(old.id,old.user_id,old.target_type,old.manga_key,old.chapter_key,old.parent_comment_id,old.created_at) then
   raise exception 'Immutable comment identity' using errcode='42501';
  end if;
  -- Ensure edited state reflects a real change, including within one transaction.
  if new.body is distinct from old.body then new.updated_at := greatest(clock_timestamp(),old.updated_at+interval '1 microsecond');
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
revoke all on function public.community_validate_comment() from public,anon,authenticated;
create trigger community_comments_validate before insert or update on public.community_comments for each row execute function public.community_validate_comment();
create function public.community_validate_rating() returns trigger
language plpgsql security invoker set search_path='' as $$
begin
 if row(new.id,new.user_id,new.target_type,new.manga_key,new.chapter_key,new.created_at)
  is distinct from row(old.id,old.user_id,old.target_type,old.manga_key,old.chapter_key,old.created_at) then
  raise exception 'Immutable rating identity' using errcode='42501';
 end if;
 new.updated_at := clock_timestamp();
 return new;
end;
$$;
revoke all on function public.community_validate_rating() from public,anon,authenticated;
create trigger community_ratings_validate before update on public.community_ratings for each row execute function public.community_validate_rating();

-- Database computes summaries; clients never submit average/counts.
create function public.community_rating_summary(p_target_type text,p_manga_key text,p_chapter_key text default null) returns jsonb
language sql stable security invoker set search_path='' as $$
 select jsonb_build_object('average',avg(rating)::double precision,'count',count(*),
   'current_user_rating',max(rating) filter(where user_id=(select auth.uid())))
 from public.community_ratings where target_type=p_target_type and manga_key=p_manga_key and chapter_key is not distinct from p_chapter_key;
$$;
revoke all on function public.community_rating_summary(text,text,text) from public,anon,authenticated;
grant execute on function public.community_rating_summary(text,text,text) to anon,authenticated;

create function public.community_comments_page(
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
   'id',c.id,'user_id',c.user_id,'body',c.body,'created_at',c.created_at,'updated_at',c.updated_at,'parent_comment_id',c.parent_comment_id,
   'display_name',p.display_name,'username',p.username,'avatar_path',p.avatar_path,'google_avatar_url',p.google_avatar_url,'author_updated_at',p.updated_at,
   'like_count',(select count(*) from public.community_comment_likes l where l.comment_id=c.id),
   'reply_count',(select count(*) from public.community_comments r where r.parent_comment_id=c.id),
   'liked_by_me',exists(select 1 from public.community_comment_likes l where l.comment_id=c.id and l.user_id=(select auth.uid()))
  ) item from page c left join public.profiles p on p.user_id=c.user_id
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
revoke all on function public.community_comments_page(text,text,text,timestamptz,uuid,uuid,integer) from public,anon,authenticated;
grant execute on function public.community_comments_page(text,text,text,timestamptz,uuid,uuid,integer) to anon,authenticated;

create function public.community_set_rating(p_target_type text,p_manga_key text,p_chapter_key text,p_rating smallint) returns void
language plpgsql security invoker set search_path='' as $$
begin
 if p_target_type='manga' then
  insert into public.community_ratings(target_type,manga_key,chapter_key,user_id,rating)
   values(p_target_type,p_manga_key,p_chapter_key,(select auth.uid()),p_rating)
   on conflict(user_id,manga_key) where target_type='manga' do update set rating=excluded.rating;
 else
  insert into public.community_ratings(target_type,manga_key,chapter_key,user_id,rating)
   values(p_target_type,p_manga_key,p_chapter_key,(select auth.uid()),p_rating)
   on conflict(user_id,manga_key,chapter_key) where target_type='chapter' do update set rating=excluded.rating;
 end if;
end;
$$;
revoke all on function public.community_set_rating(text,text,text,smallint) from public,anon,authenticated;
grant execute on function public.community_set_rating(text,text,text,smallint) to authenticated;
create function public.community_like_comment(p_comment_id uuid,p_liked boolean) returns void
language plpgsql security invoker set search_path='' as $$
begin
 if p_liked then
  insert into public.community_comment_likes(comment_id,user_id) values(p_comment_id,(select auth.uid())) on conflict do nothing;
 else
  delete from public.community_comment_likes where comment_id=p_comment_id and user_id=(select auth.uid());
 end if;
end;
$$;
revoke all on function public.community_like_comment(uuid,boolean) from public,anon,authenticated;
grant execute on function public.community_like_comment(uuid,boolean) to authenticated;
create function public.community_report_comment(p_comment_id uuid,p_reason text default null) returns void
language sql security invoker set search_path='' as $$
 insert into public.community_comment_reports(comment_id,reporter_id,reason)
 values(p_comment_id,(select auth.uid()),nullif(btrim(p_reason),'')) on conflict(comment_id,reporter_id) do nothing;
$$;
revoke all on function public.community_report_comment(uuid,text) from public,anon,authenticated;
grant execute on function public.community_report_comment(uuid,text) to authenticated;
commit;
