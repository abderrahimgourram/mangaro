-- Mangaro Phase 1 server schema. Separate from all Android/Mihon local SQL.
begin;
-- Confirmed zero-argument platform event-trigger helper. Keep ensure_rls enabled.
revoke execute on function public.rls_auto_enable() from public, anon, authenticated;

create table public.profiles (
    user_id uuid primary key references auth.users(id) on delete cascade,
    username text,
    display_name text,
    avatar_path text,
    google_avatar_url text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint profiles_username_valid check (username is null or username ~ '^[a-z0-9_]{3,24}$'),
    constraint profiles_display_name_valid check (display_name is null or (display_name = btrim(display_name) and char_length(display_name) between 1 and 40)),
    constraint profiles_avatar_path_valid check (avatar_path is null or avatar_path = user_id::text || '/avatar.webp'),
    constraint profiles_google_avatar_valid check (google_avatar_url is null or (google_avatar_url like 'https://%' and char_length(google_avatar_url) <= 2048))
);
create unique index profiles_username_lower_unique on public.profiles (lower(username)) where username is not null;
alter table public.profiles enable row level security;
revoke all on public.profiles from public, anon, authenticated;
grant select on public.profiles to anon, authenticated;
grant update (username, display_name, avatar_path) on public.profiles to authenticated;
create policy profiles_public_read on public.profiles for select to anon, authenticated using (true);
create policy profiles_owner_update on public.profiles for update to authenticated
    using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
-- No client INSERT/DELETE grant: profile identity is server-created. No email or editable XP/level.

create function public.set_updated_at() returns trigger
language plpgsql set search_path = '' as $$
begin
    if new.user_id is distinct from old.user_id or new.created_at is distinct from old.created_at
       or new.google_avatar_url is distinct from old.google_avatar_url then
        raise exception 'Immutable profile fields' using errcode = '42501';
    end if;
    new.username := nullif(lower(btrim(new.username)), '');
    new.display_name := nullif(btrim(new.display_name), '');
    new.updated_at := now();
    return new;
end;
$$;
revoke all on function public.set_updated_at() from public, anon, authenticated;
create trigger profiles_set_updated_at before update on public.profiles
for each row execute function public.set_updated_at();

-- This trigger needs definer rights because clients cannot INSERT profile rows.
-- Google metadata is presentation only, never used for authorization.
create function public.handle_new_user() returns trigger
language plpgsql security definer set search_path = '' as $$
declare
    photo text;
    display text;
begin
    if new.raw_app_meta_data->>'provider' = 'google' then
        display := nullif(left(btrim(coalesce(new.raw_user_meta_data->>'full_name', new.raw_user_meta_data->>'name', '')), 40), '');
        photo := coalesce(new.raw_user_meta_data->>'avatar_url', new.raw_user_meta_data->>'picture');
        if photo not like 'https://%' or char_length(photo) > 2048 then photo := null; end if;
        insert into public.profiles(user_id, display_name, google_avatar_url)
            values(new.id, display, photo) on conflict (user_id) do nothing;
    end if;
    return new;
end;
$$;
revoke all on function public.handle_new_user() from public, anon, authenticated;
create trigger on_auth_user_created after insert on auth.users
for each row execute function public.handle_new_user();
-- Safe backfill for Google users created before this migration; existing profiles remain unchanged.
insert into public.profiles(user_id, display_name, google_avatar_url)
select id, nullif(left(btrim(coalesce(raw_user_meta_data->>'full_name', raw_user_meta_data->>'name', '')), 40), ''),
    case when coalesce(raw_user_meta_data->>'avatar_url', raw_user_meta_data->>'picture') like 'https://%'
         and char_length(coalesce(raw_user_meta_data->>'avatar_url', raw_user_meta_data->>'picture')) <= 2048
         then coalesce(raw_user_meta_data->>'avatar_url', raw_user_meta_data->>'picture') else null end
from auth.users where raw_app_meta_data->>'provider' = 'google'
on conflict(user_id) do nothing;

insert into storage.buckets(id, name, public, file_size_limit, allowed_mime_types)
values('avatars', 'avatars', true, 2097152, array['image/jpeg', 'image/png', 'image/webp']);
create policy mangaro_avatars_public_read on storage.objects for select to anon, authenticated
    using (bucket_id = 'avatars');
create policy mangaro_avatars_owner_insert on storage.objects for insert to authenticated
    with check (bucket_id = 'avatars' and (storage.foldername(name))[1] = (select auth.uid())::text and name = (select auth.uid())::text || '/avatar.webp');
create policy mangaro_avatars_owner_update on storage.objects for update to authenticated
    using (bucket_id = 'avatars' and (storage.foldername(name))[1] = (select auth.uid())::text and name = (select auth.uid())::text || '/avatar.webp')
    with check (bucket_id = 'avatars' and (storage.foldername(name))[1] = (select auth.uid())::text and name = (select auth.uid())::text || '/avatar.webp');
create policy mangaro_avatars_owner_delete on storage.objects for delete to authenticated
    using (bucket_id = 'avatars' and (storage.foldername(name))[1] = (select auth.uid())::text and name = (select auth.uid())::text || '/avatar.webp');
commit;
