# Conditional Sunovels D1 removal plan — no commands executed

Removal is blocked on successful owner physical-device acceptance of the direct
engine, explicit authorization for a production Android rollout, and then a separate
explicit confirmation for infrastructure removal. Never delete D1 automatically.
Supabase, other Workers and all unrelated infrastructure are excluded.

## What has been verified locally

- Direct source/transport replay can browse/search/details/index/read using only
  `sunovels.com`; all gateway hosts are unavailable in the contract.
- Debug source selection prefers direct even if legacy gateway flags are set.
- Bootstrap and incremental public browse/search do not require D1.
- Saved novel/chapter IDs, disk paths, library/progress/bookmark formats and reader
  download contract version are retained.
- Current Release build defaults disable the staging gateway. Release discovery
  activation was not changed, and no Release APK was generated. This is a source
  configuration finding, not confirmation of every previously installed APK.

## Verified useful metadata backup

`sunovels-direct-evidence/accepted-metadata-backup.json.gz` is a read-only export
of the local accepted PUBLIC_SITE generation 4: 1,461 works, catalog evidence,
source summary, stored partial chapter evidence and actual change history. It
contains no policy assertion reference, credentials, private keys, cookies, reader
bodies or source request-budget state. Compressed size: 107,358 bytes.

SHA-256:
`b9af3eed4209178be18e79854307ec0344eacdef53542de4addcdbb3938d486e`.

The export was decompressed and compared to its exported object and every metadata
record passed the existing canonical/field allowlist validation. SQLite quick_check
passed inside a read-only transaction. The seed hashes match the build's packaged
asset. Re-export with `tools/sunovels-direct/export-bootstrap.py` using the private
approved policy path locally; the tool never outputs its contents.

This is useful accepted metadata, **not a new remote D1 backup**. Before any actual
removal, separately export the complete named remote D1 database, all useful chapter
rows/documents/evidence and current Worker configuration into protected storage;
verify schema/counts/hashes and restoration in an isolated local database. Read
quotas and any newer remote generation must be accounted for first.

## Dependency inventory

`sunovels-direct-evidence/dependency-audit.json` gives exact source files and line
numbers, without secrets. Read-only inspection covered Android main/build source,
both repositories' GitHub workflows, source tooling, the Worker package, persistent
local systemd units and transient systemd units. Nineteen matching files were found.

Android compatibility references: `SunovelsGateway.kt`, repository selection,
availability policy, gateway-specific UI status filters and four BuildConfig fields/
Debug Gradle properties in `app/build.gradle.kts`. None is a mandatory direct path.
Retire these after acceptance and upgrade validation; preserve the direct source
and all source IDs. Do not edit the pinned signed PARTIAL source revision 4 as part
of an infrastructure deletion operation.

Source-side references: `sunovels_gateway/cloud_sync.py`, Cloudflare configuration
and package, dedicated discovery ops units, reset-budget verification script and
D1 budget diagnostic tools. No current GitHub workflow matched the D1 publisher;
the existing general source-rule workflows must remain intact. Audit deployed
workflow/default-branch state again before actual removal, since local source is
not proof of the deployed workflow inventory.

Dedicated persistent local units:
`mangaro-sunovels-staging-discovery.timer` and `.service`. The discovery timer was
not listed as scheduled during this read-only check. Dedicated transient units:
`mangaro-sunovels-staging-budget-verify.timer` and `.service`; the timer was scheduled
for 2026-10-14 00:05 UTC. No unit was stopped, disabled, changed or deleted.

Existing isolated Cloudflare targets recorded in local configuration:

- Worker: `mangaro-sunovels-staging`.
- D1: `mangaro-sunovels-staging`, ID `8be8f516-1ec8-43a4-9230-2bce76fb0870`.
- Public gateway: `https://mangaro-sunovels-staging.arabizeai.workers.dev`.

These names identify the later removal scope; they do not authorize mutation.
Before execution, recheck the live binding maps/IDs and confirm no other Worker
uses the database. Never select resources by broad wildcard or account-wide cleanup.

## Precise sequence after acceptance and explicit authorizations

1. Collect owner acceptance results with the gateway blocked, plus offline/library/
   bookmark/progress/download persistence and other-source regression checks.
2. Inventory actual installed Release versions and pinned update behavior. Confirm
   their source operations do not require the staging gateway. If a historical
   client depends on it, keep infrastructure until an approved direct-compatible
   upgrade is available and accepted. Do not promise lossless migration from source
   inspection alone. Keep stable edition/chapter URL identity formats.
3. After production Android rollout is separately approved, enable/review direct
   Release discovery, remove obsolete gateway selection/configuration/UI code,
   and verify the resulting release plan. No D1 fallback may remain mandatory or
   be silently used for search, detail, chapters, readers or downloads. This work
   does not generate or activate that Release.
4. Capture/restore-verify the complete remote D1 export and useful accepted metadata,
   archive Worker/configuration/deployment identity, and record counts/hashes. Keep
   the current metadata-only backup as an independent useful catalog copy.
5. Refresh the audit of both GitHub workflow branches, Worker bindings, local and
   transient schedulers, ingestion credentials and any external client references.
6. Present that concrete evidence and exact target IDs for the owner's explicit
   infrastructure-removal confirmation. Do not infer consent from Debug acceptance.
7. Only after that confirmation, stop/disable the two named Sunovels acquisition/
   budget jobs and any specifically verified Sunovels D1 publisher workflow. Disable
   the isolated gateway with a reversible maintenance state first; monitor accepted
   clients and keep rollback available for an agreed period.
8. After successful isolation and renewed confirmation of the target IDs, remove
   only the Sunovels gateway deployment/binding and named D1 database according to
   the approved plan. Retire only its ingestion credentials, obsolete local scheduler
   units and publisher settings. Retain verified offline backup artifacts.
9. Recheck that Supabase, SeaNovel, translation Workers, InMobi, production reader/
   downloads, account sync and every unrelated Worker/database remain unchanged.

Rollback before deletion: restore the captured isolated gateway configuration and
accepted database state only if needed for legacy clients; keep the accepted direct
engine and stable local IDs. Rollback after deletion requires the protected full SQL
export, re-created isolated resources and explicit authorization. A catalog-only
backup cannot recover unexported remote state.
