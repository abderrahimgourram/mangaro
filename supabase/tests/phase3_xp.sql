-- Atomic, rolled-back fixtures; auth and RLS are never disabled.
begin;
select set_config('mangaro.xp.a',gen_random_uuid()::text,true),set_config('mangaro.xp.b',gen_random_uuid()::text,true),set_config('mangaro.xp.c',gen_random_uuid()::text,true);
select set_config('mangaro.xp.key',encode(gen_random_bytes(32),'hex'),true);
insert into auth.users(id,raw_app_meta_data,raw_user_meta_data)
select current_setting(k)::uuid,'{"provider":"google"}'::jsonb,'{}'::jsonb from unnest(array['mangaro.xp.a','mangaro.xp.b','mangaro.xp.c']) k;
update public.profiles set username='xp_'||substr(replace(user_id::text,'-',''),1,16)
 where user_id in (current_setting('mangaro.xp.a')::uuid,current_setting('mangaro.xp.b')::uuid);
create function pg_temp.denied(s text,expected text[] default array['42501']) returns void language plpgsql as $$
begin
 begin execute s; exception when others then if sqlstate=any(expected) then return; end if; raise; end;
 raise exception 'Expected denial: %',s;
end;
$$;
do $$ declare l integer; xp bigint; begin
 if (select count(*) from public.user_progression where user_id in (current_setting('mangaro.xp.a')::uuid,current_setting('mangaro.xp.b')::uuid,current_setting('mangaro.xp.c')::uuid))<>3 then raise exception 'SIGNUP_PROGRESSION_MISSING'; end if;
 if public.progression_level(4408)<>30 or public.progression_level(4407)<>29 or public.progression_level(0)<>1 or public.progression_level(-1)<>1 or public.progression_level(999999)<>30 then raise exception 'LEVEL_CLAMP'; end if;
 for l in 2..30 loop
  xp:=40::bigint*(l-1)+4::bigint*(l-1)*(l-2);
  if public.progression_level(xp)<>l or public.progression_level(xp-1)<>l-1 then raise exception 'LEVEL_BOUNDARY %',l; end if;
 end loop;
end $$;
set local role anon;
do $$ begin
 perform * from public.community_author_levels(array[current_setting('mangaro.xp.a')::uuid]);
 perform pg_temp.denied('select total_xp from public.user_progression');
 perform pg_temp.denied('update public.user_progression set total_xp=999999');
 perform pg_temp.denied(format('select public.claim_chapter_completion(%L,%L)',current_setting('mangaro.xp.key'),repeat('a',64)));
