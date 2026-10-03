begin;
-- Storage API transaction context for metadata-only fixtures; RLS remains enabled.
select set_config('storage.allow_delete_query', 'true', true);
select set_config('mangaro.test.a', gen_random_uuid()::text, true), set_config('mangaro.test.b', gen_random_uuid()::text, true);
insert into auth.users(id, raw_app_meta_data, raw_user_meta_data)
values(current_setting('mangaro.test.a')::uuid, '{"provider":"google"}', '{"full_name":"  قارئ اختباري  "}'),
      (current_setting('mangaro.test.b')::uuid, '{"provider":"google"}', '{}');
do $$ begin
 if (select count(*) from public.profiles where user_id in (current_setting('mangaro.test.a')::uuid,current_setting('mangaro.test.b')::uuid)) <> 2 then raise exception 'SIGNUP_TRIGGER_FAILED'; end if;
 if exists(select 1 from public.profiles where user_id=current_setting('mangaro.test.a')::uuid and (username is not null or display_name <> 'قارئ اختباري')) then raise exception 'INITIAL_PROFILE_FAILED'; end if;
end $$;
-- Regression: a truncation boundary on whitespace must not abort a Google signup.
insert into auth.users(id,raw_app_meta_data,raw_user_meta_data)
values(gen_random_uuid(),'{"provider":"google"}',jsonb_build_object('full_name',repeat('a',39)||' continued'));
set local role anon;
do $$ begin
 begin update public.profiles set username='blocked'; raise exception 'ANON_PROFILE_WRITE_ALLOWED'; exception when insufficient_privilege then null; end;
 begin insert into storage.objects(bucket_id,name) values('avatars',current_setting('mangaro.test.a')||'/avatar.webp'); raise exception 'ANON_AVATAR_WRITE_ALLOWED'; exception when insufficient_privilege then null; end;
end $$;
reset role;
select set_config('request.jwt.claim.sub',current_setting('mangaro.test.a'),true), set_config('request.jwt.claim.role','authenticated',true);
set local role authenticated;
do $$ declare affected integer; begin
 update public.profiles set username='Jalem',display_name='  قارئ حقيقي  ' where user_id=current_setting('mangaro.test.a')::uuid;
 get diagnostics affected = row_count;
 if affected <> 1 or not exists(select 1 from public.profiles where user_id=current_setting('mangaro.test.a')::uuid and username='jalem' and display_name='قارئ حقيقي') then raise exception 'OWNER_UPDATE_NORMALIZATION_FAILED'; end if;
 update public.profiles set display_name='blocked' where user_id=current_setting('mangaro.test.b')::uuid;
 get diagnostics affected = row_count; if affected <> 0 then raise exception 'NONOWNER_PROFILE_WRITE_ALLOWED'; end if;
 begin update public.profiles set created_at=now()-interval '1 day' where user_id=current_setting('mangaro.test.a')::uuid; raise exception 'CREATED_AT_WRITE_ALLOWED'; exception when insufficient_privilege then null; end;
 begin update public.profiles set user_id=current_setting('mangaro.test.b')::uuid where user_id=current_setting('mangaro.test.a')::uuid; raise exception 'USER_ID_WRITE_ALLOWED'; exception when insufficient_privilege then null; end;
 insert into storage.objects(bucket_id,name) values('avatars',current_setting('mangaro.test.a')||'/avatar.webp');
 update storage.objects set metadata='{"verified":"owner"}' where bucket_id='avatars' and name=current_setting('mangaro.test.a')||'/avatar.webp';
 get diagnostics affected = row_count; if affected <> 1 then raise exception 'OWNER_AVATAR_UPDATE_FAILED'; end if;
 begin insert into storage.objects(bucket_id,name) values('avatars',current_setting('mangaro.test.b')||'/avatar.webp'); raise exception 'NONOWNER_AVATAR_INSERT_ALLOWED'; exception when insufficient_privilege then null; end;
end $$;
reset role;
select set_config('request.jwt.claim.sub',current_setting('mangaro.test.b'),true);
set local role authenticated;
do $$ declare affected integer; handle text; begin
 begin update public.profiles set username='JALEM' where user_id=current_setting('mangaro.test.b')::uuid; raise exception 'USERNAME_CASE_CONFLICT_ALLOWED'; exception when unique_violation then null; end;
 foreach handle in array array['@jalem','jal em','ab','قارئ'] loop
   begin update public.profiles set username=handle where user_id=current_setting('mangaro.test.b')::uuid; raise exception 'INVALID_USERNAME_ALLOWED'; exception when check_violation then null; end;
 end loop;
 begin update public.profiles set display_name=repeat('ق',41) where user_id=current_setting('mangaro.test.b')::uuid; raise exception 'DISPLAY_NAME_LIMIT_FAILED'; exception when check_violation then null; end;
 update storage.objects set metadata='{"verified":"intruder"}' where bucket_id='avatars' and name=current_setting('mangaro.test.a')||'/avatar.webp';
 get diagnostics affected = row_count; if affected <> 0 then raise exception 'NONOWNER_AVATAR_UPDATE_ALLOWED'; end if;
 delete from storage.objects where bucket_id='avatars' and name=current_setting('mangaro.test.a')||'/avatar.webp';
 get diagnostics affected = row_count; if affected <> 0 then raise exception 'NONOWNER_AVATAR_DELETE_ALLOWED'; end if;
end $$;
reset role;
-- Verify automatic RLS still operates after revoking direct helper EXECUTE.
create table public.mangaro_phase15_rls_probe(id integer);
do $$ begin
 if not (select relrowsecurity from pg_class where oid='public.mangaro_phase15_rls_probe'::regclass) then raise exception 'AUTO_RLS_DISABLED'; end if;
 if exists(select 1 from information_schema.columns where table_schema='public' and table_name='profiles' and column_name in ('email','access_token','refresh_token','xp','level')) then raise exception 'PUBLIC_PRIVATE_DATA_EXPOSED'; end if;
end $$;
rollback;
select 'PASS: signup, anon denies, owner writes, nonowner denies, immutable fields, username uniqueness/format, display-name limit, avatar ownership, auto-RLS, public privacy; all fixtures rolled back' as result;
