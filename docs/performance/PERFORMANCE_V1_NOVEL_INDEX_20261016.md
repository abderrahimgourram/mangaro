# Performance V1 and novel-index recovery — 2026-10-16

Current worktree preserved. No advertising implementation/configuration, source identities, database schema, download formats, authentication behavior, production backend or workflows changed in this phase. Existing uncommitted SmartLink V3 work is checkpointed separately, without alteration. Private acceptance build advances code32 to code33 and retains the same existing private build flags.

## Confirmed causes and corrections

- Category grids waited for `awaitAll()` before converting or publishing any source. They now publish each completed source, retain cached/last-good cards, and bound source work to two concurrent jobs. Source initialization is filtered and equivalent registry emissions do not restart requests.
- `DiscoveryChapterGate` performed per-card chapter-index probes on three providers (two permits, eight-second individual deadlines, twenty-second batch deadline). Catalog rendering no longer performs these probes. Unknown and legitimate new zero-chapter works remain discoverable; opening a work/reader still uses existing index/content validation and source-health checks. This is not proof that an indexed chapter is readable/downloadable.
- Grid regrouping ran on the main dispatcher on every health-counter emission. It now runs on Default and only reacts to health-state changes. Metadata conversion invalidates quality once per batch. A bounded eight-entry projection memo (up to 256 editions per entry) skips unchanged linking/diagnostics. Its key includes the currently resolved verified evidence, quality revision, source health, category and original cards: expired/revoked evidence cannot retain a stale grouping.
- Home was already progressive and its reviewed Supabase lookup was already asynchronous/batched. No claim that that lookup gated first cards. Home/Grid source requests now enter the existing Smart Focus admission policy at OPTIONAL; reviewed-identity enrichment uses the new lowest ENRICHMENT tier. Existing reader/index/download priorities, concurrency ceilings and aging remain intact. No new polling, per-card requests, full-index crawls or source matching heuristics.
- Recoverable index errors previously used the generic `NovelFailure` UI, which always offered the website and source public error text. Index failures now show concise in-app retry; an external access explanation is retained for explicit access restrictions. Existing cached rows remain visible throughout indexing.
- One bounded batch retry resumes the existing durable checkpoint for transient transport failures only. Cancellation, parser/identity failures, access denials and Retry-After beyond five seconds are not automatically retried. Existing NovelHttp already permits one transport retry: worst case four GET attempts for the failing non-Sunovels page, not an unbounded loop. Sunovels' separate accepted bounded path is retained. Existing per-edition locks/flights and atomic page persistence are reused.
- Selected editions start first; switching editions renews screen ownership/generation, preserving already verified pages. Completed index TTL shortcuts and source-supported cursors remain unchanged. No advertised total is converted into fabricated entries or completion.

## Evidence and measurements

One LIVE metadata-only GET to `https://seanovel.org/api/novel/shadow-slave` returned HTTP 200, 362,917 bytes, declared count 3190, 3190 rows and 3190 unique IDs (including zero), in 0.437 s from the host. No chapter bodies or other live sources were fetched. The phone's 3217/3219 snapshot was unavailable: its exact origin/gap is **unresolved**, not claimed repaired or complete. A deterministic 3217/3219 fixture verifies retained rows and failure provenance after serialization/restart.

Controlled JVM benchmark, same machine/process, one warm-up pair, five trials per scheduling mode, two synthetic source delays of 20/200 ms, identical two-request budget; median first source publication:

| Pipeline | Before | After |
|---|---:|---:|
| Await-all / progressive publication | 200.421 ms | 20.441 ms |
| Same 64 catalog records, recomputed / memoized (five warm-ups, 30 samples, upper median) | 9.459 ms | 0.608 ms |

These measure host scheduling/linking, not Android first frame, scrolling FPS, cover decoding, first paragraph, memory pressure or phone network latency. No device speedup is claimed. Source/index-body requests per catalog card are now zero. Timing diagnostics retain only 64 operation-name/duration samples; no URLs, titles, accounts, headers or bodies. Existing cover sizing/cache/cancellation and stable canonical card keys remain unchanged.