end $$;
reset role;
select set_config('request.jwt.claim.sub',current_setting('mangaro.xp.a'),true),set_config('request.jwt.claim.role','authenticated',true);
set local role authenticated;
do $$ declare cid uuid; rep uuid; i integer; claimed jsonb; before bigint; begin
 perform pg_temp.denied('update public.user_progression set total_xp=999999');
 perform pg_temp.denied('update public.user_progression set level=30');
 perform pg_temp.denied(format('insert into public.user_progression(user_id) values(%L)',auth.uid()));
 perform pg_temp.denied('delete from public.user_progression');
 perform pg_temp.denied(format('insert into public.xp_events(user_id,event_type,source_key,xp_amount) values(%L,%L,%L,12)',auth.uid(),'comment_created',gen_random_uuid()));
 perform pg_temp.denied('update public.xp_events set xp_amount=12');
 perform pg_temp.denied('delete from public.xp_events');
 perform pg_temp.denied('update public.reader_chapter_completions set xp_awarded=true');
 perform pg_temp.denied(format('select mangaro_private.award_event(%L,%L,%L)',auth.uid(),'comment_created',gen_random_uuid()));
 perform pg_temp.denied(format('select mangaro_private.revoke_event(%L,%L,%L,%L)',auth.uid(),'comment_created',gen_random_uuid(),'cheat'));
 perform pg_temp.denied(format('select public.claim_chapter_completion(%L,%L)',current_setting('mangaro.xp.key'),'local-chapter-id'),array['22023']);
 perform pg_temp.denied(format('select public.claim_chapter_completion(%L,%L,%L)',current_setting('mangaro.xp.key'),repeat('b',64),current_setting('mangaro.xp.b')),array['42883']);
 insert into public.community_comments(target_type,manga_key,user_id,body) values('manga',current_setting('mangaro.xp.key'),auth.uid(),'first') returning id into cid;
 perform set_config('mangaro.xp.comment',cid::text,true);
 if (select total_xp from public.user_progression where user_id=auth.uid())<>12 then raise exception 'COMMENT_REWARD'; end if;
 insert into public.community_comments(target_type,manga_key,user_id,parent_comment_id,body) values('manga',current_setting('mangaro.xp.key'),auth.uid(),cid,'reply') returning id into rep;
 perform set_config('mangaro.xp.reply',rep::text,true);
 if (select total_xp from public.user_progression where user_id=auth.uid())<>16 then raise exception 'REPLY_REWARD'; end if;
 -- Edits, ratings, reactions, reports cannot award XP.
 update public.community_comments set body='edited' where id=cid;
 perform public.community_set_rating('manga',current_setting('mangaro.xp.key'),null,5::smallint);
 perform public.community_like_comment(cid,true); perform public.community_like_comment(cid,false);
 perform public.community_report_comment(cid);
 if (select total_xp from public.user_progression where user_id=auth.uid())<>16 then raise exception 'UNSUPPORTED_REWARD'; end if;
 for i in 1..7 loop
  insert into public.community_comments(target_type,manga_key,user_id,body) values('manga',current_setting('mangaro.xp.key'),auth.uid(),'comment cap '||i);
  insert into public.community_comments(target_type,manga_key,user_id,parent_comment_id,body) values('manga',current_setting('mangaro.xp.key'),auth.uid(),cid,'reply cap '||i);
 end loop;
 if (select total_xp from public.user_progression where user_id=auth.uid())<>80 then raise exception 'COMMENT_REPLY_CAP'; end if;
 if (select count(*) from public.community_comments where user_id=auth.uid())<>16 then raise exception 'CAPPED_CONTENT_BLOCKED'; end if;
 for i in 1..52 loop
  claimed:=public.claim_chapter_completion(current_setting('mangaro.xp.key'),lpad(to_hex(i),64,'0'));
  if (claimed->>'xp_awarded')::integer<>(case when i<=50 then 2 else 0 end) then raise exception 'CHAPTER_CAP %',i; end if;
 end loop;
 if (select total_xp from public.user_progression where user_id=auth.uid())<>180 then raise exception 'CHAPTER_BALANCE'; end if;
 if (select level from public.community_author_levels(array[auth.uid()]))<>4 then raise exception 'PUBLIC_LEVEL_PROJECTION'; end if;
 if exists(select 1 from jsonb_array_elements(public.community_comments_page('manga',current_setting('mangaro.xp.key'))->'items') c where c->>'level'<>'4' or c ? 'total_xp') then raise exception 'COMMENT_LEVEL_PROJECTION'; end if;
 claimed:=public.claim_chapter_completion(current_setting('mangaro.xp.key'),lpad(to_hex(1),64,'0'));
 if claimed->>'xp_awarded'<>'0' or (claimed->>'new_total_xp')::bigint<>180 then raise exception 'DUPLICATE_COMPLETION'; end if;
 if (select count(*) from public.reader_chapter_completions where user_id=auth.uid())<>52 then raise exception 'COMPLETION_RECORDS'; end if;
 delete from public.community_comments where id=rep;
 if (select total_xp from public.user_progression where user_id=auth.uid())<>176 then raise exception 'REPLY_REVOCATION'; end if;
 delete from public.community_comments where id=rep;
 if (select total_xp from public.user_progression where user_id=auth.uid())<>176 then raise exception 'DOUBLE_REVOKE'; end if;
 insert into public.community_comments(target_type,manga_key,user_id,parent_comment_id,body) values('manga',current_setting('mangaro.xp.key'),auth.uid(),cid,'repost reply');
 if (select total_xp from public.user_progression where user_id=auth.uid())<>176 then raise exception 'DELETE_REPLY_CAP_RESET'; end if;
 delete from public.community_comments where user_id=auth.uid();
 if (select total_xp from public.user_progression where user_id=auth.uid())<>100 then raise exception 'CASCADE_REVOCATION'; end if;
 insert into public.community_comments(target_type,manga_key,user_id,body) values('manga',current_setting('mangaro.xp.key'),auth.uid(),'repost after delete');
 if (select total_xp from public.user_progression where user_id=auth.uid())<>100 then raise exception 'DELETE_COMMENT_CAP_RESET'; end if;
 if (select count(*) from public.xp_events where user_id=auth.uid() and event_type='comment_created' and xp_amount>0)<>5 or
 (select count(*) from public.xp_events where user_id=auth.uid() and event_type='reply_created' and xp_amount>0)<>5 then raise exception 'AUDIT_CAP_ERASED'; end if;
end $$;
reset role;
-- A trusted internal retry must not award the same event; repeating revocation cannot decrement again.
do $$ declare before bigint; uid uuid:=current_setting('mangaro.xp.a')::uuid; begin
 select total_xp into before from public.user_progression where user_id=uid;
 if mangaro_private.award_event(uid,'comment_created',current_setting('mangaro.xp.comment'))<>0 or
 mangaro_private.award_event(uid,'reply_created',current_setting('mangaro.xp.reply'))<>0 then raise exception 'DUPLICATE_EVENT'; end if;
 perform mangaro_private.revoke_event(uid,'comment_created',current_setting('mangaro.xp.comment'),'retry');
 if (select total_xp from public.user_progression where user_id=uid)<>before then raise exception 'REVOKE_RETRY'; end if;
 if exists(select 1 from public.user_progression where total_xp<0 or level not between 1 and 30) then raise exception 'PROGRESSION_INVALID'; end if;
