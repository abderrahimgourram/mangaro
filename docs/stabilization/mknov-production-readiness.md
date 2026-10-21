# Mknov cloud compatibility: deployment review package

Target only: `pehsxthjetlltlsqcbfa`. **Proposal, not deployed.**
Source base: private integration `8d1d46f0ad9de8181c71b45f91da8af67e9dde23`.
Prior independent test commit: `d7c91d65f356177776d80151516dcf43c6b6a2cb`.

## Verified production contract

2026-10-21 native project-scoped MCP returned the correct project URL,
PostgreSQL 17.11, role `supabase_read_only_user`, transaction_read_only=on.
The 15 novel table constraints and both validator function definitions exactly
match `supabase/tests/fixtures/novel_mknov_deployed_contract.json` (not an
assumed schema reconstructed from Android). Only catalog metadata was queried.

`cloud_novel_state.source_id` accepts Kolnovel, Cenele, Sunovels, SeaNovel.
`mangaro_private.cloud_novel_url` has the same four-source contract. Mknov
client records therefore fail server CHECKs. The client already emits stable
`novel.mknov:https://mknov.com/novel/<id>` edition identities and chapter URL
hashes. No identity migration is needed.

RLS uses `user_id=(select auth.uid())` for SELECT, INSERT and UPDATE. Effective
authenticated grants permit SELECT and state/deleted_at updates, not identity
updates or DELETE; anon has none. The existing RPC is SECURITY INVOKER and
uses the owner-scoped advisory lock, expected revisions and existing stamp
trigger. The proposal changes none of those objects, policies or grants.

## Exact proposed change

Review `proposals/novel-mknov-compatibility.sql` and record its SHA-256 at
approval. It expands the source CHECK and URL validator only for Mknov.
Existing four-provider URL behavior is byte-for-byte retained in the ELSE arm.
Compared with the earlier proposal, Mknov is restricted to the actual adapter's
HTTPS canonical numeric work/chapter routes: exact mknov.com, optional :443,
positive IDs of at most 18 digits; no API subdomain, arbitrary paths, query,
fragment, credentials or dot-segments. Additional Mknov-only CHECKs bind the
parent's last position and each chapter row to the edition's actual work URL.
No existing rows, keys, XP, tables, grants or RLS policies are rewritten.

The transaction sets lock_timeout=5s and statement_timeout=30s. These are
**per lock acquisition/per statement**, not a 30-second bound for the entire
transaction. ALTER TABLE obtains ACCESS EXCLUSIVE locks retained until COMMIT;
constraint validation scans tables under those locks. Schedule low traffic;
check active blockers and table-size estimates using metadata first. Abort on
timeout instead of raising it blindly. Rehearsal row volumes are synthetic and
do not establish production lock duration.

## Reproducible verification

`supabase/tests/run_disposable.py` creates two fresh private Unix-socket
clusters, refuses reused directories, strips inherited PG/PGRST environment,
verifies the actual data_directory and stops clusters on every exit. No TCP
PostgreSQL listener, Docker privileges, production URL, password or account is
used. PostgreSQL 17.11 and PostgREST 16.5 downloads are pinned by SHA-256 in
`.github/workflows/novel-cloud-contract.yml`; no production dump is uploaded.

The preserved 49-test suite compares the deployed catalog fixture, exercises
real five-second lock and thirty-second statement timeouts, transaction/mid-DDL
rollback, idempotent application, all providers, RLS A→B→A, revisions,
bookmarks, insert-only identities, tombstones and unsafe URLs.

The additional readiness suite makes an actual custom pg_dump plus password-free
global-role backup, verifies archive contents, applies the proposal and Mknov
writes, restores with `pg_restore --exit-on-error` into the second cluster, and
compares synthetic rows, column definitions, keys, constraints, policies,
effective table/sequence permissions, column ACLs, functions, indexes,
triggers and revision sequence. It reapplies the forward migration to that
restored database. pg_dump can canonicalize an explicit owner-only sequence ACL
to the equivalent default ACL: the check compares actual privileges instead of
treating different serialized representations as different authorization.

