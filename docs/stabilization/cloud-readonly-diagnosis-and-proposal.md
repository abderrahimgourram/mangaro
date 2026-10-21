# Native read-only verification update — 2026-10-20 20:30 UTC

The restarted host now exposes **native** `mcp__supabase__*` tools. The configured
project URL was verified as `https://pehsxthjetlltlsqcbfa.supabase.co`;
`current_user = supabase_read_only_user`, `transaction_read_only = on`.
Native `list_tables` and metadata-only SQL succeeded. Prior Apps denials below are
historical and do not describe this connection. No further OAuth was requested.

## Deployed contract findings

- Both novel cloud tables exist with owner-scoped SELECT, INSERT and UPDATE RLS.
  Authenticated SELECT and column-scoped INSERT/UPDATE grants are present.
  Table-level INSERT/UPDATE privilege probes alone return false **by design**;
  column privilege probes confirm identity columns are insert-only, state and
  tombstones can be updated, and revision/timestamps remain server-owned.
- `cloud_apply_change` is security invoker, verifies auth ownership and expected
  revisions, and updates only state/deleted_at for novel rows. Its qualified
  update-predicate expression was tested with constant synthetic text and works.
- `cloud_novel_state_source_id_check` permits only Kolnovel, Cenele, Sunovels and
  SeaNovel. `cloud_novel_url` also lacks Mknov. Current Android supports Mknov:
  this is now a **verified deployed compatibility gap**, not just a repository
  hypothesis. It can reject Mknov sync without implying other providers fail.
- The deployed schema-1 position validator excludes `completed`. The current
  codec deliberately omits it. A restored legacy position cannot establish
  completion; neither chapter number nor XP can safely reconstruct it.
- `profile_own_statistics` returns comments/ratings, not an authoritative
  format-separated chapter count. The completion ledger has hashed chapter/work
  keys but no explicit novel/comic format column. No private ledger rows were
  inspected and no user's historical count was inferred.
- Current URL validation accepts a synthetic Cenele URL. A suspected escaping
  problem was disproven by evaluating the exact constant expression read-only.
  The read-only role cannot invoke the private validator directly (42501); this
  is not evidence of missing authenticated privileges, which were verified.

No production account, email, token, private progress, chapter body or auth log
was read. No production DDL, RLS, data mutation or RPC write was attempted.
Schema inspection does not prove authenticated app roundtrips, token refresh or
all users' sync outcomes. Those still require a controlled nonproduction account
matrix. The immutable metadata-only checks do not award XP.

## Proposed next work (not applied)

The separate [Mknov compatibility proposal](proposals/novel-mknov-compatibility.sql)
only extends the existing validator/source constraint. It leaves RLS, grants,
identities and data unchanged. A read-only expression fixture verifies the host
matrix; **that is not a migration/RLS integration test**. DDL application, lock
behavior and old/new client roundtrips require a disposable PostgreSQL environment
and approval before production execution. Docker is installed locally but the
current user cannot access its socket; no daemon/security configuration was changed.

Completion evidence needs a separately negotiated backward-compatible capability,
explicit unread/reset semantics and tests that old clients cannot erase newer
completion evidence. No speculative completion schema or historical backfill is
included in this proposal. Local known counts and partial-history state remain
truthful. No Android cloud ownership or codec changes are made in this follow-up.

---

The following is the preserved earlier diagnosis, superseded above where noted.

# Novel history: verified local contract and pending production diagnosis

Status (2026-10-20): production schema NOT verified. No production reads of user
records or mutations have succeeded. This is a proposal, not an executable migration.

## Connection distinction

The configured project-scoped MCP uses `pehsxthjetlltlsqcbfa` and
`read_only=true`. The running agent session exposes the Apps Supabase connector,
not the newly configured native MCP server. The Apps operations `get_project`,
`execute_sql` (a metadata-only SELECT), and `list_tables` all returned:

```
MCP error -32600: You do not have permission to perform this action
```

Direct `resources/list` for server `supabase` returned:

```
resources/list failed: unknown MCP server 'supabase'
```

This does not establish that the owner's OAuth setup failed. No further OAuth,
password, API key or service-role credential was requested. Do not bypass the
connector through browser credentials or remove read-only protection.

## What repository evidence establishes

