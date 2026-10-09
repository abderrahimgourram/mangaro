-- PENDING REVIEW / NOT DEPLOYED. Apply only after explicit approval for the configured Mangaro project.
-- Reuses cloud_stamp, cloud_apply_change, cloud_pull_changes and expected-revision conflicts.
-- Private metadata only; no story text, XP, public profile data or account-table changes.
begin;
-- Target project: pehsxthjetlltlsqcbfa. Review against its LIVE phase-4 schema before approval.
-- No physical deletion grants, public reading data, text payloads or APK activation flag.
create function mangaro_private.cloud_novel_url(source text, value text) returns boolean
language sql immutable security invoker set search_path='' as $$
 select coalesce(char_length(value) between 1 and 4096 and value ~ (
 '^https://([a-zA-Z0-9-]+\.)*' || case source
 when 'novel.kolnovel' then 'kolnovel\.com' when 'novel.cenele' then 'cenele\.com'
 when 'novel.sunovels' then 'sunovels\.com' when 'novel.seanovel' then 'seanovel\.org'
 else '(?!)' end || '(:443)?(/|$)'),false);
$$;
create function mangaro_private.cloud_novel_position(source text, value jsonb, chapter_url text default null) returns boolean
language plpgsql immutable security invoker set search_path='' as $$
begin
 if value is null or value='null'::jsonb then return true; end if;
 if jsonb_typeof(value)<>'object' or value - array['chapter','paragraph','offset','updatedAt','anchor']::text[] <> '{}'::jsonb then return false; end if;
 if jsonb_typeof(value->'chapter') is distinct from 'object' then return false; end if;
 if value->'chapter' - array['url','title','order','volume','volumeId','sourcePage','available']::text[] <> '{}'::jsonb then return false; end if;
 if not mangaro_private.cloud_novel_url(source,value->'chapter'->>'url') or
   (chapter_url is not null and value->'chapter'->>'url' is distinct from chapter_url) then return false; end if;
 if jsonb_typeof(value->'chapter'->'title') is distinct from 'string' or char_length(value->'chapter'->>'title')>512 then return false; end if;
 if jsonb_typeof(value->'chapter'->'order') is distinct from 'number' then return false; end if;
 if (value ? 'paragraph' and jsonb_typeof(value->'paragraph')<>'number') or
   (value ? 'offset' and jsonb_typeof(value->'offset')<>'number') or
   (value ? 'updatedAt' and jsonb_typeof(value->'updatedAt')<>'number') then return false; end if;
 if coalesce(value->'chapter'->>'order','') !~ '^[0-9]{1,7}$' then return false; end if;
 if coalesce(value->>'paragraph','0') !~ '^[0-9]{1,7}$' or coalesce(value->>'offset','0') !~ '^[0-9]{1,8}$' or
   coalesce(value->>'updatedAt','0') !~ '^[0-9]{1,16}$' then return false; end if;
 if (value->'chapter'->>'order')::bigint>1000000 then return false; end if;
 if coalesce((value->>'paragraph')::bigint,0)>1000000 or coalesce((value->>'offset')::bigint,0)>10000000 then return false; end if;
 if value ? 'anchor' and (jsonb_typeof(value->'anchor')<>'string' or char_length(value->>'anchor')>4096) then return false; end if;
 return true;
