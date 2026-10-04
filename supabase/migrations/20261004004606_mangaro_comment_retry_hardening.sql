begin;
-- Existing server-generated IDs made an ambiguous network retry indistinguishable
-- from a new comment. Permit a client-generated request UUID in the existing PK.
-- Existing owner/profile RLS, body validation and immutable identity remain intact.
-- Retry confirms the existing row; it never updates it or awards XP a second time.
grant insert(id) on public.community_comments to authenticated;
commit;
