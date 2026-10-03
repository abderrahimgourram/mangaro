-- Forward-only fix: truncation may introduce trailing whitespace at the 40-character boundary.
create or replace function public.handle_new_user() returns trigger
language plpgsql security definer set search_path = '' as $$
declare
    photo text;
    display text;
begin
    if new.raw_app_meta_data->>'provider' = 'google' then
        display := nullif(btrim(left(btrim(coalesce(new.raw_user_meta_data->>'full_name', new.raw_user_meta_data->>'name', '')), 40)), '');
        photo := coalesce(new.raw_user_meta_data->>'avatar_url', new.raw_user_meta_data->>'picture');
        if photo not like 'https://%' or char_length(photo) > 2048 then photo := null; end if;
        insert into public.profiles(user_id, display_name, google_avatar_url)
            values(new.id, display, photo) on conflict (user_id) do nothing;
    end if;
    return new;
end;
$$;
revoke all on function public.handle_new_user() from public, anon, authenticated;
