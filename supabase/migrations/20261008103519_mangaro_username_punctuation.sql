begin;
-- Widen validation only. Never rewrite account rows, canonicalization, reservations or RLS.
set local lock_timeout = '5s';

do $preflight$
begin
 if not exists(select 1 from pg_index
  where indexrelid='public.profiles_username_lower_unique'::regclass
  and indisunique and indisvalid and indisready) then
  raise exception 'Username unique index unavailable';
 end if;
 if exists(select 1 from public.profiles
  where username is not null and username !~ '^[a-z0-9_.-]{3,24}$') then
  raise exception 'Existing username incompatible; stop without changing user data';
 end if;
end $preflight$;

alter table public.profiles drop constraint profiles_username_valid;
alter table public.profiles add constraint profiles_username_valid
 check (username is null or username ~ '^[a-z0-9_.-]{3,24}$');

-- CREATE OR REPLACE preserves the existing owner and EXECUTE permissions.
create or replace function public.username_available(p_username text) returns boolean
 language sql stable security invoker set search_path='' as $$
 select public.canonical_username(p_username) ~ '^[a-z0-9_.-]{3,24}$'
 and mangaro_private.username_allowed(public.canonical_username(p_username),(select auth.uid()))
 and not exists(select 1 from public.profiles
  where lower(username)=public.canonical_username(p_username) and user_id<>(select auth.uid()));
$$;

-- Read-only checks under the actual client role; no test account or profile is created.
set local role authenticated;
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000000',true);
do $verify$
declare existing_name text;
begin
 if public.canonical_username(' MNG.Test_1206-X ') <> 'mng.test_1206-x'
  or not public.username_available(' MNG.Test_1206-X ')
  or not public.username_available('53-v')
  or public.username_available('has space')
  or public.username_available(U&'\206653.v\2069') then
  raise exception 'Username punctuation/canonical validation failed';
 end if;
 if public.username_available('ADMIN') or public.username_available('support') then
  raise exception 'Reserved-name protection failed';
 end if;
 select username into existing_name from public.profiles where username is not null limit 1;
 if existing_name is not null and public.username_available(existing_name) then
  raise exception 'Existing account username was incorrectly available';
 end if;
end $verify$;
reset role;

do $protection$
begin
 if (select md5(pg_get_functiondef('public.canonical_username(text)'::regprocedure))) <> '230dccdf82b2b10055aca42b37b8d641'
  or (select md5(pg_get_functiondef('public.profile_username_rules()'::regprocedure))) <> '373b334b15897d3e883d9787f76416a4'
  or (select md5(pg_get_functiondef('public.set_updated_at()'::regprocedure))) <> '4232a913eac347c5308d477dc8b1781e'
  or (select md5(pg_get_functiondef('mangaro_private.username_allowed(text,uuid)'::regprocedure))) <> '84b4f577018cbb4ed27aa35a23f267d8' then
  raise exception 'Canonicalization or reserved protection changed';
 end if;
 if (select md5(string_agg(policyname||coalesce(qual,'')||coalesce(with_check,''),'|' order by policyname))
  from pg_policies where schemaname='public' and tablename='profiles') <> '3f261ef71532d00f900e7246a70a8fac' then
  raise exception 'Profile RLS changed';
 end if;
 if (select proacl::text from pg_proc where oid='public.username_available(text)'::regprocedure)
  <> '{postgres=X/postgres,authenticated=X/postgres}' then
  raise exception 'Availability permissions changed';
 end if;
end $protection$;
commit;
