-- All identities/content/media fixtures are transactional and rolled back.
begin;
select set_config('mangaro.identity.a',gen_random_uuid()::text,true),set_config('mangaro.identity.b',gen_random_uuid()::text,true);
select set_config('mangaro.identity.name','id_'||substr(replace(gen_random_uuid()::text,'-',''),1,16),true);
insert into auth.users(id,raw_app_meta_data,raw_user_meta_data)
select current_setting(k)::uuid,'{"provider":"google"}'::jsonb,'{"full_name":"Identity test"}'::jsonb
from unnest(array['mangaro.identity.a','mangaro.identity.b']) k;
create function pg_temp.expect_identity_failure(statement text,expected text) returns void language plpgsql as $$
begin begin execute statement; exception when others then if sqlstate=expected then return; else raise; end if; end;
 raise exception 'Expected rejection %: %',expected,statement; end $$;
select set_config('request.jwt.claim.sub',current_setting('mangaro.identity.a'),true);
set local role authenticated;
update public.profiles set username=upper(current_setting('mangaro.identity.name'))||' ',display_name='jalem' where user_id=auth.uid();
do $$ declare payload jsonb; begin
 if (select username from public.profiles where user_id=auth.uid())<>current_setting('mangaro.identity.name') then raise exception 'Normalization failed'; end if;
 if public.username_available('jalem') or public.username_available('admin') then raise exception 'Reservation unavailable'; end if;
 if not public.username_available(current_setting('mangaro.identity.name')) then raise exception 'Owner handle unavailable'; end if;
 perform pg_temp.expect_identity_failure('update public.profiles set username=''jalem'' where user_id=auth.uid()','23505');
 perform pg_temp.expect_identity_failure('insert into public.profile_roles values(auth.uid(),''developer'')','42501');
 perform pg_temp.expect_identity_failure('update public.profile_roles set role=''developer''','42501');
 perform pg_temp.expect_identity_failure('update public.user_progression set level=30 where user_id=auth.uid()','42501');
 payload:=public.community_public_profile(auth.uid());
 if payload->>'role'<>'user' or (payload->>'level')::integer<>1 then raise exception 'Display-name impersonation'; end if;
 if payload->'chapters_read'<>'null'::jsonb or payload->'favorites'<>'[]'::jsonb then raise exception 'Default visibility not private'; end if;
 perform public.save_public_showcase(false,'[{"manga_key":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","title":"Private favorite"}]');
 perform pg_temp.expect_identity_failure('select public.save_public_showcase(true,''[{"manga_key":"bad","title":"Invalid"}]'')','23514');
 perform pg_temp.expect_identity_failure('select public.save_public_showcase(true,''[{"manga_key":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","title":"Bad","source_id":123}]'')','22023');
 perform pg_temp.expect_identity_failure('select public.save_public_showcase(true,''null''::jsonb)','22023');
 perform pg_temp.expect_identity_failure('insert into public.public_favorites(user_id,manga_key,title,sort_order) values(auth.uid(),repeat(''b'',64),''Arbitrary'',0)','42501');
 if (select count(*) from public.public_favorites)<>1 then raise exception 'Failed save destroyed draft'; end if;
 perform pg_temp.expect_identity_failure('select public.save_public_showcase(true,(select jsonb_agg(jsonb_build_object(''manga_key'',lpad(i::text,64,''0''),''title'',''work'')) from generate_series(1,6) i))','22023');
end $$;
reset role;
-- Explicit cloud unread state overrides an earlier completion; aggregate does not double count.
insert into public.reader_chapter_completions(user_id,manga_key,chapter_key) values(current_setting('mangaro.identity.a')::uuid,repeat('a',64),repeat('c',64)),(current_setting('mangaro.identity.a')::uuid,repeat('a',64),repeat('d',64));
insert into public.cloud_chapter_progress(user_id,manga_key,chapter_key,source_chapter_url,is_read) values
(current_setting('mangaro.identity.a')::uuid,repeat('a',64),repeat('c',64),'/private-c',true),
(current_setting('mangaro.identity.a')::uuid,repeat('a',64),repeat('d',64),'/private-d',false),
(current_setting('mangaro.identity.a')::uuid,repeat('a',64),repeat('e',64),'/private-e',true);
select set_config('request.jwt.claim.sub',current_setting('mangaro.identity.b'),true);
set local role authenticated;
do $$ begin
 perform pg_temp.expect_identity_failure('update public.profiles set username=upper(current_setting(''mangaro.identity.name'')) where user_id=auth.uid()','23505');
 if exists(select 1 from public.public_favorites) then raise exception 'Private showcase leak'; end if;
 if exists(select 1 from public.cloud_chapter_progress) then raise exception 'Private progress leak'; end if;
 update public.profiles set username='id_'||substr(replace(auth.uid()::text,'-',''),1,16) where user_id=auth.uid();
 perform public.save_public_showcase(true,'[]');
 if exists(select 1 from public.public_showcase_settings where user_id=current_setting('mangaro.identity.a')::uuid) then raise exception 'Cross-owner preference'; end if;
 perform pg_temp.expect_identity_failure('update public.public_showcase_settings set enabled=true where user_id=auth.uid()','42501');
