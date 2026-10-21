# PR #4 final merge-gate remediation

**READY BUT BLOCKED BY REQUIRED CHECK. No merge performed.**

Start: `45f63176f39574ea1b341d5e4c5c387f3a60b385`.
Audited formatting commit: `70435e0ef88c8cc99ba546abcc001e729cc84aac`.
Private branch: `fix/mangaro-autonomous-stabilization-20261020`.
PR #4 still targets `feature/mangaro-reader-profile-connected-stories-polish`
(base `828eacf71248cd2e828a4ea6e34ffc10f46e7be0`); earlier stacked drafts
were not batch-merged. Default integration and immutable recovery remain unchanged.

## Formatting plan and result

Only the 14 already-modified PR #4 Kotlin files were selected. No unrelated
repository-wide apply, protected implementation formatting, rule suppression,
ratchet or baseline was introduced. Wildcard imports were expanded to explicit
members of the exact original packages; every prior explicit import was retained.
Whitespace, line wrapping, trailing commas, equivalent condition parentheses and
single-expression branch braces were reviewed. No test names, assertions, values,
URLs, source identities or reader/cloud/XP behavior were changed.

A syntax-tree comparison (using ktlint's configured Kotlin parser) retained operator
nesting and literal contents. It normalized only trivia/import lists/punctuation,
redundant expression parentheses and single-expression branch blocks. All 14 bodies
matched; precedence, string and assertion mutation controls were rejected. Import
owners were checked separately, and actual Kotlin compilation/test execution verified
bindings. Intermediate checks caught missing explicit implicit-delegate/DSL/annotated
class imports, which were restored from their original packages before the final run.
The standalone lint helper was corrected to read bytes using `fromFile`: `fromPath`
joined lines and falsely removed the final newline. No final-newline rule was disabled.

All 14 changed files now pass the original Spotless/ktlint rules. The **original full**
`spotlessCheck --continue --max-workers=2` was run before and after correction:

- Before: **552** files (256 canonical differences plus 297 nonempty lint maps,
  with one overlap), 538.74 seconds, failed.
- After: **538** files (253 canonical differences plus 286 nonempty lint maps,
  with one overlap), 436.98 seconds, failed in the same four Kotlin modules.
- Remaining diagnostics in files changed by PR #4: **zero**.
- Every remaining file is byte-identical to the PR #4 base. This is inherited debt,
  not a new formatting regression introduced by this PR.
- Recovery evidence: 460 files byte-identical with identical formatter settings;
  40 altered files also fail when the recovery contents are checked using exactly
  Spotless8.9.0/ktlint1.8.0/editorconfig; 38 added after recovery but before this PR base.
  The initial 45-file recovery-copy cohort all failed. No immutable source was modified.

See [the complete current file list](pr4-formatting-inventory.md).

## Exact protected conflict

These **13 production advertising files** violate the full check and remain
byte-identical to immutable recovery and the starting working tree:

| File under app/src/main/java/eu/kanade/tachiyomi/data/ads | Finding |
| --- | --- |
| `AdBlockerEvidence.kt` | canonical formatter output differs |
| `AdBlockerNotice.kt` | canonical formatter output differs |
| `AdPageReadiness.kt` | canonical formatter output differs |
| `AdPrivacyUi.kt` | standard:max-line-length at L25 |
| `DownloadAdGate.kt` | standard:max-line-length at L1 |
| `OnceAction.kt` | canonical formatter output differs |
| `ReaderAdPageActivity.kt` | canonical formatter output differs |
| `ReaderAdPageHost.kt` | canonical formatter output differs |
| `ReaderAdPages.kt` | canonical formatter output differs |
| `SmartLinkController.kt` | standard:no-wildcard-imports at L11 |
| `SmartLinkPolicy.kt` | standard:max-line-length at L250 |
| `SmartLinkPrivacy.kt` | canonical formatter output differs |
| `SmartLinkReaderResolution.kt` | canonical formatter output differs |

The other 525 files are inherited non-advertising debt, outside the bounded PR scope.
Even cleaning those cannot satisfy the full gate while these protected bytes must
remain immutable. A specific policy decision is required: authorize separately
reviewed formatting of protected implementations and the remaining legacy cleanup,
or approve an explicitly designed baseline policy. Neither was adopted here.
The original full check remains red; there is no skip or fake-success substitute.

## Security policy and actual evidence

The current PR reports only `Build & Test App`, not a separate required Dependency
Review status. `gh pr checks --required` reports no required checks; base and default
integration branch metadata return `protected=false`. Protection/ruleset endpoints
return the private-plan entitlement403. No branch setting was changed and no policy
was inferred from an inaccessible endpoint. Owner-mandated quality gates still apply,
even though GitHub does not currently enforce a required status on these branches.

Unsupported Dependency Review was an internal workflow step, not a separately
spoofed success status. The existing PR explicitly proposes the supported OSV gate.
CI run39495937909 passed the real OSV steps and then failed full formatting; tests/build
in that CI run were skipped, not green. No paid capability was purchased or disabled.

Fresh local export/scan after the formatting commit: **866 resolved Maven versions,
146 configuration inventories, zero matching known advisories**, 2026-10-20 21:35 UTC.
All queries completed, and export verified no unresolved nodes. The 10 scanner negative/
transport/pagination/CLI tests passed again. No ignores or severity waivers exist.
This covers exported Gradle project/buildscript/included-build graphs, not all native
binaries, SDKs, arbitrary detached tooling resolutions, unpublished forks, malicious
packages or unknown advisories. No-known-advisory is not universal safety.

## Regression evidence

- Fresh app test execution: **1119 cases,1105 passed,14 optional skips,zero failures**.
- Domain/core/common were initially correctly UP-TO-DATE; they were then explicitly
  rerun without changing filters/assertions: **130 passed,zero failures/skips**.
- Combined new execution: **1249 cases,1235 passed,14 skipped,zero failures/errors**.
- Debug Kotlin, reader-scope AndroidTest Kotlin compilation and existing SQLDelight
  debug/release migration verification passed. Instrumentation was compiled, not
  installed/executed; no new emulator/physical visual acceptance is claimed.
- App coverage includes profile/ranks/seals, local/cloud ownership, search/Atlas,
  Connected Stories, novel parsing/sources, offline downloads and all39 ad cases.
- Previous R8/security-stack and five synthetic upgrade runs remain historical
  evidence; R8 was not unnecessarily rebuilt for this formatting-only source diff.
  No build/signing/version/migration configuration changed.

## Separate Mknov validation branch

`test/mangaro-mknov-cloud-compatibility-20261020`:
`d7c91d65f356177776d80151516dcf43c6b6a2cb`, pushed privately.
It inherits the original proposal unchanged, not these formatting commits.

PostgreSQL17.11 (matching the read-only-inspected production server): **49/49** checks
passed. The fixture verifies all15 deployed constraints and both validator bodies;
then exercises current refusal/new acceptance of Mknov, legacy sources/client payloads,
non-bypass owner RLS, A→B→A, grants, identity hashes, revisions/tombstones, unsafe URLs,
actual five-second lock and thirty-second statement expiry, atomic failure/rollback,
repeat application and safe reversal before new Mknov rows.

Once Mknov rows exist, the old constraint cannot validate; that negative rollback
scenario fails safely and preserves the rows. A reviewed forward-compatible recovery
plan is necessary after adoption. The cluster was private Unix-socket-only, used
synthetic claims/data and was stopped afterward. No production DDL/data/RLS changed.
This is SQL contract evidence, not OAuth/PostgREST/old APK acceptance. Historical
completion remains a separate unresolved schema1 contract; no counts were fabricated.
Production still requires backup/restore and API/client/operational checks plus explicit
production authorization. The validation branch was not merged into PR #4.

## Preservation and delivery

All16 previously fingerprinted production advertising files match, including all15
tracked files in the ads implementation directory plus DownloadPageLoader. Advertising
tests were not edited. Readers, source adapters, source/chapter/library identities,
Atlas/search rules, XP thresholds/assets/seals, cloud/auth production ownership and
storage remain unchanged. App ID/version/signing/update/website settings are unchanged.
No APK, production migration, mandatory update, website deployment or public source push.

The report and exact inventory are saved with the private branch. No automatic merge
is permitted while full formatting is red; PR #4 remains draft. There is no merge SHA.

Reviewed formatting files:

- `app/src/main/java/eu/kanade/presentation/account/ProfileReadingStatistics.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/coil/MangaCoverFetcher.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/coil/MangaCoverKeyer.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/stories/VerifiedStoryRelationshipRegistry.kt`
- `app/src/readerScopeAudit/java/eu/kanade/tachiyomi/novels/NovelReaderEndAudit.kt`
- `app/src/test/java/eu/kanade/tachiyomi/data/account/ProfileRefinementPreservationTest.kt`
- `app/src/test/java/eu/kanade/tachiyomi/data/account/sync/AutomaticCloudSyncTest.kt`
- `app/src/test/java/eu/kanade/tachiyomi/data/account/sync/NovelCloudProductionOutcomeTest.kt`
- `app/src/test/java/eu/kanade/tachiyomi/data/integration/ProductionHardeningTest.kt`
- `app/src/test/java/eu/kanade/tachiyomi/data/stories/ConnectedStoriesCoverageTest.kt`
- `app/src/test/java/eu/kanade/tachiyomi/data/stories/ConnectedStoryLiveMetadataTest.kt`
- `app/src/test/java/eu/kanade/tachiyomi/data/stories/ConnectedStoryResolverTest.kt`
- `app/src/test/java/eu/kanade/tachiyomi/data/stories/VerifiedStoryRelationshipRegistryTest.kt`
- `app/src/test/java/eu/kanade/tachiyomi/ui/home/HomePreviewLimitAndHandoffTest.kt`
