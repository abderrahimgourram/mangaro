-- PROPOSAL ONLY. NOT APPLIED TO PRODUCTION.
-- Requires a recoverable backup, disposable PostgreSQL/client/RLS validation,
-- reviewed lock/rollback plan and explicit production authorization.
-- No rows, identities, RLS policies or privileges are rewritten.
begin;
set local lock_timeout = '5s';
set local statement_timeout = '30s';

create or replace function mangaro_private.cloud_novel_url(source text, value text)
returns boolean language sql immutable set search_path = '' as $function$
 select case when source = 'novel.mknov' then coalesce(
 value ~ '^https://mknov[.]com(:443)?/novel/[1-9][0-9]{0,17}(/chapter/[1-9][0-9]{0,17})?$', false)
 else coalesce(char_length(value) between 1 and 4096 and value ~ (
 '^https://([a-zA-Z0-9-]+\.)*' || case source
 when 'novel.kolnovel' then 'kolnovel\.com'
 when 'novel.cenele' then 'cenele\.com'
 when 'novel.sunovels' then 'sunovels\.com'
 when 'novel.seanovel' then 'seanovel\.org'
 else '(?!)' end || '(:443)?(/|$)'),false) end;
$function$;

-- Validate the expanded constraint before removing the old restriction.
alter table public.cloud_novel_state
 add constraint cloud_novel_state_source_id_compatibility_check
 check (source_id in ('novel.kolnovel','novel.cenele','novel.sunovels','novel.seanovel','novel.mknov')) not valid;
alter table public.cloud_novel_state
 validate constraint cloud_novel_state_source_id_compatibility_check;
alter table public.cloud_novel_state drop constraint cloud_novel_state_source_id_check;
alter table public.cloud_novel_state rename constraint cloud_novel_state_source_id_compatibility_check
 to cloud_novel_state_source_id_check;
-- Only the new provider is constrained further; existing-provider bytes/URLs stay valid.
-- Exact routes mirror MknovSource.route; do not accept API URLs, subdomains or foreign chapters.
alter table public.cloud_novel_state drop constraint if exists cloud_novel_state_mknov_work_check;
alter table public.cloud_novel_state add constraint cloud_novel_state_mknov_work_check
 check (source_id <> 'novel.mknov' or (
  substring(edition_id from char_length(source_id)+2) ~ '^https://mknov[.]com(:443)?/novel/[1-9][0-9]{0,17}$'
  and (state->'lastPosition' = 'null'::jsonb or state->'lastPosition'->'chapter'->>'url' ~
   ('^' || replace(substring(edition_id from char_length(source_id)+2),'.','[.]') || '/chapter/[1-9][0-9]{0,17}$'))
 )) not valid;
alter table public.cloud_novel_state validate constraint cloud_novel_state_mknov_work_check;
alter table public.cloud_novel_progress drop constraint if exists cloud_novel_progress_mknov_work_check;
alter table public.cloud_novel_progress add constraint cloud_novel_progress_mknov_work_check
 check (source_id <> 'novel.mknov' or chapter_url ~
  ('^' || replace(substring(edition_id from char_length(source_id)+2),'.','[.]') || '/chapter/[1-9][0-9]{0,17}$')) not valid;
alter table public.cloud_novel_progress validate constraint cloud_novel_progress_mknov_work_check;
commit;
