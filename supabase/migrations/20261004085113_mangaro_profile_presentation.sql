-- Profile presentation only. Existing Auth, Community, XP and cloud-sync rules stay intact.
begin;
alter table public.profiles add column bio text, add column cover_path text;
alter table public.profiles add constraint profiles_bio_valid check
    (bio is null or (bio = regexp_replace(bio, '^[[:space:]]+|[[:space:]]+$', '', 'g') and char_length(bio) between 1 and 160)),
    add constraint profiles_cover_path_valid check
    (cover_path is null or cover_path = user_id::text || '/cover.webp');
grant update(bio, cover_path) on public.profiles to authenticated;
create function public.normalize_profile_bio() returns trigger language plpgsql set search_path = '' as $$
begin new.bio := nullif(regexp_replace(new.bio, '^[[:space:]]+|[[:space:]]+$', '', 'g'), ''); return new; end;
$$;
revoke all on function public.normalize_profile_bio() from public, anon, authenticated;
create trigger profiles_normalize_bio before insert or update on public.profiles
    for each row execute function public.normalize_profile_bio();

insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types)
values('profile-media','profile-media',true,4194304,array['image/jpeg','image/png','image/webp']);
create policy mangaro_cover_public_read on storage.objects for select to anon,authenticated
    using(bucket_id='profile-media');
create policy mangaro_cover_owner_insert on storage.objects for insert to authenticated
    with check(bucket_id='profile-media' and (storage.foldername(name))[1]=(select auth.uid())::text
        and name=(select auth.uid())::text || '/cover.webp');
create policy mangaro_cover_owner_update on storage.objects for update to authenticated
    using(bucket_id='profile-media' and name=(select auth.uid())::text || '/cover.webp')
    with check(bucket_id='profile-media' and (storage.foldername(name))[1]=(select auth.uid())::text
        and name=(select auth.uid())::text || '/cover.webp');
create policy mangaro_cover_owner_delete on storage.objects for delete to authenticated
    using(bucket_id='profile-media' and (storage.foldername(name))[1]=(select auth.uid())::text
        and name=(select auth.uid())::text || '/cover.webp');

-- Live own-account counts, not an analytics/counter table. No user parameter or private data.
create index community_comments_author_count on public.community_comments(user_id);
create function public.profile_own_statistics() returns jsonb language sql stable security invoker set search_path = '' as $$
    select jsonb_build_object('comments',(select count(*) from public.community_comments where user_id=(select auth.uid())),
        'ratings',(select count(*) from public.community_ratings where user_id=(select auth.uid())));
$$;
revoke all on function public.profile_own_statistics() from public,anon,authenticated;
grant execute on function public.profile_own_statistics() to authenticated;
commit;