end $$;
reset role;
select set_config('request.jwt.claim.sub',current_setting('mangaro.identity.a'),true);
set local role authenticated;
select public.save_public_showcase(true,'[{"manga_key":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","title":"Public favorite"}]');
reset role;
set local role anon;
do $$ declare p jsonb; begin
 p:=public.community_public_profile(current_setting('mangaro.identity.a')::uuid);
 if jsonb_array_length(p->'favorites')<>1 or (p->>'chapters_read')::bigint<>2 then raise exception 'Aggregate/showcase wrong'; end if;
 if p::text ~ '"(email|total_xp|source_id|source_url|source_manga_url|source_chapter_url|last_page_index|chapter_key|raw_user_meta_data|access_token)"' then raise exception 'Private field leak'; end if;
 p:=public.community_public_profile('ad5f6ca4-dec3-466e-a276-676284faab26');
 if p->>'role'<>'developer' or (p->>'level')::int<>30 then raise exception 'Confirmed developer identity missing'; end if;
 perform pg_temp.expect_identity_failure('select public.save_public_showcase(true,''[]'')','42501');
 perform pg_temp.expect_identity_failure('select * from public.cloud_chapter_progress','42501');
 perform pg_temp.expect_identity_failure('select * from public.cloud_manga_history','42501');
end $$;
reset role;
-- Role follows immutable owner identity when their username changes.
select set_config('request.jwt.claim.sub','ad5f6ca4-dec3-466e-a276-676284faab26',true);
set local role authenticated;
update public.profiles set username='owner_'||substr(replace(gen_random_uuid()::text,'-',''),1,16) where user_id=auth.uid();
do $$ begin
 if public.community_public_profile(auth.uid())->>'role'<>'developer' then raise exception 'Role tied to handle'; end if;
end $$;
reset role;
select set_config('request.jwt.claim.sub',current_setting('mangaro.identity.a'),true);
set local role authenticated;
select public.save_public_showcase(false,'[]');
reset role;
-- Private showcase media never gives another user write access.
insert into storage.objects(bucket_id,name) values('showcase-covers',current_setting('mangaro.identity.a')||'/'||repeat('a',64)||'.webp');
select set_config('request.jwt.claim.sub',current_setting('mangaro.identity.b'),true);
set local role authenticated;
do $$ begin
 if exists(select 1 from storage.objects where bucket_id='showcase-covers') then raise exception 'Private cover leak'; end if;
 perform pg_temp.expect_identity_failure(format('insert into storage.objects(bucket_id,name) values(''showcase-covers'',%L)',current_setting('mangaro.identity.a')||'/'||repeat('b',64)||'.webp'),'42501');
 update storage.objects set name=auth.uid()::text||'/'||repeat('c',64)||'.webp' where bucket_id='showcase-covers';
end $$;
reset role;
-- Server trusted milestones unlock bounded slots without giving generic XP mutation access.
update public.user_progression set total_xp=208,level=5 where user_id=current_setting('mangaro.identity.a')::uuid;
select set_config('request.jwt.claim.sub',current_setting('mangaro.identity.a'),true);
set local role authenticated;
select public.save_public_showcase(true,(select jsonb_agg(jsonb_build_object('manga_key',lpad(i::text,64,'0'),'title','work')) from generate_series(1,10) i));
select public.save_public_showcase(false,'[]');
reset role;
set local role anon;
do $$ begin
 if exists(select 1 from public.public_favorites where user_id=current_setting('mangaro.identity.a')::uuid) then raise exception 'Disabled showcase visible'; end if;
 if public.community_public_profile(current_setting('mangaro.identity.a')::uuid)->'chapters_read'<>'null'::jsonb then raise exception 'Disabled aggregate visible'; end if;
end $$;
reset role;
do $$ begin
 if public.progression_level(4408)<>30 or public.progression_level(39)<>1 or public.progression_level(40)<>2 then raise exception 'Level curve changed'; end if;
 if not exists(select 1 from pg_index where indexrelid='public.profiles_username_lower_unique'::regclass and indisunique and indisvalid and indisready) then raise exception 'Race-safe constraint missing'; end if;
 if (select count(*) from public.profile_roles where role='developer')<>1 then raise exception 'Unexpected staff'; end if;
end $$;
select 'Phase 9 identity, role, privacy, caps and aggregate tests passed' as result;
rollback;
