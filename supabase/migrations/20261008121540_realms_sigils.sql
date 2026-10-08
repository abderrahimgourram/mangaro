-- Cosmetic achievements only. No writes to progression, roles, Auth or existing reading tables.
create table public.realm_sigil_facts (
 user_id uuid not null references auth.users(id) on delete cascade,
 kind text not null check(kind in ('chapter','work','category')),
 key text not null check(key ~ '^[0-9a-f]{64}$'),
 work text not null default '' check(work='' or work ~ '^[0-9a-f]{64}$'),
 genres text[] not null default '{}',
 is_read boolean not null default false, started boolean not null default false,
 bookmarked boolean not null default false, downloaded boolean not null default false,
 in_library boolean not null default false, organized boolean not null default false,
 occurred_at bigint check(occurred_at is null or occurred_at>0),
 primary key(user_id,kind,key),
 check(cardinality(genres)<=32), check((kind='chapter' and work<>'') or (kind<>'chapter' and work=''))
);
create table public.realm_sigil_unlocks (
 user_id uuid not null references auth.users(id) on delete cascade,
 sigil_id text not null, unlocked_at bigint,
 revoked boolean not null default false, moderation_reason text,
 primary key(user_id,sigil_id)
);
create table public.realm_sigil_equipment (
 user_id uuid primary key references auth.users(id) on delete cascade,
 slots jsonb not null default '[null,null,null]' check(jsonb_typeof(slots)='array' and jsonb_array_length(slots)=3),
 revision bigint not null default 0
);
alter table public.realm_sigil_facts enable row level security;
alter table public.realm_sigil_unlocks enable row level security;
alter table public.realm_sigil_equipment enable row level security;
revoke all on public.realm_sigil_facts,public.realm_sigil_unlocks,public.realm_sigil_equipment from anon,authenticated;
grant select on public.realm_sigil_facts,public.realm_sigil_unlocks,public.realm_sigil_equipment to authenticated;
create policy sigil_facts_owner on public.realm_sigil_facts for select to authenticated using(user_id=(select auth.uid()));
create policy sigil_unlocks_owner on public.realm_sigil_unlocks for select to authenticated using(user_id=(select auth.uid()));
create policy sigil_equipment_owner on public.realm_sigil_equipment for select to authenticated using(user_id=(select auth.uid()));

-- Add a restrictive policy; retain existing profile-completeness and authenticated ownership requirements.
-- Existing self-likes are not deleted; authoritative achievement counts always exclude them.
create function mangaro_private.sigil_authenticated_account() returns boolean
 language sql stable security definer set search_path='' as $$
 select exists(select 1 from auth.users where id=(select auth.uid()) and not coalesce(is_anonymous,false) and email_confirmed_at is not null); $$;
revoke all on function mangaro_private.sigil_authenticated_account() from public,anon;
grant execute on function mangaro_private.sigil_authenticated_account() to authenticated;
create policy sigil_no_self_like on public.community_comment_likes as restrictive for insert to authenticated
 with check((select mangaro_private.sigil_authenticated_account()) and exists(select 1 from public.community_comments c where c.id=comment_id and c.user_id<>(select auth.uid())));
create or replace function public.community_like_comment(p_comment_id uuid,p_liked boolean) returns void
language plpgsql security invoker set search_path='' as $$
begin
 if not public.community_profile_complete() then raise exception 'Profile required' using errcode='42501'; end if;
 if p_liked then
  if exists(select 1 from public.community_comments where id=p_comment_id and user_id=(select auth.uid())) then
   raise exception 'Self-like is not permitted' using errcode='42501';
  end if;
  insert into public.community_comment_likes(comment_id,user_id) values(p_comment_id,(select auth.uid())) on conflict do nothing;
 else
  delete from public.community_comment_likes where comment_id=p_comment_id and user_id=(select auth.uid());
 end if;
end $$;

