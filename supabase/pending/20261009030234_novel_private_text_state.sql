-- PENDING REVIEW / NOT DEPLOYED. Do not move into migrations or enable a client until approved.
-- Reuses cloud_stamp, cloud_apply_change, cloud_pull_changes and expected-revision conflicts.
-- Private metadata only; no story text, XP, public profile data or account-table changes.
begin;
create table public.cloud_novel_state (
 user_id uuid not null references auth.users(id) on delete cascade,
 edition_key text not null check(edition_key ~ '^[a-f0-9]{64}$'),
 source_id text not null check(source_id in ('novel.kolnovel','novel.cenele','novel.sunovels','novel.seanovel')),
 edition_id text not null check(char_length(edition_id) between 1 and 4096 and left(edition_id,char_length(source_id)+1)=source_id||':'),
 state jsonb not null check(jsonb_typeof(state)='object' and octet_length(state::text)<=262144
   and state - array['saved','favorite','readingStatus','bookmarks','positions','lastPosition','completedChapters','title','cover','addedAt']::text[] = '{}'::jsonb),
 updated_at timestamptz not null default now(), deleted_at timestamptz, revision bigint not null default 0,
 primary key(user_id,edition_key), unique(user_id,source_id,edition_id)
);
alter table public.cloud_novel_state enable row level security;
revoke all on public.cloud_novel_state from public,anon,authenticated;
grant select on public.cloud_novel_state to authenticated;
grant insert(user_id,edition_key,source_id,edition_id,state,deleted_at),update(source_id,edition_id,state,deleted_at)
 on public.cloud_novel_state to authenticated;
create policy cloud_novel_state_read on public.cloud_novel_state for select to authenticated using(user_id=(select auth.uid()));
create policy cloud_novel_state_insert on public.cloud_novel_state for insert to authenticated with check(user_id=(select auth.uid()));
create policy cloud_novel_state_update on public.cloud_novel_state for update to authenticated using(user_id=(select auth.uid())) with check(user_id=(select auth.uid()));
create index cloud_novel_state_revision_idx on public.cloud_novel_state(user_id,revision);
create trigger cloud_novel_state_stamp before insert or update on public.cloud_novel_state
 for each row execute function mangaro_private.cloud_stamp();
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
 when 'cloud_library_entries' then columns:='manga_key,source_id,source_manga_url,title_snapshot,thumbnail_url_snapshot,added_at,deleted_at'; keys:='manga_key';
 when 'cloud_library_collections' then columns:='id,name,sort_order,deleted_at'; keys:='id';
 when 'cloud_library_entry_collections' then columns:='manga_key,collection_id,deleted_at'; keys:='manga_key,collection_id';
 when 'cloud_manga_history' then columns:='manga_key,source_id,source_manga_url,title_snapshot,thumbnail_url_snapshot,last_chapter_key,last_chapter_source_url,last_page_index,last_total_pages,last_read_at,deleted_at'; keys:='manga_key';
 when 'cloud_chapter_progress' then columns:='manga_key,chapter_key,source_chapter_url,chapter_name_snapshot,chapter_number_snapshot,last_page_index,total_pages,is_read,completed_at,state_changed_at,deleted_at'; keys:='manga_key,chapter_key';
 else raise exception 'Invalid table' using errcode='22023'; end case;
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
  select string_agg(format('%I=r.%I',c,c),',') into assignments from unnest(string_to_array(columns,',')) c where not c=any(string_to_array(keys,','));
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
 if p_table not in ('cloud_novel_state','cloud_library_entries','cloud_library_collections','cloud_library_entry_collections','cloud_manga_history','cloud_chapter_progress') or p_after<0 or p_limit not between 1 and 200 then
  raise exception 'Invalid pull' using errcode='22023'; end if;
 perform pg_advisory_xact_lock(hashtextextended(uid::text,73419));
 execute format('select coalesce(jsonb_agg(to_jsonb(q) order by revision), ''[]''::jsonb) from (select * from public.%I where user_id=$1 and revision>$2 order by revision limit $3) q',p_table)
 into result using uid,p_after,p_limit;
 return result;
end; $$;
revoke all on function public.cloud_pull_changes(text,bigint,integer) from public,anon,authenticated;
grant execute on function public.cloud_pull_changes(text,bigint,integer) to authenticated;
commit;