136 focused JUnit tests passed, 0 failures/errors/skips, 20 suites (app and core/common). Coverage includes injected slow/failing sources, cancellation, bounded concurrency, memo invalidation, cached snapshot deduplication, recorded Kolnovel/SeaNovel indexes, partial checkpoint/restart, genuine zero, transient retry limits, source ownership, download removal/ownership, logout, cloud reconciliation, account isolation and Smart Focus fairness. The fake-auth cloud-sync fixture now explicitly binds its local test owner: previously it depended on test order and failed after the real account-isolation tests installed ownership fencing. No production cloud code changed. 155 direct-source and 43 mobile integration replay contracts also passed (198 total), with zero live/gateway requests; the replay uses recorded responses and failure injection. The first R8 candidate was cancelled after review found that early first-card display could admit pagination before initial cursors were committed. A tested active-job guard now prevents duplicate page-one requests; only the corrected final build is delivered.

## Files changed in this phase

- `app/build.gradle.kts` — private acceptance version33 only; existing advertising flags unchanged.
- `app/src/main/java/eu/kanade/presentation/novels/NovelScreens.kt` — index-specific feedback, retry and selected-edition ownership only; existing download/ad hunks preserved.
- `app/src/main/java/eu/kanade/tachiyomi/novels/{NovelChapterIndexer,NovelIndexRecovery,NovelRepository}.kt` — bounded checkpoint-preserving transient retry/classification.
- `app/src/main/java/eu/kanade/tachiyomi/source/internal/util/DiscoveryChapterGate.kt` — metadata-only catalog eligibility.
- `app/src/main/java/eu/kanade/tachiyomi/ui/home/{DiscoveryCategoryGridViewModel,DiscoverySnapshotStore,GroupDiscoveryItems,HomeViewModel,HomeDiscoveryCache,CatalogWorkEvidence}.kt` — progressive/cache-safe/off-main work and scheduling.
- `app/src/main/java/eu/kanade/tachiyomi/ui/home/{ProgressiveCatalogBatch,CatalogProjectionMemo,CatalogPerformance}.kt` — small bounded helpers/timings.
- `app/src/main/java/eu/kanade/tachiyomi/focus/FocusCoordinator.kt` — append ENRICHMENT priority; existing tiers unchanged.
- `app/src/test/java/eu/kanade/tachiyomi/{ui/home/CatalogPerformanceRecoveryTest,novels/NovelIndexRetryRecoveryTest,focus/CatalogPriorityTest}.kt` — focused deterministic regressions/host benchmark.
- `app/src/test/java/eu/kanade/tachiyomi/data/account/sync/AutomaticCloudSyncTest.kt` — explicit mock-auth workspace setup/cleanup.
- `tools/sunovels-direct/run-contracts.py` — include the indexer's recovery dependency in standalone compilation.
- This document.

No download queue/storage, reader rendering, personal-state schema, signed rules, provider IDs, community schema, cloud authentication or advertisement files were edited in this phase. Previously accumulated source work remains present and is committed separately for recoverability.

## Manual phone acceptance

1. ثبّت النسخة فوق الحالية دون حذف البيانات. افتح الرئيسية ثم الشائع والأحدث والاستكشاف، وجرّب العودة إليها دون شبكة: تظهر النتائج المحفوظة أولًا.
2. افتح «عبد الظل»: الفصول المحفوظة متاحة أثناء الاستكمال. عند الفشل استخدم «إعادة المحاولة» داخل Mangaro. أرسل المصدر والرابط والعدد المعروض إن استمر فرق 3217/3219.
3. افتح فصلًا أثناء الاستكمال، ثم انتقل سريعًا بين الأعمال. لا يظهر محتوى عمل سابق، وتبقى العلامات والتقدم صحيحين.
4. اختبر تنزيلًا قائمًا وقراءة دون إنترنت، ثم A → ضيف → B → A وتسجيل الخروج. لم تُغيّر هذه الأنظمة؛ يبقى اختبار الهاتف ضروريًا.
