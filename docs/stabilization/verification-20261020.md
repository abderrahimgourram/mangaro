# Stabilization verification — 2026-10-20

**PARTIALLY VERIFIED — private merge blocked.** No public release or production
Supabase mutation occurred. The owner phone was not accessed.

Application source / signed QA build commit:
`508a367159ff71254fec2393a77be4dce23f4698`.
The final documentation record does not change compiled application source.

## Completed focused work

- Clean profile read values without plus suffixes. Empty values use a dash;
  historical completeness remains separate. No history is inferred from XP or labels.
- Two wrong Mount Hua provider assets resolve to exact reviewed publisher artwork;
  cache keys agree, custom covers win, registration stays lazy, source IDs are retained.
- Five reviewed Connected Stories relations / fourteen enrolled editions, including
  the new Omniscient Reader pair. Typed publisher evidence and exact routes are used;
  sparse alternate entries and unrelated namesakes remain unlinked.
- Cloud SDK fixtures bind and restore test ownership. No production account guard,
  Auth, cloud sync engine, source parser, reader or XP rule was changed.
- Home snapshot tests verify both distinct unreviewed works and a pre-grouped verified
  work retaining both source routes. No title-only production grouping was introduced.

## Actual checks

| Check | Observed result |
|---|---|
| Focused integration tests | 284 passed, zero failures/skips |
| Final full app tests | 1,119 total: 1,101 passed, four failed, fourteen optional skips |
| Domain tests | 120 passed |
| Local SQLDelight migration checks | Debug and Release passed; not a Supabase check |
| Fresh private GitHub clone | Kotlin compilation + seventeen focused cases passed; source tree clean |
| Existing source monitors | 45 Mknov + 32 SeaNovel tests passed |
| Connected review tooling | Seven passed; queue never auto-approves candidates |
| Native reader | Real end markers and 100% for 62/100/240 cases; 62 restoration passed |
| Native stories | Four metadata-backed pairs navigated both directions; original back stack and data retained |
| Native profile | One rank, developer display30, original fixture XP110/level3; 360dp and ~430dp/font1.3 |
| Corrected cover | Old wrong cache seeded; publisher cache key selected and correct card rendered |
| Live metadata | Solo Leveling and Omniscient Reader adapters passed; Cenele illustration transport passed |
| Bounded source health | 36 public requests; four complete observed indexes; twenty READABLE, two short UNKNOWN accepted/reopened, one corrupted upstream chapter rejected |
| Protected files | 135 unchanged SHA256 comparisons, reader entire file unchanged, 49 startup/storage paths unchanged |

The four app failures are protected advertising tests and reproduce on the detached
pre-task commit. A separate detached checkout of the immutable recovery commit
`0c9f95b565b0ae5aca2343430128eaaf6f5eb025` also ran39 advertising cases:
35 passed and the exact same four failed. Both advertising source/test trees are
byte-identical between recovery and this feature. Tests were not skipped or edited. Two contain stale destination
expectations; two null/cooldown failures remain undiagnosed to production-safety standards.
A separate cloud test-order failure was reproduced on the baseline and repaired without
weakening ownership. Current SearchExperienceTest passes all thirteen cases.

Full `spotlessCheck --continue` failed after13m10s across five tasks. Representative
App Gradle/core-common/data failures reproduce on the baseline. No global formatting,
workflow suppression or protected-file alteration was applied.

## Actual in-place upgrade matrix

Signed optimized ARM64 QA artifact: app.manhwaar.reader, version2.5.6/code48,
32,862,552 bytes. APK SHA256:
`af50ec5535ec6ad58b2674afd8de0068414d7370a194ee5bd35b7468843a9545`.
Original certificate SHA256:
`3e52c0d0f434c8b6e2c0edee9eff0864aa4a0368bac4e007fc0d70900aa302ba`.
ZIP CRC, signature, ABI and packaged five-record registry were verified. Existing
R8/resource shrinking and signing configuration remain intact. This is a private-cache
QA artifact, not a newly published update or a version increment.

Five real historical APK scenarios passed on an offline disposable API34 emulator:

- 2.0.4/code31 → current QA: guest fixture preserved.
- 2.5.2/code27 → current QA: guest fixture preserved.
- 2.5.3/code46 → current QA: synthetic encrypted session/workspace preserved.
- 2.5.5/code47 → current QA: guest fixture preserved.
- Prior2.5.6/code48 → current QA: synthetic encrypted session/workspace preserved.

No reset/uninstall occurred between baseline seeding and in-place update. Library,
history, chapter/bookmark state, novel position/library/download payload hashes and
reader/source preferences were unchanged; working Home and a second restart passed.
Target Home was captured within17.64s at most. ARM64 used the emulator's native bridge;
this is not physical-device or live account acceptance. No historical black-screen
cause was invented or fixed by clearing data. Historical release artifacts were retained.

## Unresolved gates

Required private PR CI fails at Dependency Review before Gradle because the repository
lacks the required feature entitlement/configuration. No security check or protection
was disabled or overridden; no PR was merged. PR4 remains stacked on PR3 so the change
is scoped; the verified private integration branch remains `integration/search-foundation`.

The configured project-scoped native Supabase MCP server is not exposed in this running
tool session. Apps `get_project`, metadata-only `execute_sql` and `list_tables` return
`MCP error -32600: You do not have permission to perform this action`; native
resources/list returns `unknown MCP server 'supabase'`. This does not establish an
OAuth failure. No further credential/OAuth request or production operation was attempted.
See the separate read-only diagnosis and conditional compatibility proposal. Real
schema/RLS/history inspection, production account synchronization and historical
completion completeness remain unverified.

SeaNovel Invincible1979 remains source-corrupted and correctly rejected; catalog-wide
body accuracy is not claimed. Owner physical UI/account/edition acceptance remains
pending. Full local tests, CI security and production sync gates must be resolved before
merging or treating this as a production release.