create function mangaro_private.sigil_counts(p_user uuid) returns jsonb
language sql stable security definer set search_path='' as $$
with chapters as (
 select f.*,f.genres||coalesce(w.genres,'{}') as tags from public.realm_sigil_facts f
 left join public.realm_sigil_facts w on w.user_id=f.user_id and w.kind='work' and w.key=f.work
 where f.user_id=p_user and f.kind='chapter'
), read_counts as (
 select count(*)::int as reads,count(*) filter(where tags&&array['martial_arts'])::int as martial,
 count(*) filter(where tags&&array['romance'])::int as romance,count(*) filter(where tags&&array['historical'])::int as historical,
 count(*) filter(where tags&&array['romance','historical'])::int as court from chapters where is_read
), per_work as (select work,count(*) as n from chapters where is_read group by work),
 starts as (select key from public.realm_sigil_facts where user_id=p_user and kind='work' and started union select work from chapters where is_read),
 social as (
 select count(*) filter(where parent_comment_id is null and length(btrim(body)) between 1 and 2000)::int as comments,
 count(*) filter(where parent_comment_id is not null and length(btrim(body)) between 1 and 2000)::int as replies from public.community_comments where user_id=p_user
), liked as (
 select c.id,count(l.user_id)::int as n from public.community_comments c
 join public.community_comment_likes l on l.comment_id=c.id and l.user_id<>c.user_id
 join auth.users u on u.id=l.user_id and not coalesce(u.is_anonymous,false) and u.email_confirmed_at is not null
 where c.user_id=p_user and length(btrim(c.body)) between 1 and 2000 group by c.id
)
select jsonb_build_object(
 'gates_awakened',reads,'gates_hunter',reads,'gates_opener',reads,'gates_leader',reads,'gates_guardian',reads,
 'tower_visitor',(select count(*) from starts),'tower_climber',(select count(*) from starts),
 'tower_survivor',(select count(*) from per_work where n>=10),'tower_fates',(select count(*) from per_work where n>=20),
 'tower_narrator',(select count(*) from per_work where n>=20),
 'murim_scroll',(select count(*) from chapters where bookmarked),'murim_keeper',(select count(*) from chapters where bookmarked),
 'murim_student',martial,'murim_heir',martial,
 'murim_master',least(3,(select count(*)::int from public.realm_sigil_facts where user_id=p_user and kind='category'))+
 least(10,(select count(*)::int from public.realm_sigil_facts where user_id=p_user and kind='work' and organized)),
 'court_visitor',(select count(*) from starts s where exists(select 1 from public.realm_sigil_facts f where f.user_id=p_user and (f.key=s.key or f.work=s.key) and f.genres&&array['romance'])),
 'court_rose',romance,'court_regent',historical,
 'court_returner',(select count(*) from (select work from chapters where is_read and tags&&array['reincarnation','regression'] group by work having count(*)>=10) q),
 'court_historian',court,
 'archive_gem',(select count(*) from public.realm_sigil_facts where user_id=p_user and kind='work' and in_library),
 'archive_relics',(select count(*) from public.realm_sigil_facts where user_id=p_user and kind='work' and in_library),
 'archive_keeper',(select count(*) from public.realm_sigil_facts where user_id=p_user and kind='work' and organized),
 'archive_dimensions',(select count(distinct tag) from public.realm_sigil_facts f cross join lateral unnest(f.genres) tag where f.user_id=p_user and
 ((f.kind='chapter' and f.is_read) or (f.kind='work' and f.key in (select key from starts)))),
 'archive_volumes',(select count(*) from chapters where downloaded),
 'social_voice',comments,'social_pen',comments,'social_council',replies,
 'social_witness',(select count(distinct manga_key) from public.community_ratings where user_id=p_user and target_type='manga' and rating between 1 and 10),
 'social_revered',coalesce((select max(n) from liked),0)) from read_counts cross join social;
$$;
revoke all on function mangaro_private.sigil_counts(uuid) from public,anon,authenticated;

create function mangaro_private.sigil_award(p_user uuid,p_historical boolean default false) returns void
language plpgsql security definer set search_path='' as $$
declare counts jsonb;
begin
 counts:=mangaro_private.sigil_counts(p_user);
 insert into public.realm_sigil_unlocks(user_id,sigil_id,unlocked_at)
 select p_user,id,case when p_historical then null else (extract(epoch from clock_timestamp())*1000)::bigint end
 from (values
 ('gates_awakened',1),('gates_hunter',50),('gates_opener',200),('gates_leader',500),('gates_guardian',1000),
 ('tower_visitor',3),('tower_climber',10),('tower_survivor',3),('tower_fates',5),('tower_narrator',10),
 ('murim_scroll',1),('murim_keeper',20),('murim_student',20),('murim_heir',100),('murim_master',13),
 ('court_visitor',1),('court_rose',30),('court_regent',50),('court_returner',3),('court_historian',100),
 ('archive_gem',1),('archive_relics',20),('archive_keeper',10),('archive_dimensions',5),('archive_volumes',100),
 ('social_voice',1),('social_pen',50),('social_council',30),('social_witness',20),('social_revered',200)
 ) d(id,target) where coalesce((counts->>id)::int,0)>=target
 on conflict(user_id,sigil_id) do nothing;
end $$;
revoke all on function mangaro_private.sigil_award(uuid,boolean) from public,anon,authenticated;

-- Server-authoritative social awards run on real committed social activity, including likes on a user's comment.
create function mangaro_private.sigil_social_event() returns trigger language plpgsql security definer set search_path='' as $$
declare owner_id uuid;
begin
 if tg_table_name='community_comment_likes' then
  select user_id into owner_id from public.community_comments where id=new.comment_id;
 else owner_id:=new.user_id; end if;
 if owner_id is not null then
  perform pg_advisory_xact_lock(hashtextextended(owner_id::text,71421));
  perform mangaro_private.sigil_award(owner_id,false);
 end if;
 return new;