end; $$;
revoke all on function mangaro_private.cloud_novel_url(text,text),mangaro_private.cloud_novel_position(text,jsonb,text) from public,anon,authenticated;
grant execute on function mangaro_private.cloud_novel_url(text,text),mangaro_private.cloud_novel_position(text,jsonb,text) to authenticated;
create table public.cloud_novel_state (
 user_id uuid not null references auth.users(id) on delete cascade,
 edition_key text not null check(edition_key ~ '^[a-f0-9]{64}$'),
 source_id text not null check(source_id in ('novel.kolnovel','novel.cenele','novel.sunovels','novel.seanovel')),
 edition_id text not null check(left(edition_id,char_length(source_id)+1)=source_id||':'
   and mangaro_private.cloud_novel_url(source_id,substring(edition_id from char_length(source_id)+2))
   and edition_key=encode(sha256(convert_to(edition_id,'UTF8')),'hex')),
 state jsonb not null check(coalesce(jsonb_typeof(state)='object' and octet_length(state::text)<=65536
   and state ?& array['saved','favorite','readingStatus','lastPosition','title','cover','addedAt']::text[]
   and state - array['saved','favorite','readingStatus','lastPosition','title','cover','addedAt']::text[] = '{}'::jsonb
   and jsonb_typeof(state->'saved')='boolean' and jsonb_typeof(state->'favorite')='boolean'
   and state->>'readingStatus' in ('reading','completed','planned')
   and jsonb_typeof(state->'title')='string' and char_length(state->>'title') between 1 and 512
   and (state->'cover'='null'::jsonb or (jsonb_typeof(state->'cover')='string' and char_length(state->>'cover')<=4096))
   and coalesce(state->>'addedAt','') ~ '^[0-9]{1,16}$'
   and mangaro_private.cloud_novel_position(source_id,state->'lastPosition'),false)),
 updated_at timestamptz not null default now(), deleted_at timestamptz, revision bigint not null default 0,
 primary key(user_id,edition_key), unique(user_id,source_id,edition_id)
);
create table public.cloud_novel_progress (
 user_id uuid not null references auth.users(id) on delete cascade,
 edition_key text not null,source_id text not null,edition_id text not null check(
   left(edition_id,char_length(source_id)+1)=source_id||':' and edition_key=encode(sha256(convert_to(edition_id,'UTF8')),'hex')),
 chapter_key text not null check(chapter_key ~ '^[a-f0-9]{64}$'),
 chapter_url text not null check(mangaro_private.cloud_novel_url(source_id,chapter_url)
   and chapter_key=encode(sha256(convert_to(chapter_url,'UTF8')),'hex')),
 state jsonb not null check(coalesce(jsonb_typeof(state)='object' and octet_length(state::text)<=65536
   and state ?& array['position','bookmarked']::text[]
   and state - array['position','bookmarked']::text[] = '{}'::jsonb
   and jsonb_typeof(state->'bookmarked')='boolean'
   and mangaro_private.cloud_novel_position(source_id,state->'position',chapter_url),false)),
 updated_at timestamptz not null default now(),deleted_at timestamptz,revision bigint not null default 0,
 primary key(user_id,edition_key,chapter_key),
 foreign key(user_id,source_id,edition_id) references public.cloud_novel_state(user_id,source_id,edition_id),
 foreign key(user_id,edition_key) references public.cloud_novel_state(user_id,edition_key)
);
alter table public.cloud_novel_state enable row level security;
alter table public.cloud_novel_progress enable row level security;
revoke all on public.cloud_novel_state,public.cloud_novel_progress from public,anon,authenticated;
grant select on public.cloud_novel_state,public.cloud_novel_progress to authenticated;
grant insert(user_id,edition_key,source_id,edition_id,state,deleted_at),update(state,deleted_at)
 on public.cloud_novel_state to authenticated;
grant insert(user_id,edition_key,source_id,edition_id,chapter_key,chapter_url,state,deleted_at),update(state,deleted_at)
 on public.cloud_novel_progress to authenticated;
create policy cloud_novel_state_read on public.cloud_novel_state for select to authenticated using(user_id=(select auth.uid()));
create policy cloud_novel_state_insert on public.cloud_novel_state for insert to authenticated with check(user_id=(select auth.uid()));
create policy cloud_novel_state_update on public.cloud_novel_state for update to authenticated using(user_id=(select auth.uid())) with check(user_id=(select auth.uid()));
create policy cloud_novel_progress_read on public.cloud_novel_progress for select to authenticated using(user_id=(select auth.uid()));
create policy cloud_novel_progress_insert on public.cloud_novel_progress for insert to authenticated with check(user_id=(select auth.uid()));
create policy cloud_novel_progress_update on public.cloud_novel_progress for update to authenticated using(user_id=(select auth.uid())) with check(user_id=(select auth.uid()));
create index cloud_novel_state_revision_idx on public.cloud_novel_state(user_id,revision);
create index cloud_novel_progress_revision_idx on public.cloud_novel_progress(user_id,revision);
create trigger cloud_novel_state_stamp before insert or update on public.cloud_novel_state
 for each row execute function mangaro_private.cloud_stamp();
create trigger cloud_novel_progress_stamp before insert or update on public.cloud_novel_progress
 for each row execute function mangaro_private.cloud_stamp();
-- Versioned capability probe keeps old servers/clients functional; the Android worker skips unavailable novel tables.
create function public.cloud_novel_capabilities() returns jsonb
language sql stable security invoker set search_path='' as $$
 select jsonb_build_object('schema',1,'private',true,'tables',jsonb_build_array('cloud_novel_state','cloud_novel_progress'))
 where (select auth.uid()) is not null;
