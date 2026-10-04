begin;
-- Explicit client denial documents the existing default-deny private boundary.
create policy reserved_names_client_denied on mangaro_private.reserved_usernames
for all to anon,authenticated using(false) with check(false);
commit;