end $$;
select set_config('request.jwt.claim.sub',current_setting('mangaro.xp.b'),true);
set local role authenticated;
do $$ declare cid uuid; n integer; begin
 if exists(select 1 from public.user_progression where user_id=current_setting('mangaro.xp.a')::uuid) then raise exception 'OTHER_TOTAL_XP_EXPOSED'; end if;
 update public.community_comments set body='intruder' where user_id=current_setting('mangaro.xp.a')::uuid;
 get diagnostics n=row_count; if n<>0 then raise exception 'NONOWNER_COMMENT_EDIT'; end if;
 insert into public.community_comments(target_type,manga_key,user_id,body) values('manga',current_setting('mangaro.xp.key'),auth.uid(),'deletion boundary') returning id into cid;
 delete from public.community_comments where id=cid;
 if (select total_xp from public.user_progression where user_id=auth.uid())<>0 or (select level from public.user_progression where user_id=auth.uid())<>1 then raise exception 'ZERO_REVOKE_BOUNDARY'; end if;
 perform public.claim_chapter_completion(current_setting('mangaro.xp.key'),repeat('b',64));
 if (select total_xp from public.user_progression where user_id=auth.uid())<>2 then raise exception 'SECOND_USER_OWN_CLAIM'; end if;
end $$;
reset role;
select set_config('request.jwt.claim.sub',current_setting('mangaro.xp.c'),true);
set local role authenticated;
do $$ begin
 perform pg_temp.denied(format('select public.claim_chapter_completion(%L,%L)',current_setting('mangaro.xp.key'),repeat('c',64)));
end $$;
reset role;
-- Verify capped chapter claims cannot award on a later UTC day, using time-anchored trusted fixtures.
do $$ declare uid uuid:=current_setting('mangaro.xp.b')::uuid; key text; result jsonb; begin
 -- Backfill *test-only* historical awards after the previous UTC midnight for a clean per-day count.
 for i in 1..50 loop
  key:=lpad(to_hex(i+1000),64,'0');
  insert into public.xp_events(user_id,event_type,source_key,xp_amount,created_at)
   values(uid,'chapter_completed',key,2,(date_trunc('day',clock_timestamp() at time zone 'UTC') at time zone 'UTC')-interval '1 hour');
 end loop;
 insert into public.reader_chapter_completions(user_id,manga_key,chapter_key,completed_at,xp_awarded)
 values(uid,current_setting('mangaro.xp.key'),repeat('e',64),clock_timestamp()-interval '24 hours',false);
 insert into public.xp_events(user_id,event_type,source_key,xp_amount,created_at)
 values(uid,'chapter_completed',repeat('e',64),0,clock_timestamp()-interval '24 hours');
 -- Existing ledger/progression balance for these trusted test fixtures is applied through the internal updater.
 perform mangaro_private.change_progression(uid,100);
end $$;
select set_config('request.jwt.claim.sub',current_setting('mangaro.xp.b'),true);
set local role authenticated;
do $$ declare result jsonb; begin
 result:=public.claim_chapter_completion(current_setting('mangaro.xp.key'),repeat('d',64));
 if result->>'xp_awarded'<>'2' then raise exception 'UTC_DAY_BOUNDARY'; end if;
 result:=public.claim_chapter_completion(current_setting('mangaro.xp.key'),repeat('e',64));
 if result->>'xp_awarded'<>'0' then raise exception 'CAPPED_CHAPTER_LATER_AWARD'; end if;
end $$;
reset role;
do $$ begin
 if exists(select 1 from public.user_progression p where p.user_id in (current_setting('mangaro.xp.a')::uuid,current_setting('mangaro.xp.b')::uuid)
  and p.total_xp<>(select coalesce(sum(e.xp_amount),0) from public.xp_events e where e.user_id=p.user_id and e.revoked_at is null)) then raise exception 'LEDGER_BALANCE_MISMATCH'; end if;
 if exists(select 1 from pg_class where oid in ('public.user_progression'::regclass,'public.xp_events'::regclass,'public.reader_chapter_completions'::regclass) and not relrowsecurity) then raise exception 'RLS_DISABLED'; end if;
 if exists(select 1 from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname='mangaro_private' and p.proname not in ('claim_chapter_completion','community_author_levels') and (has_function_privilege('anon',p.oid,'execute') or has_function_privilege('authenticated',p.oid,'execute'))) then raise exception 'INTERNAL_XP_RPC_EXPOSED'; end if;
 if has_function_privilege('anon','public.rls_auto_enable()','execute') then raise exception 'PLATFORM_HARDENING_REGRESSED'; end if;
end $$;
rollback;
select 'PASS: signup/backfill contract, RLS denies, private XP mutations, owner-only totals, rewards 12/4/2, caps 5/5/50, duplicate events/claims, revocation/cascade/repost protection, UTC day boundary, level 1..30 thresholds including 4408, ledger balances; fixtures rolled back' result;
