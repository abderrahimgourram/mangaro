-- Rollback fixtures: verifies client request IDs under real roles and unchanged XP rules.
begin;
select set_config('mangaro.retry.a',gen_random_uuid()::text,true),set_config('mangaro.retry.b',gen_random_uuid()::text,true),set_config('mangaro.retry.id',gen_random_uuid()::text,true);
insert into auth.users(id,raw_app_meta_data,raw_user_meta_data)
select current_setting(k)::uuid,'{"provider":"google"}'::jsonb,'{}'::jsonb from unnest(array['mangaro.retry.a','mangaro.retry.b']) k;
update public.profiles set username='r_'||substr(replace(user_id::text,'-',''),1,16) where user_id in(current_setting('mangaro.retry.a')::uuid,current_setting('mangaro.retry.b')::uuid);
create function pg_temp.denied(s text,expected text[] default array['42501']) returns void language plpgsql as $$
begin
 begin execute s; exception when others then if sqlstate=any(expected) then return; end if; raise; end;
 raise exception 'Expected denial: %',s;
end; $$;
select set_config('request.jwt.claim.sub',current_setting('mangaro.retry.a'),true),set_config('request.jwt.claim.role','authenticated',true);
set local role authenticated;
do $$ begin
 insert into public.community_comments(id,target_type,manga_key,user_id,body)
 values(current_setting('mangaro.retry.id')::uuid,'manga',repeat('a',64),auth.uid(),'Retry-safe comment');
 perform pg_temp.denied(format('insert into public.community_comments(id,target_type,manga_key,user_id,body) values(%L,''manga'',%L,%L,''Retry-safe comment'')',current_setting('mangaro.retry.id'),repeat('a',64),auth.uid()),array['23505']);
 if (select count(*) from public.community_comments where id=current_setting('mangaro.retry.id')::uuid and user_id=auth.uid() and body='Retry-safe comment')<>1 then raise exception 'RETRY_CONFIRMATION_FAILED'; end if;
 perform pg_temp.denied(format('update public.community_comments set id=gen_random_uuid() where id=%L',current_setting('mangaro.retry.id')));
 perform public.community_set_rating('manga',repeat('a',64),null,4::smallint);
 perform public.community_set_rating('manga',repeat('a',64),null,5::smallint);
 perform public.community_like_comment(current_setting('mangaro.retry.id')::uuid,true);
 perform public.community_like_comment(current_setting('mangaro.retry.id')::uuid,true);
end $$;
reset role;
do $$ begin
 if (select total_xp from public.user_progression where user_id=current_setting('mangaro.retry.a')::uuid)<>12 then raise exception 'DUPLICATE_XP_OR_RATING_LIKE_REWARD'; end if;
 if (select count(*) from public.xp_events where user_id=current_setting('mangaro.retry.a')::uuid)<>1 then raise exception 'DUPLICATE_EVENT'; end if;
end $$;
select set_config('request.jwt.claim.sub',current_setting('mangaro.retry.b'),true);
set local role authenticated;
do $$ begin
 perform pg_temp.denied(format('insert into public.community_comments(id,target_type,manga_key,user_id,body) values(%L,''manga'',%L,%L,''Impersonation'')',gen_random_uuid(),repeat('a',64),current_setting('mangaro.retry.a')));
 perform pg_temp.denied(format('insert into public.community_comments(id,target_type,manga_key,user_id,body) values(%L,''manga'',%L,%L,''Overwrite'')',current_setting('mangaro.retry.id'),repeat('a',64),auth.uid()),array['23505']);
 if (select body from public.community_comments where id=current_setting('mangaro.retry.id')::uuid)<>'Retry-safe comment' then raise exception 'OTHER_USER_OVERWROTE'; end if;
end $$;
reset role;
set local role anon;
do $$ begin
 perform pg_temp.denied(format('insert into public.community_comments(id,target_type,manga_key,user_id,body) values(%L,''manga'',%L,%L,''Guest'')',gen_random_uuid(),repeat('a',64),current_setting('mangaro.retry.a')));
end $$;
reset role;
rollback;
