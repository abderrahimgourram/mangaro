# Phase 3b — automatic cloud synchronization

## Demonstrated defects and corrections

* An explicit enabled=false/decision=made was overwritten by configure(true) on
  restored authentication. The new real coordinator test failed before the fix
  (false expected, true observed), and passes now. Opt-out now persists across
  restore; the existing account controls expose a clear switch. Re-enabling uses
  restoreAccount (no implicit guest import). New-account guest-merge consent remains.
* CloudLocalGateway applied cloud writes through an owned boundary but snapshot
  entry points lacked that boundary. The foreign-account all(B) test under A failed
  before correction. capture/all/knownSnapshot/baseline now use the same token and
  owner checks before reads and after suspension. Foreign hints and late captures
  are rejected. This establishes a missing boundary in isolation; it is not proof
  that production users' data had been transferred between accounts.
* CloudSyncWorker's general failures and auth-initialization timeout could retry
  indefinitely. All failure paths now stop after six retries with existing
  exponential WorkManager backoff; the durable journal is retained for a later
  foreground/manual/periodic attempt. Worker cancellation still propagates.

## Trigger architecture

Existing login/restore, durable local-change hints (15-second coalescing and
20-second delayed one-time work), network-constrained retry, and foreground
triggers are reused. Clean successful foreground snapshots are fresh for five
minutes; pending rows, missing success or clock rollback remain due.

A separate unique per-account periodic request runs at a six-hour interval with
an initial six-hour delay, CONNECTED network, battery-not-low and exponential
30-second backoff. Existing one-time and periodic names remain separate. KEEP
prevents duplicate registration; account change/logout/opt-out cancel both. Existing
sync mutex, account checks before/after RPCs, revision checks, tombstones and durable
acknowledgment prevent overlapping sync mutations. Android may defer periodic
execution; no instantaneous background-sync guarantee. This follows the
[WorkManager request contract](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work).

## Test isolation and verification

The eight known legacy failures reproduced before modifications. AutomaticCloudSync
fixtures installed A then guest; CloudLocalGateway fixtures applied A without
binding A. Gateway fixtures now bind A and restore previous host ownership.
Tests comparing two accounts explicitly bind each account; no production guard was
weakened. The built-in shelf test now seeds real scoped preference keys rather
than relying on chained relaxed mocks. An intermediate replacement incorrectly
assumed InMemoryPreferenceStore.set persists across newly created preference
objects; its actual snapshot-factory contract requires initial values. Correcting
that fixture retained the original cross-account stable-UUID assertion.

Final normal compilation plus 40 app tests: **40 passed, zero failures/skips**,
85.36 seconds. Filters: AutomaticCloudSyncTest, CloudLocalGatewayTest,
CloudSyncSchedulingTest, CloudSyncReconciliationTest, AccountLogoutTest,
NovelCloudMetadataTest and AccountDownloadOwnershipTest. Separately,
`:domain:testDebugUnitTest --tests '*CloudSyncPolicyTest' --tests '*AutomaticCloudBindingTest'`
passed **9/9**. Test results include owner-switch rejection, opt-out restoration,
scheduler constraints/budgets, actual SDK logout, SQLite account preservation,
progress conflicts, explicit reset clocks, tombstones, idempotent work restoration
and guest-merge consent. The entire project suite is not claimed green.

## Preservation and limits

No backend/schema/RLS/credentials, cloud tables, row identities, sync conflict
semantics, novel capability activation, downloaded files or media uploads changed.
No real production accounts used; no production Supabase requests. No ads or
source-adapter changes. WorkManager timing has host builder/policy and coordinator
coverage, not on-device six-hour execution. Real Google login, two actual devices
and OEM background execution remain owner acceptance items.
