begin;
select set_config('showcase95.a',gen_random_uuid()::text,true),set_config('showcase95.b',gen_random_uuid()::text,true);
insert into auth.users(id,raw_app_meta_data,raw_user_meta_data)
select current_setting(k)::uuid,'{"provider":"google"}'::jsonb,'{}'::jsonb from unnest(array['showcase95.a','showcase95.b']) k;
create function pg_temp.showcase95_reject(s text,expected text) returns void language plpgsql as $$
begin begin execute s; exception when others then if sqlstate=expected then return; else raise; end if; end;raise exception 'Expected rejection: %',s;end $$;
select set_config('request.jwt.claim.sub',current_setting('showcase95.a'),true);
set local role authenticated;
do $$ declare p jsonb; begin
 p:=public.community_public_profile(auth.uid());
 if (p->>'showcase_enabled')::boolean or jsonb_array_length(p->'favorites')<>0 then raise exception 'Default privacy'; end if;
 perform public.save_public_showcase(true,(select jsonb_agg(jsonb_build_object('manga_key',lpad(i::text,64,'0'),'title','Work '||i,'featured',i<=3) order by i desc) from generate_series(1,5) i));
 if (select count(*) from public.public_favorites where featured)<>3 then raise exception 'Feature flags missing';end if;
 perform pg_temp.showcase95_reject('select public.save_public_showcase(true,(select jsonb_agg(jsonb_build_object(''manga_key'',lpad(i::text,64,''0''),''title'',''Work'',''featured'',true)) from generate_series(1,4) i))','22023');
 perform pg_temp.showcase95_reject('select public.save_public_showcase(true,(select jsonb_agg(jsonb_build_object(''manga_key'',lpad(i::text,64,''0''),''title'',''Work'')) from generate_series(1,6) i))','22023');
 perform pg_temp.showcase95_reject('select public.save_public_showcase(true,''[{"manga_key":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","title":"Work","featured":"true"}]'')','22023');
 perform pg_temp.showcase95_reject('update public.public_favorites set featured=true','42501');
 perform pg_temp.showcase95_reject('update public.public_favorites set sort_order=19','42501');
 if (select count(*) from public.public_favorites)<>5 then raise exception 'Failed save lost selection';end if;
 p:=public.community_public_profile(auth.uid());
 if p->'favorites'->0->>'manga_key'<>lpad('5',64,'0') or p->'favorites'->4->>'manga_key'<>lpad('1',64,'0') then raise exception 'Order not persisted';end if;
end $$;
reset role;
select set_config('request.jwt.claim.sub',current_setting('showcase95.b'),true);
set local role authenticated;
-- Same-key work on B never changes A: owner identity is derived inside the RPC.
select public.save_public_showcase(true,jsonb_build_array(jsonb_build_object('manga_key',lpad('5',64,'0'),'title','B work','featured',true)));
do $$ begin
 perform pg_temp.showcase95_reject(format('update public.public_showcase_settings set enabled=false where user_id=%L',current_setting('showcase95.a')),'42501');
 perform pg_temp.showcase95_reject('select public.save_public_showcase(true,''[{"manga_key":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","title":"Work","user_id":"00000000-0000-0000-0000-000000000001"}]'')','22023');
 if jsonb_array_length(public.community_public_profile(current_setting('showcase95.a')::uuid)->'favorites')<>5 then raise exception 'Cross owner write';end if;
end $$;
reset role;
select set_config('request.jwt.claim.sub','',true);
set local role anon;
do $$ declare p jsonb;begin
 p:=public.community_public_profile(current_setting('showcase95.a')::uuid);
 if not (p->>'showcase_enabled')::boolean or jsonb_array_length(p->'favorites')<>5 then raise exception 'Published subset missing';end if;
 if p::text~'"(source_id|source_url|source_manga_url|source_chapter_url|email|chapter_key|last_page_index|total_xp|last_read_at)"' then raise exception 'Private data leakage';end if;
 perform pg_temp.showcase95_reject('select public.save_public_showcase(true,''[]'')','42501');
end $$;
reset role;
select set_config('request.jwt.claim.sub',current_setting('showcase95.a'),true);
set local role authenticated;
select public.save_public_showcase(false,jsonb_build_array(jsonb_build_object('manga_key',lpad('1',64,'0'),'title','Retained private','featured',true)));
reset role;
select set_config('request.jwt.claim.sub','',true);
set local role anon;
do $$ declare p jsonb;begin
 p:=public.community_public_profile(current_setting('showcase95.a')::uuid);
 if (p->>'showcase_enabled')::boolean or p->'favorites'<>'[]'::jsonb or p->'chapters_read'<>'null'::jsonb then raise exception 'Disable leak';end if;
 if exists(select 1 from public.public_favorites where user_id=current_setting('showcase95.a')::uuid) then raise exception 'Table visibility leak';end if;
end $$;
reset role;
-- Existing capacity economy remains server authoritative at each milestone.
do $$ declare l integer;cap integer;begin
 foreach l in array array[1,5,10,15,20,25,30] loop
  cap:=case when l>=25 then 20 when l>=15 then 15 when l>=5 then 10 else 5 end;
  update public.user_progression set level=l,total_xp=40*(l-1)+4*(l-1)*(l-2) where user_id=current_setting('showcase95.a')::uuid;
  perform set_config('request.jwt.claim.sub',current_setting('showcase95.a'),true);
  execute 'set local role authenticated';
  perform public.save_public_showcase(true,(select jsonb_agg(jsonb_build_object('manga_key',lpad(i::text,64,'0'),'title','Work')) from generate_series(1,cap) i));
  if cap<20 then perform pg_temp.showcase95_reject(format('select public.save_public_showcase(true,(select jsonb_agg(jsonb_build_object(''manga_key'',lpad(i::text,64,''0''),''title'',''Work'')) from generate_series(1,%s) i))',cap+1),'22023');end if;
  execute 'reset role';
 end loop;
end $$;
select 'PASS: Showcase default privacy, order, 3-feature limit, deployed slot milestones, atomic retry, owner isolation and public field privacy' result;
rollback;
