-- PROPOSAL ONLY. No production deployment or historical backfill authorized.
-- Separate approval from Mknov compatibility. Reuses the existing private sync rows,
-- revision RPC, RLS and account ownership. No XP functions, tables or triggers touched.
begin;
set local lock_timeout = '5s';
set local statement_timeout = '30s';

-- The schema-1 validator remains byte-identical for compatibility and rollback review.
create or replace function mangaro_private.cloud_novel_position_v2(source text, value jsonb, chapter_url text default null)
returns boolean language sql immutable security invoker set search_path = '' as $function$
 select case
  when value is null or value = 'null'::jsonb then true
  when jsonb_typeof(value) <> 'object' then false
  else (not (value ? 'completed') or value->'completed' = 'null'::jsonb
    or jsonb_typeof(value->'completed') = 'boolean')
   and mangaro_private.cloud_novel_position(source, value - 'completed', chapter_url)
 end;
$function$;
revoke all on function mangaro_private.cloud_novel_position_v2(text,jsonb,text) from public,anon,authenticated;
grant execute on function mangaro_private.cloud_novel_position_v2(text,jsonb,text) to authenticated;

-- Replace ONLY the validator reference inside the two deployed state CHECKs.
-- Fail closed on schema drift rather than substituting a new generalized constraint.
do $block$
declare target text; constraint_name text; definition text;
begin
 foreach target in array array['cloud_novel_state','cloud_novel_progress'] loop
  constraint_name := case target when 'cloud_novel_state' then 'cloud_novel_state_check1'
    else 'cloud_novel_progress_check2' end;
  select pg_get_constraintdef(c.oid) into definition from pg_constraint c
   where c.conrelid = ('public.' || target)::regclass and c.conname = constraint_name;
  if definition is null then raise exception 'Missing expected state CHECK on %',target; end if;
  -- Reapplying this exact proposal is idempotent; no unknown validator is accepted.
  if position('mangaro_private.cloud_novel_position_v2(' in definition) > 0 then continue; end if;
  if position('mangaro_private.cloud_novel_position(' in definition) = 0 then
   raise exception 'Unexpected validator on %',target;
  end if;
  definition := replace(definition,'mangaro_private.cloud_novel_position(', 'mangaro_private.cloud_novel_position_v2(');
  execute format('alter table public.%I add constraint %I %s not valid', target, constraint_name || '_v2', definition);
  execute format('alter table public.%I validate constraint %I', target, constraint_name || '_v2');
  execute format('alter table public.%I drop constraint %I', target, constraint_name);
  execute format('alter table public.%I rename constraint %I to %I', target, constraint_name || '_v2', constraint_name);
 end loop;
end $block$;

-- A schema-1 client omits completion on update. Preserve explicit true from the
-- same immutable source-qualified chapter, including on a tombstone. Never infer
-- completion from paragraph count, work readingStatus, chapter number or XP.
create or replace function mangaro_private.preserve_novel_completion() returns trigger
language plpgsql security invoker set search_path = '' as $function$
begin
 if tg_op = 'UPDATE' and old.state->'position'->'completed' = 'true'::jsonb then
  if new.state->'position' is null or new.state->'position' = 'null'::jsonb then
   new.state := jsonb_set(new.state,'{position}',old.state->'position');
  else
   new.state := jsonb_set(new.state,'{position,completed}','true'::jsonb);
  end if;
 end if;
 return new;
end;
$function$;
revoke all on function mangaro_private.preserve_novel_completion() from public,anon,authenticated;
drop trigger if exists cloud_novel_progress_completion on public.cloud_novel_progress;
create trigger cloud_novel_progress_completion before update on public.cloud_novel_progress
 for each row execute function mangaro_private.preserve_novel_completion();

-- Existing clients still require schema=1 and the same two tables. Additive capability
-- only; a future client must opt in AFTER the server explicitly advertises this value.
create or replace function public.cloud_novel_capabilities() returns jsonb
language sql stable security invoker set search_path = '' as $function$
 select jsonb_build_object('schema',1,'private',true,
  'tables',jsonb_build_array('cloud_novel_state','cloud_novel_progress'),'completionEvidence',1)
 where (select auth.uid()) is not null;
$function$;
revoke all on function public.cloud_novel_capabilities() from public,anon,authenticated;
grant execute on function public.cloud_novel_capabilities() to authenticated;

-- Bounded response, owner-only, existing PK prefix supports the user filter.
-- NO new public-profile endpoint and NO claim of complete historical coverage.
-- Tombstones hide removed progress but retain recoverable completion evidence;
-- stale revisions cannot resurrect it. No physical-delete grant is introduced.
create or replace function public.cloud_own_novel_completion_statistics() returns jsonb
language sql stable security invoker set search_path = '' as $function$
 select jsonb_build_object('confirmedReadChapters',count(distinct (source_id,chapter_url)),
  'historicalComplete',false,'evidenceContract',1)
 from public.cloud_novel_progress
 where user_id = (select auth.uid()) and deleted_at is null
   and state->'position'->'completed' = 'true'::jsonb
 having (select auth.uid()) is not null;
$function$;
revoke all on function public.cloud_own_novel_completion_statistics() from public,anon,authenticated;
grant execute on function public.cloud_own_novel_completion_statistics() to authenticated;
commit;
