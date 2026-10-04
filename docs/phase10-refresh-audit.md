# Phase 10: source freshness audit

Starting HEAD: `cea39b02f1cda1f6cf070193d51959407692bc6c`.

## Architecture and concrete findings

Details calls `UpdateMangaFromRemote`; Library and Updates refresh start the existing
`LibraryUpdateJob`. The worker groups manga by source, fetches through SourceManager,
then uses the same interactor and `SyncChaptersWithSource`. SQLDelight chapter/library/
update query flows expose persisted changes. WorkUpdateInbox stores local notification
identities. Home previously refreshed catalogues and weekly picks only.

Identified delay/failure paths:

- Manual Library jobs inherited automatic unread/completed/not-started/release-window
  restrictions and ONLY_FETCH_ONCE. A user request could skip the affected manga.
- Manual jobs did not request a source-health recovery probe.
- Details automatic chapter checks used a 30-minute successful-check cooldown.
- Differently configured requests for the same manga could overlap through persistence.
- Legacy HttpSource chapter fetches could use a fresh-enough cached HTTP response.
  The seven built-in chapter adapters already request network-fresh metadata.
- Source reconciliation inserted, updated and removed chapter rows separately;
  insertion errors could be swallowed by the generic addAll path.
- Full-library timestamps advanced at attempt start; source failures and cancellation
  could still report worker success.
- Details discoveries did not publish work-update inbox entries.
- Foregrounding the app did not schedule a chapter freshness check.

## Resulting behavior

Explicit requests bypass automatic manga restrictions and the automatic cooldown,
retain category scope, force the existing source-health probe, and execute real source
fetches. Existing manual unique work is shared; automatic work does not substitute for
an explicit pass. Home refresh also coordinates that existing update job.

Chapter HTTP metadata bypasses local response caching. Page/image caching is unchanged.
Upstream servers/CDNs can still return stale data themselves.

Identical active requests coalesce; all refresh variants for one exact source/manga/URL
serialize fetch-through-persistence. Source additions, metadata edits and removals now
commit in one SQLite transaction; failure propagates and rolls back. Existing identity
migration, incomplete/empty/collapse protections, numeric recognition and ordering remain.

Details re-entry checks the exact manga after a two-minute successful-check cooldown.
App foregrounding schedules at most 12 recent eligible Library works, with a five-minute
attempt/success cooldown. The shared source-group concurrency limit is five. Existing
periodic preferences/constraints remain unchanged (including disabled updates). Foreground
work requires connectivity and allows two exponential retries. Manual failures remain
explicitly retryable and retain successful data from other works. Failed checks never
advance successful Library timestamps; cancellation does not become success.

Confirmed new chapters update the Home/Updates discovery counter and publish through the common inbox path, using existing chapter-ID
notification deduplication. No Reader navigation is triggered by discovery.

## Controlled live verification, 2026-10-04

Public releases endpoint: `https://meshmanga.com/v2/api/v2/series/releases/?page=1`.
Observed *Childhood Friend of the Zenith*, series 1702275, updated
`2026-10-04T14:13:40.728628Z`.

Canonical chapter endpoint:
`https://meshmanga.com/v2/api/v2/chapters/?serie=1702275&order_by=-order&page_size=200`.
HTTP 200, 109 chapters, newest chapter 109 (remote ID 1762933), no continuation.

A temporary controlled JUnit audit invoked the real MangaSwat.getMangaUpdate implementation
with its normal public details/chapter requests. It returned COMPLETE, 109 distinct chapter
identities, newest 109. A second network refresh returned the identical identity sequence.
A second temporary audit used the actual source adapter, UpdateMangaFromRemote,
SyncChaptersWithSource, MangaRepositoryImpl and ChapterRepositoryImpl against a temporary
SQLite database seeded with the 108 older actual chapter records. Manual refresh returned
one new chapter (109), committed a 109-row list and emitted that list through the observed
chapter query. The next manual network refresh inserted zero chapters; publication occurred
exactly once and the successful freshness memo was persisted. No user database was changed.

Both live tests were removed before commit; production regression tests use fixtures/local HTTP.
No adapter/parser/pagination defect was demonstrated in this example, so no adapter changed.
The specific reported missing title was not supplied, so this is a representative same-day
case, not proof about every source website.

SQLite regression tests separately exercise the actual repository and reconciliation:
new chapter commit, observed query update without reopening, exact source ordering,
second-refresh deduplication, saved progress preservation and atomic rollback on failure.
Android UI/WorkManager runtime verification remains manual; no device/emulator was used.

## Scope

No backend/schema/migration, Auth, XP, rank, Showcase, Community, Cloud merge/queue,
Reader rendering, source identity/parser, Actions or publisher changes. No new source
engine, network client architecture or database. SQLite JDBC is test-only and uses the
existing SQLDelight version.

## Verification and changed files

Focused suite: 48 app tests and 55 domain tests (103 total), including eight new tests.
Both additional temporary live audits passed separately. Debug assembly passed. No ADB,
emulator launch or APK installation was performed.

Production files:
- `app/src/main/java/eu/kanade/domain/chapter/interactor/SyncChaptersWithSource.kt`
- `app/src/main/java/eu/kanade/presentation/library/LibraryUpdateRefresh.kt`
- `app/src/main/java/eu/kanade/tachiyomi/App.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/library/LibraryUpdateJob.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/library/SourceRefreshPolicy.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeTab.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreen.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaViewModel.kt`
- `app/src/main/java/mihon/domain/source/interactor/UpdateMangaFromRemote.kt`
- `data/src/main/java/tachiyomi/data/chapter/ChapterRepositoryImpl.kt`
- `domain/src/main/java/tachiyomi/domain/chapter/repository/ChapterRepository.kt`
- `domain/src/main/java/tachiyomi/domain/library/service/LibraryPreferences.kt`
- `source-api/src/main/kotlin/eu/kanade/tachiyomi/source/online/HttpSource.kt`

Tests/dependencies:
- `app/src/test/java/eu/kanade/domain/chapter/interactor/SyncChapterIntegrityTest.kt`
- `app/src/test/java/eu/kanade/presentation/library/LibraryUpdateRefreshTest.kt`
- `app/src/test/java/eu/kanade/tachiyomi/data/library/SourceRefreshPolicyTest.kt`
- `app/src/test/java/mihon/domain/source/interactor/ChapterMetadataCacheTest.kt`
- `app/src/test/java/mihon/domain/source/interactor/SourceRefreshConcurrencyTest.kt`
- `app/src/test/java/tachiyomi/data/chapter/SourceChapterTransactionTest.kt`
- `app/build.gradle.kts`, `gradle/libs.versions.toml` (test-only SQLite driver)
- This audit document.