$$;
revoke all on function public.cloud_novel_capabilities() from public,anon,authenticated;
grant execute on function public.cloud_novel_capabilities() to authenticated;
create or replace function public.cloud_apply_change(p_table text,p_row jsonb,p_expected_revision bigint) returns jsonb
language plpgsql security invoker set search_path='' as $$
declare uid uuid:=(select auth.uid()); columns text; keys text; predicate text; existing jsonb; result jsonb; assignments text;
begin
 if uid is null then raise exception 'Authentication required' using errcode='42501'; end if;
 if p_row ? 'user_id' and p_row->>'user_id'<>uid::text then raise exception 'Owner mismatch' using errcode='42501'; end if;
 if p_row ? 'revision' or p_row ? 'updated_at' then raise exception 'Server fields' using errcode='42501'; end if;
 if p_expected_revision is null or p_expected_revision<0 then raise exception 'Invalid baseline' using errcode='22023'; end if;
 case p_table
 when 'cloud_novel_state' then columns:='edition_key,source_id,edition_id,state,deleted_at'; keys:='edition_key';
 when 'cloud_novel_progress' then columns:='edition_key,source_id,edition_id,chapter_key,chapter_url,state,deleted_at'; keys:='edition_key,chapter_key';
 when 'cloud_library_entries' then columns:='manga_key,source_id,source_manga_url,title_snapshot,thumbnail_url_snapshot,added_at,deleted_at'; keys:='manga_key';
 when 'cloud_library_collections' then columns:='id,name,sort_order,deleted_at'; keys:='id';
 when 'cloud_library_entry_collections' then columns:='manga_key,collection_id,deleted_at'; keys:='manga_key,collection_id';
 when 'cloud_manga_history' then columns:='manga_key,source_id,source_manga_url,title_snapshot,thumbnail_url_snapshot,last_chapter_key,last_chapter_source_url,last_page_index,last_total_pages,last_read_at,deleted_at'; keys:='manga_key';
 when 'cloud_chapter_progress' then columns:='manga_key,chapter_key,source_chapter_url,chapter_name_snapshot,chapter_number_snapshot,last_page_index,total_pages,is_read,completed_at,state_changed_at,deleted_at'; keys:='manga_key,chapter_key';
 else raise exception 'Invalid table' using errcode='22023'; end case;
 if p_table in ('cloud_novel_state','cloud_novel_progress') and p_row - (string_to_array(columns,',') || array['user_id']) <> '{}'::jsonb then
  raise exception 'Invalid novel fields' using errcode='22023'; end if;
 perform pg_advisory_xact_lock(hashtextextended(uid::text,73419));
 select string_agg(format('%I::text = $2->>%L',k,k),' and ') into predicate from unnest(string_to_array(keys,',')) k;
 execute format('select to_jsonb(t) from public.%I t where user_id=$1 and %s for update',p_table,predicate) into existing using uid,p_row;
 if (existing is null and p_expected_revision<>0) or (existing is not null and (existing->>'revision')::bigint<>p_expected_revision) then
  return jsonb_build_object('accepted',false,'row',existing);
 end if;
 if existing is null then
  execute format('insert into public.%I(user_id,%s) select user_id,%s from jsonb_populate_record(null::public.%I,$1) returning to_jsonb(%I.*)',p_table,columns,columns,p_table,p_table)
   into result using p_row||jsonb_build_object('user_id',uid);
 else
  select string_agg(format('%I=r.%I',c,c),',') into assignments from unnest(string_to_array(columns,',')) c where not c=any(string_to_array(keys,',')) and (p_table not in ('cloud_novel_state','cloud_novel_progress') or c in ('state','deleted_at'));
  execute format('update public.%I t set %s from jsonb_populate_record(null::public.%I,$2) r where t.user_id=$1 and %s returning to_jsonb(t.*)',p_table,assignments,p_table,
   regexp_replace(predicate,'(^| and )([a-z_]+)::text','\1t.\2::text','g')) into result using uid,p_row;
 end if;
 return jsonb_build_object('accepted',true,'row',result);
end; $$;
revoke all on function public.cloud_apply_change(text,jsonb,bigint) from public,anon,authenticated;
grant execute on function public.cloud_apply_change(text,jsonb,bigint) to authenticated;
create or replace function public.cloud_pull_changes(p_table text,p_after bigint default 0,p_limit integer default 200) returns jsonb
language plpgsql security invoker set search_path='' as $$
declare uid uuid:=(select auth.uid()); result jsonb;
begin
 if uid is null then raise exception 'Authentication required' using errcode='42501'; end if;
 if p_table not in ('cloud_novel_state','cloud_novel_progress','cloud_library_entries','cloud_library_collections','cloud_library_entry_collections','cloud_manga_history','cloud_chapter_progress') or p_after<0 or p_limit not between 1 and 200 then
  raise exception 'Invalid pull' using errcode='22023'; end if;
 perform pg_advisory_xact_lock(hashtextextended(uid::text,73419));
 execute format('select coalesce(jsonb_agg(to_jsonb(q) order by revision), ''[]''::jsonb) from (select * from public.%I where user_id=$1 and revision>$2 order by revision limit $3) q',p_table)
 into result using uid,p_after,p_limit;
 return result;
end; $$;
revoke all on function public.cloud_pull_changes(text,bigint,integer) from public,anon,authenticated;
grant execute on function public.cloud_pull_changes(text,bigint,integer) to authenticated;
commit;
