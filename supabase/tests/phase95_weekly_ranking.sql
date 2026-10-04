begin;
select set_config('ranking.a',gen_random_uuid()::text,true),set_config('ranking.b',gen_random_uuid()::text,true);
insert into auth.users(id,raw_app_meta_data,raw_user_meta_data)
select current_setting(k)::uuid,'{"provider":"google"}'::jsonb,'{}'::jsonb from unnest(array['ranking.a','ranking.b']) k;
update public.profiles set display_name='Ranking fixture',username='rank_'||substr(replace(user_id::text,'-',''),1,12)
where user_id in (current_setting('ranking.a')::uuid,current_setting('ranking.b')::uuid);
select set_config('request.jwt.claim.sub',current_setting('ranking.a'),true);
set local role authenticated;
insert into public.community_ratings(user_id,target_type,manga_key,rating) values(auth.uid(),'manga',repeat('e',64),5),(auth.uid(),'manga',repeat('f',64),5);
select set_config('request.jwt.claim.sub',current_setting('ranking.b'),true);
insert into public.community_ratings(user_id,target_type,manga_key,rating) values(auth.uid(),'manga',repeat('e',64),4);
update public.community_ratings set rating=5 where user_id=auth.uid() and manga_key=repeat('e',64);
reset role;
select set_config('request.jwt.claim.sub','',true);
set local role anon;
do $$ declare p jsonb;w jsonb; begin
 p:=public.community_weekly_ranking();
 if (p->>'confidence')::numeric<2 then raise exception 'Confidence floor missing';end if;
 if jsonb_array_length(p->'works')>100 then raise exception 'Unbounded results';end if;
 select value into w from jsonb_array_elements(p->'works') where value->>'manga_key'=repeat('e',64);
 if w is null or (w->>'count')::int<>2 or (w->>'average')::numeric<>5 then raise exception 'Rating update duplicated or aggregation incorrect';end if;
 if abs((w->>'score')::numeric-(2*5+(p->>'confidence')::numeric*(p->>'mean')::numeric)/(2+(p->>'confidence')::numeric))>0.00001 then raise exception 'Weighted formula incorrect';end if;
 if exists(select 1 from jsonb_array_elements(p->'works') x where x->>'manga_key'=repeat('f',64)) then raise exception 'Single vote qualifies';end if;
 if exists(select 1 from jsonb_array_elements(p->'works') x,jsonb_object_keys(x) k where k not in ('manga_key','average','count','score')) then raise exception 'Private projection field';end if;
 if p<>public.community_weekly_ranking() then raise exception 'Unstable ranking';end if;
 if has_function_privilege('anon','public.community_weekly_ranking()','execute') is not true then raise exception 'Guest public read unavailable';end if;
 if has_table_privilege('anon','public.cloud_library_entries','select') then raise exception 'Private Library exposed';end if;
 end $$;
reset role;
select set_config('request.jwt.claim.sub',current_setting('ranking.b'),true);
set local role authenticated;
do $$ begin
 if (select count(*) from public.community_ratings where manga_key=repeat('e',64))<>2 then raise exception 'Public rating access regressed';end if;
 update public.community_ratings set rating=1 where user_id=current_setting('ranking.a')::uuid and manga_key=repeat('e',64);
 if found then raise exception 'Cross owner rating changed';end if;
 if exists(select 1 from public.cloud_library_entries where user_id=current_setting('ranking.a')::uuid) then raise exception 'Private Library exposed';end if;
 end $$;
reset role;
rollback;
