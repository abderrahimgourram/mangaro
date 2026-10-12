# Mangaro Refined Dark — stage 1 checkpoint

The complete redesign is **not finished**. This checkpoint contains the download integrity fix and a coherent first UI stage. Continue with work details, then library/history, downloads/profile and final navigation/accessibility acceptance. Do not restart source publishing or reader performance work.

## Download integrity

Local commit `bfb49103f`: readable cached text survives missing illustrations; a persisted DONE task is restored on cold reopen and becomes FAILED/retryable. The queue is not created for ordinary online reading. Queue failure cannot block valid cached text, and cancellation still propagates.

Focused JVM tests: **15 passed, 0 failed, 1 skipped** (optional live unified-index test). Actual emulator instrumentation: **1 passed**, isolated real SQLite/GZIP storage fault, cold repository, no chapter HTTP requests, no user database writes. Log: `/tmp/mangaro-refined-offline-emulator.log`. Fixture evidence is not live-source acceptance.

## Implemented UI

- Home novel and manga shelves share an artwork frame and scalable two/three-line titles.
- Community Picks keeps the same five-work selection, weekly rotation, original artwork and real ratings. More compact artwork/text composition; artwork crops without stretching, including at large font sizes.
- Manga search and novel discovery share search styling, clear/search behavior and cover cards. Manga results retain actual source/author and library badges. Novel discovery keeps separate content-specific metadata and two-column phone layout.
- Genre chips have readable single-line labels and minimum 48dp targets. Shared navigation adds selected tab semantics, higher contrast and font-safe minimum height.
- No source/parser, reader, index, cloud, authentication, advertising or database design changes in this visual stage. Cover loading uses the existing loaders and lifecycle animation behavior.

Debug build passed. Home/discovery, durable queue and reader-scope regression suites: **65 passed, 0 failed, 0 skipped**. This is separate from the 15/0/1 download suite; total distinct JVM cases: **80 passed, 0 failed, 1 skipped**. No new minified UI run was performed.

Actual API 30 Pixel 4a emulator (`emulator-5554`): Home, native manga search, novel discovery, genuine artwork, Arabic/English search, scroll, background/reopen inspected; 360dp, approximately 430dp, default and 150% font. Density and font settings restored. Screenshots are genuine app captures, not mockups. The 12 reference images were not attached to this session.

## Evidence and limits

Screenshots and raw gfx/memory summaries: `/home/jalem/Downloads/Mangaro_Refined_Dark_20261012_Stage1/`.

| Screen | Before | After |
|---|---|---|
| Home | before-home.png | after-home.png |
| Novel discovery | before-novel-discovery.png | after-novel-discovery.png |
| Manga search | before-manga-search.png | after-manga-search-empty.png, after-manga-search-results.png |

Large-font captures: after-home-360dp-font150.png, after-community-360dp-font150.png, after-novel-360dp-font150-scrolled.png. Wide phone: after-home-430dp.png. Keyboard: after-novel-keyboard.png.

Short debug emulator scroll samples: baseline P95 **19ms**, first post-restart **25ms**, warm repeat **18ms**. Jank counter baseline **23.89%**, both new samples **51.40%**; this requires controlled profiling before performance sign-off. PSS: baseline **287321KiB**, restart sample **325937KiB**, warm **258934KiB**. Live artwork/cache/GC state differs, so these are observations, not a proven improvement or a diagnosed causal regression. No low-end physical-device claim.

TextSecondary/SurfaceDark contrast **10.71:1**, GoldPrimary/SurfaceDark **10.92:1**. RTL and mixed-language rendering inspected. Full TalkBack acceptance remains pending: UIAutomator clips bottom-navigation bounds (also present before this stage), so declaration of 48dp targets is not proof of assistive hit testing.

Continue-reading/recent-update rows need acceptance with populated reading history. Missing-cover, all loading/error states, long library, animation frame progression, real-account synchronization and final minified UI regression remain unverified in this stage. Individual source catalogue layouts, work-details hero, library/history and downloads/profile have not been redesigned here.

## Exact visual paths

- `app/src/main/java/eu/kanade/presentation/components/MangaroFilterChip.kt`
- `app/src/main/java/eu/kanade/presentation/components/MangaroSearchField.kt`
- `app/src/main/java/eu/kanade/presentation/components/MangaroWorkCard.kt`
- `app/src/main/java/eu/kanade/presentation/home/MangaroBottomNavigation.kt`
- `app/src/main/java/eu/kanade/presentation/home/MangaroContinueReading.kt`
- `app/src/main/java/eu/kanade/presentation/home/MangaroDiscoveryShelves.kt`
- `app/src/main/java/eu/kanade/presentation/home/MangaroSearchScreen.kt`
- `app/src/main/java/eu/kanade/presentation/home/MangaroSectionHeader.kt`
- `app/src/main/java/eu/kanade/presentation/home/MangaroVisualTokens.kt`
- `app/src/main/java/eu/kanade/presentation/home/MangaroWeeklyPicks.kt`
- `app/src/main/java/eu/kanade/presentation/novels/MangaroNovelIntegration.kt`
- `app/src/main/java/eu/kanade/presentation/novels/NovelScreens.kt`
- `app/src/main/java/eu/kanade/presentation/theme/MangaroDesignSystem.kt`

## Download fix paths

- `app/build.gradle.kts` — focused test selector only; pre-existing version changes remain uncommitted.
- `app/src/main/java/eu/kanade/tachiyomi/novels/NovelDownloadDisk.kt`
- `app/src/main/java/eu/kanade/tachiyomi/novels/NovelRepository.kt`
- `app/src/test/java/eu/kanade/tachiyomi/novels/NovelUnifiedTest.kt`
- `app/src/androidTest/java/eu/kanade/tachiyomi/novels/NovelOfflineRecoveryInstrumentation.kt`

No Android push, APK release, version bump, publisher edit, production publication or secret change. Protected user changes are verified against `/tmp/mangaro-refined-dark-initial.json`; final preservation evidence is `/tmp/mangaro-refined-dark-preservation-final.json`.
