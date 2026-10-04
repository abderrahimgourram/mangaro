-- All users, media metadata and social fixtures are rolled back; no Storage bytes are uploaded.
begin;
select set_config('storage.allow_delete_query','true',true);
select set_config('profile.test.a',gen_random_uuid()::text,true),set_config('profile.test.b',gen_random_uuid()::text,true);
insert into auth.users(id,raw_app_meta_data,raw_user_meta_data)
select current_setting(k)::uuid,'{"provider":"google"}'::jsonb,'{}'::jsonb from unnest(array['profile.test.a','profile.test.b']) k;
update public.profiles set username='p_'||substr(replace(user_id::text,'-',''),1,16) where user_id in(current_setting('profile.test.a')::uuid,current_setting('profile.test.b')::uuid);
set local role anon;
do $$ begin
 begin update public.profiles set bio='Guest'; raise exception 'ANON_PROFILE_WRITE'; exception when insufficient_privilege then null; end;
 begin insert into storage.objects(bucket_id,name) values('profile-media',current_setting('profile.test.a')||'/cover.webp'); raise exception 'ANON_COVER_UPLOAD'; exception when insufficient_privilege then null; end;
 begin perform public.profile_own_statistics(); raise exception 'ANON_OWN_STATS'; exception when insufficient_privilege then null; end;
end $$;
reset role;
select set_config('request.jwt.claim.sub',current_setting('profile.test.a'),true),set_config('request.jwt.claim.role','authenticated',true);
set local role authenticated;
do $$ declare n integer; counts jsonb; begin
 update public.profiles set bio='  أقرأ المانجا  ',cover_path=auth.uid()::text||'/cover.webp' where user_id=auth.uid();
 if not exists(select 1 from public.profiles where user_id=auth.uid() and bio='أقرأ المانجا') then raise exception 'BIO_TRIM'; end if;
 update public.profiles set bio=repeat('ق',160) where user_id=auth.uid();
 begin update public.profiles set bio=repeat('ق',161) where user_id=auth.uid(); raise exception 'BIO_LENGTH'; exception when check_violation then null; end;
 update public.profiles set bio=E' \t\n ' where user_id=auth.uid();
 if not exists(select 1 from public.profiles where user_id=auth.uid() and bio is null) then raise exception 'EMPTY_BIO'; end if;
 begin update public.profiles set cover_path=current_setting('profile.test.b')||'/cover.webp' where user_id=auth.uid(); raise exception 'OTHER_COVER_PATH'; exception when check_violation then null; end;
 update public.profiles set bio='Intruder' where user_id=current_setting('profile.test.b')::uuid;
 get diagnostics n=row_count; if n<>0 then raise exception 'OTHER_PROFILE_UPDATE'; end if;
 insert into storage.objects(bucket_id,name) values('profile-media',auth.uid()::text||'/cover.webp');
 update storage.objects set metadata='{"test":"own"}' where bucket_id='profile-media' and name=auth.uid()::text||'/cover.webp';
 get diagnostics n=row_count; if n<>1 then raise exception 'OWNER_COVER_UPDATE'; end if;
 begin insert into storage.objects(bucket_id,name) values('profile-media',current_setting('profile.test.b')||'/cover.webp'); raise exception 'OTHER_COVER_UPLOAD'; exception when insufficient_privilege then null; end;
 begin insert into storage.objects(bucket_id,name) values('profile-media',auth.uid()::text||'/other.webp'); raise exception 'ARBITRARY_MEDIA_PATH'; exception when insufficient_privilege then null; end;
 insert into public.community_comments(target_type,manga_key,user_id,body) values('manga',repeat('c',64),auth.uid(),'Stats fixture');
 perform public.community_set_rating('manga',repeat('c',64),null,4::smallint);
 counts:=public.profile_own_statistics();
 if counts<>jsonb_build_object('comments',1,'ratings',1) then raise exception 'OWN_LIVE_COUNTS'; end if;
end $$;
reset role;
set local role anon;
do $$ begin
 if not exists(select 1 from public.profiles where user_id=current_setting('profile.test.a')::uuid and cover_path=current_setting('profile.test.a')||'/cover.webp') then raise exception 'PUBLIC_COVER_PROJECTION'; end if;
 if not exists(select 1 from storage.objects where bucket_id='profile-media' and name=current_setting('profile.test.a')||'/cover.webp') then raise exception 'PUBLIC_MEDIA_READ'; end if;
end $$;
reset role;
select set_config('request.jwt.claim.sub',current_setting('profile.test.b'),true);
set local role authenticated;
do $$ declare n integer; begin
 update storage.objects set metadata='{"test":"intruder"}' where bucket_id='profile-media' and name=current_setting('profile.test.a')||'/cover.webp';
 get diagnostics n=row_count; if n<>0 then raise exception 'OTHER_COVER_UPDATE'; end if;
 delete from storage.objects where bucket_id='profile-media' and name=current_setting('profile.test.a')||'/cover.webp';
 get diagnostics n=row_count; if n<>0 then raise exception 'OTHER_COVER_DELETE'; end if;
 if public.profile_own_statistics()<>jsonb_build_object('comments',0,'ratings',0) then raise exception 'OTHER_COUNTS_LEAK'; end if;
end $$;
reset role;
select set_config('request.jwt.claim.sub',current_setting('profile.test.a'),true);
set local role authenticated;
delete from storage.objects where bucket_id='profile-media' and name=auth.uid()::text||'/cover.webp';
reset role;
do $$ begin
 if exists(select 1 from storage.objects where bucket_id='profile-media' and name=current_setting('profile.test.a')||'/cover.webp') then raise exception 'OWNER_COVER_DELETE'; end if;
 if not exists(select 1 from storage.buckets where id='profile-media' and public and file_size_limit=4194304 and allowed_mime_types=array['image/jpeg','image/png','image/webp']) then raise exception 'BUCKET_LIMITS'; end if;
 if exists(select 1 from information_schema.columns where table_schema='public' and table_name='profiles' and column_name in('email','access_token','refresh_token')) then raise exception 'PRIVATE_PROFILE_DATA'; end if;
 if not(select relrowsecurity from pg_class where oid='public.profiles'::regclass) then raise exception 'PROFILE_RLS'; end if;
end $$;
rollback;
select 'PASS: bio Unicode/trim/limit, owner profiles, media ownership/read scope, live own counts, bucket limits, privacy; fixtures rolled back' as result;