end $$;
revoke all on function mangaro_private.sigil_social_event() from public,anon,authenticated;
create trigger sigil_comment_activity after insert on public.community_comments for each row execute function mangaro_private.sigil_social_event();
create trigger sigil_rating_activity after insert or update on public.community_ratings for each row execute function mangaro_private.sigil_social_event();
create trigger sigil_like_activity after insert on public.community_comment_likes for each row execute function mangaro_private.sigil_social_event();

create function mangaro_private.sigils_reconcile(p_facts jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=(select auth.uid()); f jsonb; counts jsonb; result jsonb; historical boolean;
begin
 if u is null then raise exception 'Authentication required' using errcode='42501'; end if;
 if p_facts is null or jsonb_typeof(p_facts)<>'array' or jsonb_array_length(p_facts)>200 then raise exception 'Invalid evidence batch' using errcode='22023'; end if;
 -- Serialized per-account reconciliation; the caller never supplies an owner, counter or unlock ID.
 perform pg_advisory_xact_lock(hashtextextended(u::text,71421));
 historical:=not exists(select 1 from public.realm_sigil_equipment where user_id=u);
 insert into public.realm_sigil_equipment(user_id) values(u) on conflict do nothing;
 for f in select value from jsonb_array_elements(p_facts) loop
  if not (f->>'kind'=any(array['chapter','work','category'])) or coalesce(f->>'key','')!~'^[0-9a-f]{64}$'
   or coalesce(f->>'work','')!~'^([0-9a-f]{64})?$' then raise exception 'Invalid evidence identity' using errcode='22023'; end if;
  if exists(select 1 from jsonb_array_elements_text(coalesce(f->'genres','[]')) g where g not in
   ('martial_arts','romance','historical','reincarnation','regression','action','fantasy','comedy','adventure','drama','horror','sci_fi','mystery','slice_of_life','sports','psychological','supernatural','school_life')) then
   raise exception 'Unrecognized genre metadata' using errcode='22023'; end if;
  insert into public.realm_sigil_facts as old(user_id,kind,key,work,genres,is_read,started,bookmarked,downloaded,in_library,organized,occurred_at)
  values(u,f->>'kind',f->>'key',coalesce(f->>'work',''),array(select distinct value from jsonb_array_elements_text(coalesce(f->'genres','[]'))),
   coalesce((f->>'read')::boolean,false),coalesce((f->>'started')::boolean,false),coalesce((f->>'bookmarked')::boolean,false),
   coalesce((f->>'downloaded')::boolean,false),coalesce((f->>'library')::boolean,false),coalesce((f->>'organized')::boolean,false),
   case when f->>'occurredAt' is null then null else least((f->>'occurredAt')::bigint,(extract(epoch from clock_timestamp())*1000)::bigint) end)
  on conflict(user_id,kind,key) do update set
   genres=array(select distinct unnest(old.genres||excluded.genres)),is_read=old.is_read or excluded.is_read,
   started=old.started or excluded.started,bookmarked=old.bookmarked or excluded.bookmarked,downloaded=old.downloaded or excluded.downloaded,
   in_library=old.in_library or excluded.in_library,organized=old.organized or excluded.organized,
   occurred_at=least(old.occurred_at,excluded.occurred_at)
  where old.work=excluded.work;
 end loop;
 perform mangaro_private.sigil_award(u,historical or exists(select 1 from jsonb_array_elements(p_facts) x where x->>'occurredAt' is null));
 counts:=mangaro_private.sigil_counts(u);
 select jsonb_build_object(
  'facts',coalesce((select jsonb_agg(jsonb_build_object('key',key,'kind',kind,'work',work,'genres',genres,'read',is_read,'started',started,
   'bookmarked',bookmarked,'downloaded',downloaded,'library',in_library,'organized',organized,'occurredAt',occurred_at)) from public.realm_sigil_facts where user_id=u),'[]'),
  'unlocks',coalesce((select jsonb_agg(jsonb_build_object('id',sigil_id,'unlockedAt',unlocked_at,'revoked',revoked)) from public.realm_sigil_unlocks where user_id=u),'[]'),
  'slots',e.slots,'revision',e.revision,
  'community',jsonb_build_object('social_voice',counts->'social_voice','social_pen',counts->'social_pen','social_council',counts->'social_council',
    'social_witness',counts->'social_witness','social_revered',counts->'social_revered')) into result
 from public.realm_sigil_equipment e where user_id=u;
 return result;
end $$;
revoke all on function mangaro_private.sigils_reconcile(jsonb) from public,anon;
grant execute on function mangaro_private.sigils_reconcile(jsonb) to authenticated;
create function public.sigils_reconcile(p_facts jsonb default '[]') returns jsonb
 language sql security invoker set search_path='' as $$ select mangaro_private.sigils_reconcile(p_facts); $$;
revoke all on function public.sigils_reconcile(jsonb) from public,anon;
grant execute on function public.sigils_reconcile(jsonb) to authenticated;

create function mangaro_private.sigils_equip(p_slots jsonb,p_revision bigint) returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=(select auth.uid()); saved boolean:=false; result jsonb;
begin
 if u is null then raise exception 'Authentication required' using errcode='42501'; end if;
 if p_slots is null or jsonb_typeof(p_slots)<>'array' or jsonb_array_length(p_slots)<>3 then raise exception 'Three slots required' using errcode='22023'; end if;
 perform pg_advisory_xact_lock(hashtextextended(u::text,71421));
 if exists(select 1 from jsonb_array_elements_text(p_slots) s where s is not null and not exists(
   select 1 from public.realm_sigil_unlocks a where a.user_id=u and a.sigil_id=s and not a.revoked))
  or (select count(*) from jsonb_array_elements_text(p_slots) s where s is not null)<>
     (select count(distinct s) from jsonb_array_elements_text(p_slots) s where s is not null) then
  raise exception 'Only distinct earned sigils can be equipped' using errcode='42501'; end if;
 insert into public.realm_sigil_equipment(user_id) values(u) on conflict do nothing;
 update public.realm_sigil_equipment set slots=p_slots,revision=revision+1 where user_id=u and revision=p_revision;
 saved:=found;
 select jsonb_build_object('saved',saved,'slots',slots,'revision',revision) into result from public.realm_sigil_equipment where user_id=u;
 return result;
end $$;
revoke all on function mangaro_private.sigils_equip(jsonb,bigint) from public,anon;
grant execute on function mangaro_private.sigils_equip(jsonb,bigint) to authenticated;
create function public.sigils_equip(p_slots jsonb,p_revision bigint) returns jsonb language sql security invoker set search_path='' as $$
 select mangaro_private.sigils_equip(p_slots,p_revision); $$;
revoke all on function public.sigils_equip(jsonb,bigint) from public,anon;
grant execute on function public.sigils_equip(jsonb,bigint) to authenticated;

-- Public projection contains exactly three explicitly chosen IDs. No private progress or reading facts.
create function mangaro_private.sigils_public_slots(p_user uuid) returns jsonb
language sql stable security definer set search_path='' as $$
 select coalesce((select jsonb_agg(case when exists(select 1 from public.realm_sigil_unlocks u where u.user_id=p_user and u.sigil_id=s.value and not u.revoked)
 then to_jsonb(s.value) else 'null'::jsonb end order by s.ordinality)
 from public.realm_sigil_equipment e cross join lateral jsonb_array_elements_text(e.slots) with ordinality s(value,ordinality) where e.user_id=p_user),'[null,null,null]');
$$;
revoke all on function mangaro_private.sigils_public_slots(uuid) from public;
grant execute on function mangaro_private.sigils_public_slots(uuid) to anon,authenticated;
create function public.sigils_public_slots(p_user uuid) returns jsonb language sql stable security invoker set search_path='' as $$
 select mangaro_private.sigils_public_slots(p_user); $$;
revoke all on function public.sigils_public_slots(uuid) from public;
grant execute on function public.sigils_public_slots(uuid) to anon,authenticated;

-- Service-role moderation only; no client or level grants administrative power.
create function mangaro_private.revoke_sigil(p_user uuid,p_sigil text,p_reason text) returns void
language plpgsql security definer set search_path='' as $$
begin
 if length(btrim(p_reason))<5 then raise exception 'Moderation reason required'; end if;
 perform pg_advisory_xact_lock(hashtextextended(p_user::text,71421));
 update public.realm_sigil_unlocks set revoked=true,moderation_reason=p_reason where user_id=p_user and sigil_id=p_sigil;
 update public.realm_sigil_equipment e set slots=(select jsonb_agg(case when value=p_sigil then 'null'::jsonb else coalesce(to_jsonb(value),'null') end order by ordinality)
 from jsonb_array_elements_text(e.slots) with ordinality),revision=revision+1 where user_id=p_user;
end $$;
revoke all on function mangaro_private.revoke_sigil(uuid,text,text) from public,anon,authenticated;
grant usage on schema mangaro_private to service_role;
grant execute on function mangaro_private.revoke_sigil(uuid,text,text) to service_role;

-- Historical social state is authoritative, but the original unlock dates are unknown.
do $$ declare u uuid; begin
 for u in select distinct user_id from public.community_comments union select distinct user_id from public.community_ratings loop
  perform mangaro_private.sigil_award(u,true);
 end loop;
end $$;
