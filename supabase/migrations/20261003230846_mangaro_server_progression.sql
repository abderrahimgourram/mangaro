begin;
create schema if not exists mangaro_private;
revoke all on schema mangaro_private from public,anon,authenticated;

-- One authoritative level curve: n completed levels cost 40*n + 4*n*(n-1).
create function public.progression_level(p_total_xp bigint) returns smallint
language sql immutable security invoker set search_path='' as $$
 select max(l)::smallint from generate_series(1,30) l
 where 40::bigint*(l-1)+4::bigint*(l-1)*(l-2)<=greatest(p_total_xp,0);
$$;
revoke all on function public.progression_level(bigint) from public,anon,authenticated;

create table public.user_progression (
 user_id uuid primary key references auth.users(id) on delete cascade,
 total_xp bigint not null default 0 check(total_xp>=0),
 level smallint not null default 1 check(level between 1 and 30),
 created_at timestamptz not null default now(),updated_at timestamptz not null default now(),
 constraint progression_level_consistent check(level=public.progression_level(total_xp))
);
create table public.xp_events (
 id uuid primary key default gen_random_uuid(),
 user_id uuid not null references auth.users(id) on delete cascade,
 event_type text not null check(event_type in ('comment_created','reply_created','chapter_completed')),
 source_key text not null,
 xp_amount integer not null check(xp_amount in (0,2,4,12)),
 created_at timestamptz not null default now(),revoked_at timestamptz,revoke_reason text,
 unique(user_id,event_type,source_key),
 constraint xp_reward_shape check(xp_amount=0 or (event_type='comment_created' and xp_amount=12)
  or (event_type='reply_created' and xp_amount=4) or (event_type='chapter_completed' and xp_amount=2)),
 constraint xp_key_shape check((event_type='chapter_completed' and source_key ~ '^[a-f0-9]{64}$')
  or (event_type in ('comment_created','reply_created') and source_key ~ '^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$')),
 constraint xp_revocation_shape check((revoked_at is null and revoke_reason is null) or (revoked_at is not null and revoke_reason is not null))
);
create index xp_events_daily_idx on public.xp_events(user_id,event_type,created_at) where xp_amount>0;
create table public.reader_chapter_completions (
 user_id uuid not null references auth.users(id) on delete cascade,
 manga_key text not null check(manga_key ~ '^[a-f0-9]{64}$'),
 chapter_key text not null check(chapter_key ~ '^[a-f0-9]{64}$'),
 completed_at timestamptz not null default now(),xp_awarded boolean not null default false,
 primary key(user_id,chapter_key)
);
alter table public.user_progression enable row level security;
alter table public.xp_events enable row level security;
alter table public.reader_chapter_completions enable row level security;
revoke all on public.user_progression,public.xp_events,public.reader_chapter_completions from public,anon,authenticated;
grant select on public.user_progression,public.xp_events,public.reader_chapter_completions to authenticated;
create policy progression_owner_read on public.user_progression for select to authenticated using(user_id=(select auth.uid()));
create policy xp_owner_read on public.xp_events for select to authenticated using(user_id=(select auth.uid()));
create policy completions_owner_read on public.reader_chapter_completions for select to authenticated using(user_id=(select auth.uid()));

-- Extend the existing signup path through profiles; do not replace Auth/profile triggers.
create function mangaro_private.initialize_progression() returns trigger
language plpgsql security definer set search_path='' as $$
begin
 insert into public.user_progression(user_id) values(new.user_id) on conflict do nothing;
 return new;
end;
$$;
revoke all on function mangaro_private.initialize_progression() from public,anon,authenticated;
create trigger profile_initialize_progression after insert on public.profiles
for each row execute function mangaro_private.initialize_progression();
insert into public.user_progression(user_id) select user_id from public.profiles on conflict do nothing;

-- Internal only. Caller holds the user's progression row lock before changing ledger/balance.
create function mangaro_private.change_progression(p_user_id uuid,p_delta integer) returns void
language plpgsql security definer set search_path='' as $$
begin
 update public.user_progression set total_xp=total_xp+p_delta,
  level=public.progression_level(total_xp+p_delta),updated_at=clock_timestamp() where user_id=p_user_id;
end;
$$;
revoke all on function mangaro_private.change_progression(uuid,integer) from public,anon,authenticated;
create function mangaro_private.award_event(p_user_id uuid,p_type text,p_key text) returns integer
language plpgsql security definer set search_path='' as $$
declare reward integer; cap integer; awarded integer; inserted uuid; moment timestamptz; day_start timestamptz;
begin
 -- This entry point is never executable by a client. Trusted triggers/RPC select the type/key.
 case p_type when 'comment_created' then reward:=12; cap:=5;
  when 'reply_created' then reward:=4; cap:=5;
  when 'chapter_completed' then reward:=2; cap:=50;
  else raise exception 'Invalid event' using errcode='22023'; end case;
 perform 1 from public.user_progression where user_id=p_user_id for update;
 if not found then return 0; end if;
 if exists(select 1 from public.xp_events where user_id=p_user_id and event_type=p_type and source_key=p_key) then return 0; end if;
 moment:=clock_timestamp();
 day_start:=date_trunc('day',moment at time zone 'UTC') at time zone 'UTC';
 -- Revoked positive awards STILL consume the award-day quota. Zero awards cannot be re-claimed.
 select case when count(*)<cap then reward else 0 end into awarded from public.xp_events
  where user_id=p_user_id and event_type=p_type and xp_amount>0 and created_at>=day_start and created_at<day_start+interval '24 hours';
 insert into public.xp_events(user_id,event_type,source_key,xp_amount,created_at)
  values(p_user_id,p_type,p_key,awarded,moment) on conflict do nothing returning id into inserted;
 if inserted is null then return 0; end if;
 if awarded>0 then perform mangaro_private.change_progression(p_user_id,awarded); end if;
 return awarded;
