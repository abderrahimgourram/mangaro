begin;
-- Keep privileged implementations outside the exposed API schema. Public wrappers
-- remain SECURITY INVOKER, with the same safe signatures and explicit grants.
alter function public.claim_chapter_completion(text,text) set schema mangaro_private;
alter function public.community_author_levels(uuid[]) set schema mangaro_private;
grant usage on schema mangaro_private to anon,authenticated;
-- Only these two narrow implementations are executable; generic award/revoke/change
-- functions and triggers retain their revoked EXECUTE privileges.
create function public.claim_chapter_completion(p_manga_key text,p_chapter_key text) returns jsonb
language sql security invoker set search_path='' as $$
 select mangaro_private.claim_chapter_completion(p_manga_key,p_chapter_key);
$$;
revoke all on function public.claim_chapter_completion(text,text) from public,anon,authenticated;
grant execute on function public.claim_chapter_completion(text,text) to authenticated;
create function public.community_author_levels(p_user_ids uuid[]) returns table(user_id uuid,level smallint)
language sql stable security invoker set search_path='' as $$
 select * from mangaro_private.community_author_levels(p_user_ids);
$$;
revoke all on function public.community_author_levels(uuid[]) from public,anon,authenticated;
grant execute on function public.community_author_levels(uuid[]) to anon,authenticated;
commit;