- `NovelReadStatistics` counts explicit completed positions, uniquely by source,
  work URL and chapter identity. Legacy `completed=null` remains unknown. Downloads,
  highest chapter number and reader launches cannot establish completion.
- Account-owned local storage remains the reading source of truth. The profile
  retains confirmed positive local values during incomplete historical sync. The
  numeric display omits `+`; completeness remains separate state and a small status.
- The checked-in schema-1 compatibility SQL supports Kolnovel, Cenele, Sunovels and
  SeaNovel; Mknov is absent. The current Android codec ALREADY supports Mknov
  (the preceding repair added it); it must not be "fixed" a second time. The
  checked-in server/client mismatch is a verified compatibility limitation, not
  proof of the deployed schema or of a particular production account's error.
- Schema-1 positions exclude `completed`. `NovelCloudState.cloudPosition` removes
  that field for compatibility. A restored legacy position therefore cannot
  establish that a chapter was finished. No historical completion is invented.
- Capability failure correctly preserves files and reports a novel-specific error;
  the existing loopback SDK test proves recovery after a later successful probe.
- SDK test fixtures must bind their fake authenticated user's local workspace and
  restore the preceding owner/installation state. A recovered checkout reproduces
  the old order failure with `NovelCloudProductionOutcomeTest` followed by
  `ProductionHardeningTest`. The correction changes tests, not production guards.

## Safe metadata-only diagnosis when the native connection is available

Run only through the authorized read-only connection. Do not query auth user rows,
JWTs, refresh tokens, emails, chapter bodies or private reading records.

```sql
select current_user, current_setting('transaction_read_only') as read_only;
select table_schema, table_name, column_name, data_type, is_nullable
from information_schema.columns
where (table_schema = 'public' and
       (table_name like 'cloud_%' or table_name like '%profile%' or
        table_name like '%completion%'))
   or (table_schema = 'auth' and table_name in ('users','sessions','refresh_tokens'))
order by table_schema, table_name, ordinal_position;
select schemaname, tablename, policyname, roles, cmd, qual, with_check
from pg_policies
where schemaname = 'public' and
      (tablename like 'cloud_%' or tablename like '%profile%')
order by tablename, policyname;
select table_schema, table_name, grantee, privilege_type
from information_schema.role_table_grants
where table_schema = 'public' and table_name like 'cloud_novel_%'
order by table_name, grantee, privilege_type;
select n.nspname, p.proname, p.prosecdef, p.proconfig,
       pg_get_function_identity_arguments(p.oid) as arguments,
       pg_get_functiondef(p.oid) as definition
from pg_proc p join pg_namespace n on n.oid=p.pronamespace
where n.nspname in ('public','mangaro_private') and
      p.proname in ('cloud_novel_capabilities','cloud_novel_position',
                   'cloud_novel_url')
order by n.nspname,p.proname;
```

Compare deployed constraints, grants and capabilities with the Android codec and
actual response classes before diagnosing production RLS or applying any change.
A service-role success would not prove that an authenticated client can sync.

## Proposed compatibility work — not approved for production execution

1. Inspect the actual schema and any already-authorized completion aggregate/event
   API; preserve its existing ownership and privacy semantics. A retained server
   completion event may provide evidence that legacy position records do not.
2. If still needed, add an explicitly negotiated capability to the existing novel
   contract for completion evidence and Mknov support. Keep old clients compatible;
   do not silently send extra schema-1 fields or interpret an unknown server as ready.
3. Preserve exact edition/chapter keys. Future explicit completion can be recorded;
   historical unknowns cannot be backfilled from chapter labels, XP or estimates.
4. Test schema-1 and upgraded clients against a disposable database with synthetic
   accounts: A/B isolation, insert/update/select ownership, guest merge, idempotency,
   stale writes, tombstones, offline retries, and old-client roundtrips that must not
   erase newer completion evidence. Define explicit unread/reset behavior first.
5. Prepare a minimal forward migration only after the deployed contract is known.
   Inventory dependencies, validate on a nonproduction copy, and verify a recoverable
   backup before requesting authorization for production execution. No data rewrite,
   schema operation, policy change or migration was run in this task.

Production sync and historical completeness remain PARTIALLY VERIFIED until the
correct MCP tools are actually callable and the deployed contract is inspected.
