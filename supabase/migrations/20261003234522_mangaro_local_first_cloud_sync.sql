begin;
create sequence mangaro_private.cloud_sync_revision_seq;
revoke all on sequence mangaro_private.cloud_sync_revision_seq from public,anon,authenticated;
create function mangaro_private.cloud_stamp() returns trigger
language plpgsql security definer set search_path='' as $$
begin
 if tg_op='UPDATE' and new.user_id<>old.user_id then raise exception 'Immutable sync owner' using errcode='42501'; end if;
 -- Serialize per owner through commit. Pull RPC takes the same lock, preventing a
 -- late-committing lower sequence value from being skipped by an incremental cursor.
 perform pg_advisory_xact_lock(hashtextextended(new.user_id::text,73419));
 new.revision:=nextval('mangaro_private.cloud_sync_revision_seq');
 new.updated_at:=clock_timestamp();
 if new.deleted_at is not null then
  if tg_op='INSERT' or old.deleted_at is null then new.deleted_at:=new.updated_at;
  else new.deleted_at:=old.deleted_at; end if;
 end if;
 return new;
end; $$;
revoke all on function mangaro_private.cloud_stamp() from public,anon,authenticated;
create table public.cloud_library_entries(user_id uuid not null references auth.users(id) on delete cascade, updated_at timestamptz not null default now(), deleted_at timestamptz, revision bigint not null default 0,manga_key text not null check(manga_key ~ '^[a-f0-9]{64}$'),source_id bigint not null, source_manga_url text not null check(char_length(source_manga_url) between 1 and 2048), title_snapshot text not null check(char_length(title_snapshot) between 1 and 512), thumbnail_url_snapshot text check(char_length(thumbnail_url_snapshot)<=4096),added_at timestamptz,primary key(user_id,manga_key));
alter table public.cloud_library_entries enable row level security;
revoke all on public.cloud_library_entries from public,anon,authenticated;
grant select on public.cloud_library_entries to authenticated;
grant insert(user_id,manga_key,source_id,source_manga_url,title_snapshot,thumbnail_url_snapshot,added_at,deleted_at),update(source_id,source_manga_url,title_snapshot,thumbnail_url_snapshot,added_at,deleted_at) on public.cloud_library_entries to authenticated;
create policy cloud_library_entries_read on public.cloud_library_entries for select to authenticated using(user_id=(select auth.uid()));
create policy cloud_library_entries_insert on public.cloud_library_entries for insert to authenticated with check(user_id=(select auth.uid()));
create policy cloud_library_entries_update on public.cloud_library_entries for update to authenticated using(user_id=(select auth.uid())) with check(user_id=(select auth.uid()));
create index cloud_library_entries_revision_idx on public.cloud_library_entries(user_id,revision);
create trigger cloud_library_entries_stamp before insert or update on public.cloud_library_entries for each row execute function mangaro_private.cloud_stamp();
create table public.cloud_library_collections(user_id uuid not null references auth.users(id) on delete cascade, updated_at timestamptz not null default now(), deleted_at timestamptz, revision bigint not null default 0,id uuid not null, name text not null check(char_length(name) between 1 and 200),sort_order integer not null,created_at timestamptz not null default now(),primary key(user_id,id));
alter table public.cloud_library_collections enable row level security;
revoke all on public.cloud_library_collections from public,anon,authenticated;
grant select on public.cloud_library_collections to authenticated;
grant insert(user_id,id,name,sort_order,deleted_at),update(name,sort_order,deleted_at) on public.cloud_library_collections to authenticated;
create policy cloud_library_collections_read on public.cloud_library_collections for select to authenticated using(user_id=(select auth.uid()));
create policy cloud_library_collections_insert on public.cloud_library_collections for insert to authenticated with check(user_id=(select auth.uid()));
create policy cloud_library_collections_update on public.cloud_library_collections for update to authenticated using(user_id=(select auth.uid())) with check(user_id=(select auth.uid()));
create index cloud_library_collections_revision_idx on public.cloud_library_collections(user_id,revision);
create trigger cloud_library_collections_stamp before insert or update on public.cloud_library_collections for each row execute function mangaro_private.cloud_stamp();
create table public.cloud_library_entry_collections(user_id uuid not null references auth.users(id) on delete cascade, updated_at timestamptz not null default now(), deleted_at timestamptz, revision bigint not null default 0,manga_key text not null check(manga_key ~ '^[a-f0-9]{64}$'),collection_id uuid not null,primary key(user_id,manga_key,collection_id),foreign key(user_id,manga_key) references public.cloud_library_entries(user_id,manga_key),foreign key(user_id,collection_id) references public.cloud_library_collections(user_id,id));
alter table public.cloud_library_entry_collections enable row level security;
revoke all on public.cloud_library_entry_collections from public,anon,authenticated;
grant select on public.cloud_library_entry_collections to authenticated;
grant insert(user_id,manga_key,collection_id,deleted_at),update(deleted_at) on public.cloud_library_entry_collections to authenticated;
create policy cloud_library_entry_collections_read on public.cloud_library_entry_collections for select to authenticated using(user_id=(select auth.uid()));
create policy cloud_library_entry_collections_insert on public.cloud_library_entry_collections for insert to authenticated with check(user_id=(select auth.uid()));
create policy cloud_library_entry_collections_update on public.cloud_library_entry_collections for update to authenticated using(user_id=(select auth.uid())) with check(user_id=(select auth.uid()));
create index cloud_library_entry_collections_revision_idx on public.cloud_library_entry_collections(user_id,revision);
create trigger cloud_library_entry_collections_stamp before insert or update on public.cloud_library_entry_collections for each row execute function mangaro_private.cloud_stamp();
create table public.cloud_manga_history(user_id uuid not null references auth.users(id) on delete cascade, updated_at timestamptz not null default now(), deleted_at timestamptz, revision bigint not null default 0,manga_key text not null check(manga_key ~ '^[a-f0-9]{64}$'),source_id bigint not null, source_manga_url text not null check(char_length(source_manga_url) between 1 and 2048), title_snapshot text not null check(char_length(title_snapshot) between 1 and 512), thumbnail_url_snapshot text check(char_length(thumbnail_url_snapshot)<=4096),last_chapter_key text check(last_chapter_key ~ '^[a-f0-9]{64}$'),last_chapter_source_url text check(char_length(last_chapter_source_url) between 1 and 2048),last_page_index integer check(last_page_index>=0),last_total_pages integer check(last_total_pages>0),last_read_at timestamptz not null,primary key(user_id,manga_key));
alter table public.cloud_manga_history enable row level security;
revoke all on public.cloud_manga_history from public,anon,authenticated;
grant select on public.cloud_manga_history to authenticated;
grant insert(user_id,manga_key,source_id,source_manga_url,title_snapshot,thumbnail_url_snapshot,last_chapter_key,last_chapter_source_url,last_page_index,last_total_pages,last_read_at,deleted_at),update(source_id,source_manga_url,title_snapshot,thumbnail_url_snapshot,last_chapter_key,last_chapter_source_url,last_page_index,last_total_pages,last_read_at,deleted_at) on public.cloud_manga_history to authenticated;
create policy cloud_manga_history_read on public.cloud_manga_history for select to authenticated using(user_id=(select auth.uid()));
create policy cloud_manga_history_insert on public.cloud_manga_history for insert to authenticated with check(user_id=(select auth.uid()));
create policy cloud_manga_history_update on public.cloud_manga_history for update to authenticated using(user_id=(select auth.uid())) with check(user_id=(select auth.uid()));
create index cloud_manga_history_revision_idx on public.cloud_manga_history(user_id,revision);
create trigger cloud_manga_history_stamp before insert or update on public.cloud_manga_history for each row execute function mangaro_private.cloud_stamp();
create table public.cloud_chapter_progress(user_id uuid not null references auth.users(id) on delete cascade, updated_at timestamptz not null default now(), deleted_at timestamptz, revision bigint not null default 0,manga_key text not null check(manga_key ~ '^[a-f0-9]{64}$'),chapter_key text not null check(chapter_key ~ '^[a-f0-9]{64}$'),source_chapter_url text not null check(char_length(source_chapter_url) between 1 and 2048),chapter_name_snapshot text check(char_length(chapter_name_snapshot)<=512),chapter_number_snapshot double precision,last_page_index integer not null default 0 check(last_page_index>=0),total_pages integer check(total_pages>0),is_read boolean not null default false,completed_at timestamptz,state_changed_at timestamptz not null default now(),primary key(user_id,manga_key,chapter_key));
alter table public.cloud_chapter_progress enable row level security;
revoke all on public.cloud_chapter_progress from public,anon,authenticated;
grant select on public.cloud_chapter_progress to authenticated;
grant insert(user_id,manga_key,chapter_key,source_chapter_url,chapter_name_snapshot,chapter_number_snapshot,last_page_index,total_pages,is_read,completed_at,state_changed_at,deleted_at),update(source_chapter_url,chapter_name_snapshot,chapter_number_snapshot,last_page_index,total_pages,is_read,completed_at,state_changed_at,deleted_at) on public.cloud_chapter_progress to authenticated;
create policy cloud_chapter_progress_read on public.cloud_chapter_progress for select to authenticated using(user_id=(select auth.uid()));
create policy cloud_chapter_progress_insert on public.cloud_chapter_progress for insert to authenticated with check(user_id=(select auth.uid()));
create policy cloud_chapter_progress_update on public.cloud_chapter_progress for update to authenticated using(user_id=(select auth.uid())) with check(user_id=(select auth.uid()));
create index cloud_chapter_progress_revision_idx on public.cloud_chapter_progress(user_id,revision);
create trigger cloud_chapter_progress_stamp before insert or update on public.cloud_chapter_progress for each row execute function mangaro_private.cloud_stamp();
create index cloud_membership_collection_idx on public.cloud_library_entry_collections(user_id,collection_id);
create index cloud_history_read_idx on public.cloud_manga_history(user_id,last_read_at desc);
-- Own data only; identifiers/column lists are selected from a fixed allow-list.
create function public.cloud_apply_change(p_table text,p_row jsonb,p_expected_revision bigint) returns jsonb
language plpgsql security invoker set search_path='' as $$
declare uid uuid:=(select auth.uid()); columns text; keys text; predicate text; existing jsonb; result jsonb; assignments text;
begin
 if uid is null then raise exception 'Authentication required' using errcode='42501'; end if;
 if p_row ? 'user_id' and p_row->>'user_id'<>uid::text then raise exception 'Owner mismatch' using errcode='42501'; end if;
 if p_row ? 'revision' or p_row ? 'updated_at' then raise exception 'Server fields' using errcode='42501'; end if;
 if p_expected_revision is null or p_expected_revision<0 then raise exception 'Invalid baseline' using errcode='22023'; end if;
 case p_table
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
create function public.cloud_pull_changes(p_table text,p_after bigint default 0,p_limit integer default 200) returns jsonb
language plpgsql security invoker set search_path='' as $$
declare uid uuid:=(select auth.uid()); result jsonb;
begin
 if uid is null then raise exception 'Authentication required' using errcode='42501'; end if;
 if p_table not in ('cloud_library_entries','cloud_library_collections','cloud_library_entry_collections','cloud_manga_history','cloud_chapter_progress') or p_after<0 or p_limit not between 1 and 200 then
  raise exception 'Invalid pull' using errcode='22023'; end if;
 perform pg_advisory_xact_lock(hashtextextended(uid::text,73419));
 execute format('select coalesce(jsonb_agg(to_jsonb(q) order by revision), ''[]''::jsonb) from (select * from public.%I where user_id=$1 and revision>$2 order by revision limit $3) q',p_table)
 into result using uid,p_after,p_limit;
 return result;
end; $$;
revoke all on function public.cloud_pull_changes(text,bigint,integer) from public,anon,authenticated;
grant execute on function public.cloud_pull_changes(text,bigint,integer) to authenticated;
commit;
