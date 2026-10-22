# Guarded novel completion evidence

This change prepares the existing Android sync coordinator for the **separately
approved, currently undeployed** completion proposal. It does not deploy SQL,
backfill historical activity, change XP or introduce another synchronization
service. Mknov compatibility remains a separate production change.

## Existing path and ownership

Genuine reader-end evidence → existing account-scoped novel position files →
`NovelRepository.cloudRows` → `CloudLocalGateway` → existing dirty/pending journal
and revision RPC → `cloud_novel_progress` → existing local restore → existing
source-qualified `novelReadStatistics` projection.

The projection unions evidence by exact source and chapter URL. Cloud and local
counts are never added together. Downloads, chapter order, library readingStatus,
XP and internal reader-page counts are not completion evidence. Existing unknown
historical activity remains unknown; account totals remain nonauthoritative.

## Capability and wire compatibility

Every sync clears its previous in-memory capability and calls the existing
`cloud_novel_capabilities` RPC. Only exact schema=1, private=true, the existing two
tables and numeric completionEvidence=1 authorize the new wire field. Missing,
string, boolean or unknown future versions retain schema-one encoding.

The lease is bound to the authenticated profile, local account generation,
account-session object and fingerprint of the live, unexpired SDK access token.
Neither that token nor its fingerprint is persisted or logged. Logout, account
switch, token/session changes and failed probes invalidate the lease. A changed
session aborts work; the next ordinary sync can negotiate again. Unknown backend
support never enables v2 merely because a local chapter is complete.

The default codec continues to omit completed. A supported sync serializes the
existing durable true/false evidence; absent evidence remains absent. Pending
v2 entries can be sent to a legacy backend after stripping the optional field
from a copy, without erasing the journal or the local evidence. CAS baselines use
the negotiated representation and are rechecked under the existing mutation
lock. Restoration does not invoke completion/XP claim APIs.

## Conflicts and identity

Compatible cursor and bookmark edits merge through the existing three-way
coordinator. True evidence is preserved only for the **same exact chapter URL**.
A previous chapter's completion must never migrate to a new parent lastPosition.
Competing cursor edits still require the existing explicit conflict decision.
Tombstone conflicts do not silently resurrect deleted rows. Novel and comic
identities, storage formats, chapter IDs and XP events remain distinct.

## Verification and rollout boundary

- `NovelCompletionLeaseTest`: strict capability, identity, generation and session
  invalidation (including A→B→A and anonymous states).
- `NovelCompletionWireTest`: legacy representation, durable evidence, exact
  chapter union, stale pointers, independent edits, cross-chapter boundaries and
  tombstones.
- `NovelCompletionSdkTest`: actual Kotlin Auth/Postgrest SDK and production
  coordinator against a private loopback protocol fixture; capability failure,
  old-server fallback, retry after lost acknowledgement, logout and A→B→A. This
  fixture is not production GoTrue or RLS acceptance.
- `supabase/tests/validate_completion_api.py`: real isolated PostgreSQL17.11,
  PostgREST16.5, locally signed JWTs, actual RLS and RPCs. Runs alongside the
  existing 49 compatibility, 32 readiness and 35 completion-contract checks via
  `run_disposable.py`. All data is synthetic; no production connection.

The proposal SQL checksums remain unchanged. Production backup/restore, fresh
schema drift review, lock assessment and **separate exact owner approval** are
still mandatory for each production migration. A private code merge or a test
APK does not deploy or activate completion-v2. No universal historical total or
real production cross-device success is claimed before that deployment and an
authorized smoke test.

## Formatting review

The three modified legacy Kotlin files are fully formatted with the original
Spotless/ktlint1.8 rules and retired from the exact-content baseline. The existing
content-type integration test is also formatted and retired: its three exact-call
assertions now allow whitespace/trailing commas without allowing a different
target or argument (including a negative control). No formatter
configuration or advertising exemptions changed. Larger mechanical diffs are
limited to those files; meaningful code changes are the capability negotiation,
optional wire representation and matching CAS snapshot. Source literals and
operator precedence are preserved; two long diagnostic/index strings use
runtime-equivalent concatenation solely to satisfy the 120-character rule.
