-- Non-destructive production verification: all users, comments, likes, ratings and XP roll back.
begin;
select set_config('presentation.a',gen_random_uuid()::text,true),set_config('presentation.b',gen_random_uuid()::text,true);
insert into auth.users(id,raw_app_meta_data,raw_user_meta_data)
select current_setting(k)::uuid,'{"provider":"google"}'::jsonb,'{}'::jsonb from unnest(array['presentation.a','presentation.b']) k;
update public.profiles set username='t_'||substr(replace(user_id::text,'-',''),1,16),display_name='مستخدم',bio='نبذة عامة',cover_path=user_id::text||'/cover.webp'
 where user_id in(current_setting('presentation.a')::uuid,current_setting('presentation.b')::uuid);
select set_config('request.jwt.claim.sub',current_setting('presentation.a'),true),set_config('request.jwt.claim.role','authenticated',true);
set local role authenticated;
insert into public.community_comments(target_type,manga_key,user_id,body)
select 'manga',repeat('e',64),auth.uid(),'Presentation fixture '||n from generate_series(1,23) n;
select public.community_set_rating('manga',repeat('e',64),null,4::smallint);
reset role;
select set_config('presentation.top',(select id::text from public.community_comments where user_id=current_setting('presentation.a')::uuid order by created_at,id limit 1),true);
select set_config('request.jwt.claim.sub',current_setting('presentation.b'),true);
set local role authenticated;
select public.community_like_comment(current_setting('presentation.top')::uuid,true);
reset role;
select set_config('request.jwt.claim.sub','',true),set_config('request.jwt.claim.role','anon',true);
set local role anon;
do $$ declare profile jsonb; page jsonb; next_page jsonb; c jsonb; begin
 profile:=public.community_public_profile(current_setting('presentation.a')::uuid);
 if profile->>'user_id'<>current_setting('presentation.a') or (profile->>'comment_count')::integer<>23
  or (profile->>'rating_count')::integer<>1 or profile->>'bio'<>'نبذة عامة' then raise exception 'PUBLIC_REAL_PROFILE'; end if;
 if profile ?| array['email','total_xp','xp','auth','access_token','refresh_token','source_id','source_url','library','history'] then raise exception 'PRIVATE_PROFILE_PAYLOAD'; end if;
 if public.community_public_profile(gen_random_uuid()) is not null then raise exception 'MISSING_PROFILE'; end if;
 page:=public.community_comments_popular_page('manga',repeat('e',64));
 if jsonb_array_length(page->'items')<>20 or not (page->>'has_more')::boolean
  or page->'items'->0->>'id'<>current_setting('presentation.top') then raise exception 'GLOBAL_POPULAR_ORDER'; end if;
 c:=page->'next_cursor';
 next_page:=public.community_comments_popular_page('manga',repeat('e',64),null,(c->>'created_at')::timestamptz,(c->>'id')::uuid,null,20,(c->>'like_count')::bigint);
 if jsonb_array_length(next_page->'items')<>3 or (next_page->>'has_more')::boolean then raise exception 'KEYSET_REMAINDER'; end if;
 if exists(select 1 from jsonb_array_elements(page->'items') a join jsonb_array_elements(next_page->'items') b on a->>'id'=b->>'id') then raise exception 'DUPLICATE_PAGE'; end if;
 if exists(select 1 from jsonb_array_elements(page->'items') i where i ?| array['email','total_xp','xp','access_token','source_url']) then raise exception 'PRIVATE_COMMENT_PAYLOAD'; end if;
 begin perform public.community_comments_popular_page('manga',repeat('e',64),null,null,null,null,21); raise exception 'UNBOUNDED_PAGE'; exception when invalid_parameter_value then null; end;
 begin perform public.community_comments_popular_page('manga',repeat('e',64),null,now(),gen_random_uuid()); raise exception 'INVALID_CURSOR'; exception when invalid_parameter_value then null; end;
 begin update public.profiles set bio='Intrusion'; raise exception 'PUBLIC_WRITE'; exception when insufficient_privilege then null; end;
 begin perform 1 from public.cloud_library_entries; raise exception 'PRIVATE_CLOUD'; exception when insufficient_privilege then null; end;
end $$;
reset role;
do $$ begin
 if exists(select 1 from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname='public' and p.proname in('community_public_profile','community_comments_popular_page') and p.prosecdef) then raise exception 'READ_RPC_BYPASSES_RLS'; end if;
end $$;
rollback;
select 'PASS: public profile privacy, live counts, global like order, 20-row keyset pages, deduplication, validation, RLS; all fixtures rolled back' as result;
