-- Non-destructive owner/guest/non-owner checks. All users, comments and XP fixtures roll back.
begin;
select set_config('mangaro.spoiler.a',gen_random_uuid()::text,true),set_config('mangaro.spoiler.b',gen_random_uuid()::text,true);
select set_config('mangaro.spoiler.key',encode(gen_random_bytes(32),'hex'),true);
insert into auth.users(id,raw_app_meta_data,raw_user_meta_data)
select current_setting(k)::uuid,'{"provider":"google"}'::jsonb,'{"full_name":"Spoiler test"}'::jsonb
from unnest(array['mangaro.spoiler.a','mangaro.spoiler.b']) k;
update public.profiles set username='test_'||substr(replace(user_id::text,'-',''),1,16)
where user_id in(current_setting('mangaro.spoiler.a')::uuid,current_setting('mangaro.spoiler.b')::uuid);
create function pg_temp.spoiler_denied(statement text) returns void language plpgsql as $$
begin
 begin execute statement; exception when insufficient_privilege then return; end;
 raise exception 'Expected permission denial';
end $$;
select set_config('request.jwt.claim.sub',current_setting('mangaro.spoiler.a'),true);
set local role authenticated;
do $$ declare normal uuid; hidden uuid; reply uuid; before_xp bigint; before_updated timestamptz; begin
 insert into public.community_comments(target_type,manga_key,user_id,body)
 values('manga',current_setting('mangaro.spoiler.key'),auth.uid(),'normal') returning id into normal;
 if (select spoiler from public.community_comments where id=normal) then raise exception 'Wrong default'; end if;
 insert into public.community_comments(target_type,manga_key,user_id,body,spoiler)
 values('manga',current_setting('mangaro.spoiler.key'),auth.uid(),'spoiler body',true) returning id into hidden;
 insert into public.community_comments(target_type,manga_key,user_id,parent_comment_id,body,spoiler)
 values('manga',current_setting('mangaro.spoiler.key'),auth.uid(),normal,'spoiler reply',true) returning id into reply;
 perform set_config('mangaro.spoiler.hidden',hidden::text,true);
 perform set_config('mangaro.spoiler.parent',normal::text,true);
 perform set_config('mangaro.spoiler.reply',reply::text,true);
 select total_xp into before_xp from public.user_progression where user_id=auth.uid();
 select updated_at into before_updated from public.community_comments where id=hidden;
 update public.community_comments set spoiler=false where id=hidden;
 if not exists(select 1 from public.community_comments where id=hidden and not spoiler and updated_at>before_updated and body='spoiler body') then raise exception 'Flag-only edit failed'; end if;
 update public.community_comments set spoiler=true where id=hidden;
 if (select total_xp from public.user_progression where user_id=auth.uid())<>before_xp then raise exception 'Editing awarded XP'; end if;
 perform pg_temp.spoiler_denied(format('update public.community_comments set user_id=%L where id=%L',current_setting('mangaro.spoiler.b'),hidden));
end $$;
reset role;
set local role anon;
do $$ declare newest jsonb; popular jsonb; replies jsonb; next_page jsonb; item jsonb; begin
 newest:=public.community_comments_page('manga',current_setting('mangaro.spoiler.key'));
 popular:=public.community_comments_popular_page('manga',current_setting('mangaro.spoiler.key'));
 replies:=public.community_comments_page('manga',current_setting('mangaro.spoiler.key'),p_parent_id=>current_setting('mangaro.spoiler.parent')::uuid);
 if jsonb_array_length(newest->'items')<>2 or jsonb_array_length(popular->'items')<>2 or jsonb_array_length(replies->'items')<>1 then raise exception 'Spoilers filtered out'; end if;
 if not (replies->'items'->0->>'spoiler')::boolean then raise exception 'Reply flag missing'; end if;
 if not exists(select 1 from jsonb_array_elements(newest->'items') i where i->>'id'=current_setting('mangaro.spoiler.hidden') and (i->>'spoiler')::boolean) then raise exception 'Newest flag missing'; end if;
 if not exists(select 1 from jsonb_array_elements(popular->'items') i where i->>'id'=current_setting('mangaro.spoiler.hidden') and (i->>'spoiler')::boolean) then raise exception 'Popular flag missing'; end if;
 newest:=public.community_comments_page('manga',current_setting('mangaro.spoiler.key'),p_limit=>1);
 next_page:=public.community_comments_page('manga',current_setting('mangaro.spoiler.key'),p_before_created=>(newest->'next_cursor'->>'created_at')::timestamptz,p_before_id=>(newest->'next_cursor'->>'id')::uuid,p_limit=>1);
 if jsonb_array_length(next_page->'items')<>1 or newest->'items'->0->>'id'=next_page->'items'->0->>'id' then raise exception 'Cursor regression'; end if;
 foreach item in array array[popular->'items'->0,replies->'items'->0] loop
  if item ?| array['email','total_xp','source_id','source_url','raw_user_meta_data','access_token','refresh_token'] then raise exception 'Public privacy regression'; end if;
 end loop;
 perform pg_temp.spoiler_denied(format('update public.community_comments set spoiler=false where id=%L',current_setting('mangaro.spoiler.hidden')));
end $$;
reset role;
select set_config('request.jwt.claim.sub',current_setting('mangaro.spoiler.b'),true);
set local role authenticated;
do $$ declare n integer; begin
 update public.community_comments set spoiler=false where id=current_setting('mangaro.spoiler.hidden')::uuid;
 get diagnostics n=row_count;
 if n<>0 then raise exception 'Non-owner edit allowed'; end if;
 if not (select spoiler from public.community_comments where id=current_setting('mangaro.spoiler.hidden')::uuid) then raise exception 'Non-owner changed spoiler'; end if;
end $$;
reset role;
rollback;
