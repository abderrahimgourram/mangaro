begin;
-- INSERT ON CONFLICT needs SELECT on conflict columns. Reports intentionally have
-- no client read privileges. Handle only the unique duplicate using an invoker
-- exception block; RLS/ownership/profile violations still propagate unchanged.
create or replace function public.community_report_comment(p_comment_id uuid,p_reason text default null) returns void
language plpgsql security invoker set search_path='' as $$
begin
 insert into public.community_comment_reports(comment_id,reporter_id,reason)
 values(p_comment_id,(select auth.uid()),nullif(btrim(p_reason),''));
exception when unique_violation then
 return;
end;
$$;
revoke all on function public.community_report_comment(uuid,text) from public,anon,authenticated;
grant execute on function public.community_report_comment(uuid,text) to authenticated;
commit;
