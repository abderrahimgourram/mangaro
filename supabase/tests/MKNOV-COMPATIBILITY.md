# Mknov compatibility — disposable PostgreSQL validation

This is a **separate validation branch**, not an automatic production migration or
part of PR #4's formatting changes. The proposal remains under
`docs/stabilization/proposals/novel-mknov-compatibility.sql`, outside migrations.
No cloud user data or credentials are needed. No Android/network/OAuth acceptance
is implied by PostgreSQL contract tests.

## Reproduce

Use PostgreSQL **17.11**, matching the inspected deployed server. Initialize a new
private cluster root containing `pg-data` and `pg-socket` (mode 0700). Start it with
`listen_addresses=''`, the private socket directory and a dedicated port. Never
point the runner at an existing personal/production database. Minimal Auth roles
are synthetic, non-login, non-superuser and cannot bypass RLS; claims are supplied
only inside this isolated database.

```sh
initdb -D "$test_cluster/pg-data" --no-locale --encoding=UTF8 \
  --auth-local=trust --auth-host=reject
mkdir -m 700 "$test_cluster/pg-socket"
pg_ctl -D "$test_cluster/pg-data" -l "$test_cluster/server.log" \
  -o "-k $test_cluster/pg-socket -h '' -p 55485" -w start
python3 supabase/tests/validate_mknov_compatibility.py \
  --cluster-root "$test_cluster" --port 55485 --output "$test_cluster/results.json"
pg_ctl -D "$test_cluster/pg-data" -m fast -w stop
```

The runner requires an explicit Unix socket, ignores inherited PG environment,
verifies the server's data_directory against that dedicated cluster, creates a
uniquely named test database and retains it for inspection. It loads existing
cloud migrations, verifies all 15 deployed CHECK/PK/FK/unique definitions and the
two URL/position validator bodies against the authorized metadata-only snapshot,
and applies the exact proposed SQL locally. PostgreSQL 18 represents NOT NULL
constraints differently, so the deployed metadata comparison deliberately requires
major version 17 rather than pretending two different catalogs are identical.

Coverage includes existing sources, Mknov source/chapter URLs and identity hashes,
old/new metadata payloads, insert-only identities, actual non-owner RLS rejection,
A→B→A, anonymous/missing-session rejection, owner revisions and tombstones,
unsafe URLs, forbidden content fields, and legacy completion limitations.

The exact five-second lock timeout is exercised with another local connection.
The exact thirty-second statement timeout is exercised by injecting a sleep into
the local migration transaction; both failures must leave prior DDL/data intact.
A forced error after dropping the old constraint must also roll back atomically.
The proposal is rerun to verify repeatability and unchanged RLS/grants. Existing
`novel_cloud_metadata.sql` regression scenarios also run against the new contract.

## Rollback and release boundary

Transaction failure/ROLLBACK is data-preserving. After successful commit, restoring
the prior constraint/validator is safe **only before new Mknov rows exist**; that
path is tested. Once accepted Mknov rows exist, validating the old source allowlist
must fail and roll back without deleting those rows. Production rollback therefore
needs a reviewed forward-compatible plan or an authorized recovery process that
preserves new data. Do not delete rows or disable validation to make rollback pass.

Historical completion is still excluded by schema 1. This source compatibility
extension does not reconstruct read totals, change XP, modify RLS or solve the
separate historical-completion capability contract.

Production application still requires a recoverable backup, current-schema
revalidation, API/old-new Android client acceptance, operational lock planning and
explicit production authorization. Local PASS is not permission to deploy.
