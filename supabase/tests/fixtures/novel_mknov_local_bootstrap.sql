-- Disposable PostgreSQL only: minimal Supabase Auth role/claim surface.
-- No real account rows, network sessions, auth tokens or production credentials.
do $$ begin if not exists(select 1 from pg_roles where rolname='anon') then create role anon nologin nosuperuser nobypassrls; end if; end $$;
do $$ begin if not exists(select 1 from pg_roles where rolname='authenticated') then create role authenticated nologin nosuperuser nobypassrls; end if; end $$;
create schema auth;
create schema mangaro_private;
create table auth.users(id uuid primary key);
create function auth.uid() returns uuid language sql stable as $$
 select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid;
$$;
grant usage on schema auth, mangaro_private to authenticated;
grant execute on function auth.uid() to authenticated;