end;
$$;
revoke all on function mangaro_private.award_event(uuid,text,text) from public,anon,authenticated;

create function mangaro_private.revoke_event(p_user_id uuid,p_type text,p_key text,p_reason text) returns void
language plpgsql security definer set search_path='' as $$
declare amount integer;
begin
 perform 1 from public.user_progression where user_id=p_user_id for update;
 update public.xp_events set revoked_at=clock_timestamp(),revoke_reason=p_reason
  where user_id=p_user_id and event_type=p_type and source_key=p_key and revoked_at is null returning xp_amount into amount;
 if amount>0 then perform mangaro_private.change_progression(p_user_id,-amount); end if;
end;
$$;
revoke all on function mangaro_private.revoke_event(uuid,text,text,text) from public,anon,authenticated;
create function mangaro_private.guard_xp_event() returns trigger
language plpgsql security invoker set search_path='' as $$
begin
 if row(new.id,new.user_id,new.event_type,new.source_key,new.xp_amount,new.created_at)
  is distinct from row(old.id,old.user_id,old.event_type,old.source_key,old.xp_amount,old.created_at)
  or old.revoked_at is not null then raise exception 'Immutable XP audit' using errcode='42501'; end if;
 return new;
end;
$$;
revoke all on function mangaro_private.guard_xp_event() from public,anon,authenticated;
create trigger xp_events_immutable before update on public.xp_events for each row execute function mangaro_private.guard_xp_event();
create function mangaro_private.comment_progression() returns trigger
language plpgsql security definer set search_path='' as $$
declare kind text;
begin
 if tg_op='INSERT' then
  kind:=case when new.parent_comment_id is null then 'comment_created' else 'reply_created' end;
  if exists(select 1 from public.profiles where user_id=new.user_id and username is not null) then
   perform mangaro_private.award_event(new.user_id,kind,new.id::text);
  end if;
  return new;
 else
  kind:=case when old.parent_comment_id is null then 'comment_created' else 'reply_created' end;
  perform mangaro_private.revoke_event(old.user_id,kind,old.id::text,'comment_deleted');
  return old;
 end if;
end;
$$;
revoke all on function mangaro_private.comment_progression() from public,anon,authenticated;
-- No historical comments are scanned or awarded. Edits/ratings/likes/reports never award XP.
create trigger community_comment_xp after insert or delete on public.community_comments
for each row execute function mangaro_private.comment_progression();

-- The sole client mutation RPC: no user ID, amount or level input.
create function public.claim_chapter_completion(p_manga_key text,p_chapter_key text) returns jsonb
language plpgsql security definer set search_path='' as $$
declare uid uuid:=(select auth.uid()); old_level smallint; amount integer:=0; inserted uuid; existing_key text; progress public.user_progression;
begin
 if uid is null or not exists(select 1 from public.profiles where user_id=uid and username is not null) then
  raise exception 'Authentication/complete profile required' using errcode='42501'; end if;
 if p_manga_key is null or p_chapter_key is null or p_manga_key !~ '^[a-f0-9]{64}$' or p_chapter_key !~ '^[a-f0-9]{64}$' then
  raise exception 'Invalid community keys' using errcode='22023'; end if;
 select level into old_level from public.user_progression where user_id=uid for update;
 if not found then raise exception 'Progression unavailable' using errcode='22023'; end if;
 select manga_key into existing_key from public.reader_chapter_completions where user_id=uid and chapter_key=p_chapter_key;
 if found and existing_key<>p_manga_key then raise exception 'Chapter context mismatch' using errcode='22023'; end if;
 insert into public.reader_chapter_completions(user_id,manga_key,chapter_key,completed_at)
  values(uid,p_manga_key,p_chapter_key,clock_timestamp()) on conflict do nothing returning user_id into inserted;
 if inserted is not null then
  amount:=mangaro_private.award_event(uid,'chapter_completed',p_chapter_key);
  update public.reader_chapter_completions set xp_awarded=(amount>0) where user_id=uid and chapter_key=p_chapter_key;
 end if;
 select * into progress from public.user_progression where user_id=uid;
 return jsonb_build_object('xp_awarded',amount,'new_total_xp',progress.total_xp,'new_level',progress.level,'leveled_up',progress.level>old_level);
end;
$$;
revoke all on function public.claim_chapter_completion(text,text) from public,anon,authenticated;
grant execute on function public.claim_chapter_completion(text,text) to authenticated;

-- Intentionally public, narrow projection; it cannot disclose total XP or private account data.
-- Batch join avoids network/profile requests per comment. Owner XP stays under table RLS.
create function public.community_author_levels(p_user_ids uuid[]) returns table(user_id uuid,level smallint)
language plpgsql stable security definer set search_path='' as $$
begin
 if cardinality(p_user_ids)>20 then raise exception 'Bounded author projection' using errcode='22023'; end if;
 return query select p.user_id,p.level from public.user_progression p where p.user_id=any(p_user_ids);
end;
$$;
revoke all on function public.community_author_levels(uuid[]) from public,anon,authenticated;
grant execute on function public.community_author_levels(uuid[]) to anon,authenticated;

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
   'id',c.id,'user_id',c.user_id,'body',c.body,'created_at',c.created_at,'updated_at',c.updated_at,'parent_comment_id',c.parent_comment_id,
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
revoke all on function public.community_comments_page(text,text,text,timestamptz,uuid,uuid,integer) from public,anon,authenticated;
grant execute on function public.community_comments_page(text,text,text,timestamptz,uuid,uuid,integer) to anon,authenticated;


commit;