Real HTTP PostgREST tests verify signed JWT validation, expired/invalid token
rejection, anon rejection, per-account SELECT, spoofed owner rejection, Mknov
position/bookmark roundtrip, stale/lost-ack revision conflict, duplicate prevention,
wrong-work child AND parent rejection, unsafe canonical URLs and old schema-1
payload updates. Tokens are random, synthetic and not logged. This tests a
real JWT verifier and database RLS, **not GoTrue signup/OAuth/refresh**.

## Client compatibility scope

Current production `NovelCloudState.rows/decode` and schema-1 contracts were
inspected. Existing source payloads remain accepted; source/chapter keys,
paragraph offsets, bookmarks, revisions and tombstones roundtrip unchanged.
No new required fields, endpoints or tables are introduced. Mknov support does
not imply completion synchronization: schema-1 still rejects `completed`.

Static APK inspection found cloud_novel_capabilities/cloud_novel_progress in
actual local 2.5.2/code27, 2.5.3/code46 and 2.5.6/code48 owner-test artifacts.
These are not labeled official historical releases. Their SHA-256 and manifest
versions are in the local evidence inventory. Static string presence does not
prove every binary's network behavior. HTTP fixtures mirror the actual client
contract; this is not full binary-driven old-client acceptance.

## Production backup gate and limitations

Native database read access works. The separate management connector operation
`supabase_get_project(id=pehsxthjetlltlsqcbfa)` returned
`MCP error -32600: You do not have permission to perform this action`.
The native project-scoped tool server exposes no backup listing endpoint.
Consequently this session **cannot verify this project's plan, actual snapshot
IDs, retention, PITR entitlement or recoverable time range**. OAuth success is
not evidence of those permissions. No production backup/restore was performed.

Official backup capabilities vary by plan: do not assume PITR or a recoverable
automated backup from the database version. Review the actual project's backup
dashboard/authorized management API, then document snapshot ID, creation time,
retention, restore target/downtime, and writes at risk. See
https://supabase.com/docs/guides/platform/backups . A logical database dump does
not include Storage object bytes or custom-role passwords. Never commit a real
dump, expose credentials or enable paid features to satisfy this gate.

## Deployment procedure — requires separate explicit approval

1. Confirm target project, exact proposal SHA and the currently reviewed catalog
   snapshot; stop on schema drift. Read-only pre/post queries are in
   `proposals/novel-mknov-verification.sql`.
2. Record and verify an operational production backup and restore procedure.
   Synthetic restore success is necessary evidence, not a production backup.
3. Confirm a low-traffic window, acceptable table lock duration and blockers.
4. Obtain explicit approval for this exact SQL and recovery procedure. A private
   Git merge is not deployment authorization.
5. Only then create the official migration with `supabase migration new`, transfer
   the reviewed bytes without editing and use the approved Supabase migration
   mechanism. Preserve migration-history recording; do not run via app startup.
6. Verify history, validator truth table, exact constraints, unchanged RLS/grants,
   unchanged PK/FK/unique objects, and the owner's scoped synthetic smoke test.
   Do not inspect real reading rows to prove a synthetic write.
7. Verify existing providers, Mknov reads/writes, bookmark/position revisions and
   logout/relogin under the dedicated synthetic test identity. Monitor only
   sanitized error classifications; no chapter text or credentials in logs.

## Recovery

Before COMMIT, any error/timeout rolls the entire DDL transaction back; never
continue a failed transaction. Before any Mknov data exists, a reviewed reverse
DDL can restore the original validator/CHECK (tested). **After Mknov writes,
do not restore the old allowlist or delete those rows.** Keep the expanded
source contract and forward-fix only the defective validator/constraint with a
separately reviewed bounded transaction. Old clients must not truncate newer
records. A database snapshot restore loses writes after its recovery point and
can interrupt service; prefer restoring to a separate recovery environment for
comparison, then a reviewed forward repair. Never overwrite live data merely
to avoid writing a forward recovery.

Deployment status: technical migration/restore/API evidence prepared;
**production backup capability and operational snapshot remain unverified;
production execution NOT authorized or performed**.
