# Search foundation verification

Status: **PARTIALLY VERIFIED — private owner review; no automatic merge.**

## Recovery and source changes

Private recovery branch: `backup/mangaro-before-search-rework-20261018`, commit
`0c9f95b565b0ae5aca2343430128eaaf6f5eb025`. A fresh GitHub checkout reproduced all
2,251 initial source-tree file checksums, including the 37 previously untracked
files. Private integration base points at the same snapshot; the backup is unchanged.
The public origin was not pushed or reconfigured.

Production integration is in `41f520597fe919b123852559022ac1653f55cc82`.
Both search paths use shared full-context session ownership, bounded raw snapshots,
and typed source-edition work presentation. Warm comic cache resolution runs on
Default rather than the UI thread; no source requests are needed to show its
resolved cards. Existing source transport/cancellation owners remain adapters.
No new identity engine, source endpoints, persistent schema or matching thresholds.

## Verified pipeline defects

* Original ViewModel, replayed on recovery source: two distinct providers with the
  same display name/language collapsed to one sorted-map key. The production
  regression expected IDs 961001 and 961002, observed only 961002. ID tie-breaking
  now retains both, including pagination, chooser routes and cache restoration.
* Active-query suppression previously compared query/filter but not the provider
  context. A new production test switches providers while the first request is
  non-cancellable; only the second provider can publish to the new session.
* Novel screen memory previously keyed query/genre, omitted status/provider
  context, and recreated its epoch independently of retained memory. The retained
  coordinator and full-context snapshot contract replace that ownership split.
* Cache hits previously invoked identity matching synchronously on the UI thread.
  Warm restoration now reprojects raw rows off-thread, publishes only current
  resolved groups, and never borrows cards from a different failed filter context.
  A production cache test blocks SQL and verifies both one-card restoration and
  execution away from the caller thread.

These are established pipeline defects, not proof that every reported phone
pair had the same cause. Missing identity evidence remains a separate limitation.

## Tests and baseline comparison

| Run | Executed | Passed | Failed | Skipped |
|---|---:|---:|---:|---:|
| Original baseline historical search tests | 14 | 10 | 4 | 0 |
| Initial shared primitives | 5 | 5 | 0 | 0 |
| First production integration check | 105 | 105 | 0 | 0 |
| Broad search/preservation run | 364 | 354 | 8 | 2 |
| Final changed-lifecycle production rerun | 42 | 42 | 0 | 0 |
| Baseline logout/download/cloud subset | 23 | 23 | 0 | 0 |
| Baseline AutomaticCloudSync + CloudLocalGateway order probe | 12 | 4 | 8 | 0 |

The broad run's search/identity subset contains 285 tests: 283 passed and two
optional live tests skipped. The last lifecycle run adds one provider-context
regression, and rechecks every affected ViewModel path. Across both final runs:
365 distinct cases, 355 passed, 8 failed, 2 skipped.

The four historical problems are **three** SearchExperienceTest expectations plus
one MangaTimeSearchEvidenceTest expectation, not four current SearchExperience
failures. Positive matching fixtures lacked identities; they now explicitly declare
synthetic shared external identities, with distinct identities for distinct works.
SQL chapter proof is separate. The SQL-only evidence test now requests SQL-only
mode. Original cardinality/count/zero-request assertions remain. All 14 pass.

One intermediate full search run exposed a timing-sensitive MockK fixture: it
stubbed indexSnapshot using the whole sparse Novel object, although enrichment
legitimately updates metadata for the same edition. The mock now matches only the
known source-qualified edition IDs; unknown IDs remain unstubbed. No assertions
were removed and no production change was made to suppress the failure.

### Eight broader failures remain; do not call the complete suite green

All eight are CloudLocalGatewayTest cases throwing LocalAccountScope.Changed.
AutomaticCloudSyncTest binds account A and then commits guest in cleanup. The
CloudLocalGateway fixture calls apply(A) without binding A. It passes without that
preceding scope installation, but correctly hits the ownership guard afterwards.
The original recovery source reproduces the exact eight failures when the two
classes run together. The guard and protected tests are unchanged. Repairing that
protected test-isolation contract is a separate review item; never weaken the
production ownership guard to obtain green tests.

The ordered baseline diagnostic reuses existing Kotlin bytecode from the previously
normally compiled, unchanged recovery checkout using a private init script. It
executes both test classes and all 12 assertions; it does not skip failing tests.
Final feature compilation and lifecycle verification used normal Gradle tasks
without compile/test exclusions. Initial invalid compilation-reuse experiments
are recorded privately and are not counted as passing test evidence.

Compilation passed with warnings. The complete repository suite/Spotless was not
claimed green; private PR checks remain separate acceptance gates.

## Real metadata replay and limits

No new public source crawl or chapter/body/image requests were made. Existing
sanitized observations cover three real queries, 55 source records and reviewed
positive/negative labels. Owner-reviewed original Solo Leveling editions remain
3 -> 1 card; reviewed TBATE editions remain 2 -> 1 (a separately corroborated third
edition remains provisional); Shadow Slave text editions remain 3 -> 1.
Ragnarok, Jasmine side stories and comic/prose differences remain separate.

Whole-query identity-corpus counts are unchanged from the Atlas recovery baseline:
Solo Leveling 25 editions/23 cards, TBATE 16/14, Shadow Slave 14/12. All 55 records
remain. These counts intentionally include unresolved independent editions and
other works. They are not a claim that every apparent duplicate is resolved.
UI format eligibility is applied separately before novel identity presentation.

## Paired host projection microbenchmark

Same inputs, three warmups, five alternating rounds, medians; existing identity
clustering is excluded from this terminal-mapper comparison. The host was also
performing baseline Gradle work, so numbers are not portable phone measurements.

| Records | Previous mapper (ms) | Shared typed projection (ms) |
|---|---:|---:|
| 100 | 1.024 | 1.713 |
| 1000 | 6.720 | 7.835 |
| 5000 | 7.627 | 16.868 |

The shared contract adds validation/routing overhead; no speedup or FPS claim.
Live-heap deltas include both methods and allocations/assertions, depend on GC,
and are neither retained-memory nor peak measurements. Cache tests verify bounded
12-context LRU behavior and expiry. Atlas indexed candidate blocking is retained;
dense provisional buckets still have a complete-link worst case. No universal
linear-time claim and no per-card networking.

## Preservation and owner acceptance

* All 101 recorded protected-file checksums unchanged. Source adapters, novel
  content classifier, release version/signing, advertising, auth/cloud, source
  identities, downloads, library and reader code are unchanged.
* NovelScreens changes are confined to discovery/search memory and projection;
  its complete chapter/details/reader suffix is byte-identical to recovery source.
* No phone/emulator interaction, production backend operations, APK build,
  source-feed publication, website deployment or public-remote push.
* Primary cards keep no provider names/counts; deliberate edition choosers remain.
* Owner should verify both actual screens, Arabic/English searches, long-press
  chooser routes, actual indexed counts, rapid query/filter changes, cached return
  and paging on the phone. No runtime/phone acceptance is asserted.
* Resolve protected cloud test-order debt and required private CI checks before
  considering a merge. Do not merge this draft automatically.
