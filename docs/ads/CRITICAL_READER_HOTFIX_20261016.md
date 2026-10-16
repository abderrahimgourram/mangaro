# Critical reader hotfix — private code36

Baseline: code35 e1776e2562a1b168b19ba836f0129f648f8905a4, initially clean worktree. No ADB/emulator/installation/live source crawl or ad traffic. Recovery snapshot and initial state map are local under /tmp/mangaro-critical-reader.

## Verified source-level defects

1. Completion persisted only the counter, while presentation ran only during a later navigation/entry. Neither reader observed a newly due end obligation. Manga additionally discarded a ready selected-end callback while isLoadingAdjacentChapter was true, with no retry after that flag cleared. Initial read eligibility was captured at the first page callback, potentially after progress mutation. These are reproducible code paths; no phone Logcat was available to identify which path affected the reported session. Code35's verified Release already enabled SmartLink, so a disabled build flag was not assumed.
2. The ReaderViewModel init collector assigned the same SavedState page_index to every newly active ReaderChapter. It never consumed that saved chapter/page tuple. Delayed progress jobs also unconditionally wrote the visible currentPage and global page_index, allowing old chapter callbacks to alter current UI state.
3. ChapterLoader set Loading but rethrew cancellation without resetting it; preload explicitly skips Loading forever. Preload and direct open could fetch the same ReaderChapter concurrently. HttpPageLoader accepted an empty cached list and never attempted the normal network fallback; the outer loader rejected it only afterwards. Generic temporary transport failures had no bounded page-list retry. The user's unidentified provider failure cannot be attributed to these paths without its diagnostic.

## Corrected transitions and invariants

- The existing journal commits unique completion and an already-resolved pending destination together. Counters, fulfilled gates, JSON schema and account/category scopes remain unchanged. A ready last chapter may use its own identity as a safe stay-on-chapter boundary; no next chapter is invented.
- Novel end-state observes the durable pending gate ID. Manga combines durable obligations with current reader/loading state, waits for an existing navigation job, and presents once per obligation in that reader. Back/failing destination cannot cause an automatic dialog/retry loop. Explicit navigation and lifecycle restoration still use the existing admission guard. Late observers see durable state; no UI-only event is the source of truth. An older launch intent cannot replace its unresolved exact destination or accepted browser proof.
- Manga captures initial read eligibility before loading/progress callbacks. Ready-end completion no longer depends on the transient loading spinner. Webtoon completion additionally verifies the lower edge of its last panel is visible; a tall last image cannot trigger a mid-image gate. Browser opening remains solely the explicit full-screen button. Existing privacy/incognito exemptions and device protections are retained.
- Chapter-owned page restoration consumes the SavedState tuple once, respects explicit ad destination pages, restores each unread chapter's own progress and clamps to its page list. New SavedState reader_position is an additive two-element LongArray (chapter ID, zero-based page); legacy keys are still maintained/read. No disk/SQL migration. Publish the new visible chapter and its page together, fence obsolete viewer callbacks, key Compose app-bar controls by chapter, and serialize progress writes with selection-generation fencing for A -> B -> A races.
- A per-ReaderChapter mutex coalesces preload/open. Loaded pages are reused. Cancellation recycles only its failed loader and returns to Wait; invalid/empty content remains an error. Empty cached metadata falls through to the existing network path. Network page-list fetches have at most two attempts with 400ms backoff for IO/500/502/503/504 only. 403/404/429 are not automatically retried by this policy; the existing bounded, identity-validated stale-URL recovery remains unchanged. No access-control bypass. Offline loaders and download formats are unchanged.
- New diagnostics report source, opaque chapter hash, page-list/cache/network/offline/recovery stage and failure category, excluding bodies, credentials and URLs. Initial reader error uses «تعذر فتح الفصل» / «حاول مجددًا بعد قليل» / «إعادة المحاولة». Adjacent failures retain the prior good chapter.

## Validation and limitations

107 focused host JUnit cases passed, 0 failures/errors/skips. New fixtures exercise atomic due obligations at19/20/21/40, cross-work/restart/late observer, failed durable write, exact destination and accepted return, account separation, final indexed chapter, last-panel geometry, chapter-owned restoration/generation and actual ChapterLoader reuse/concurrency/cancellation/empty/offline routing through injected page loaders. IO/HTTP retry tests use injected failures. Existing stale-identity, download ownership/removal, Smart Focus, logout, cloud reconciliation and advertising suites passed. These are recorded/injected host scenarios, not successful live provider or Android rendering tests.

No reading database, library, bookmark, download queue/file, source adapter, cloud schema, advertising URL/ID/download thresholds, Home/catalog, workflow or account implementation was changed. The download unlock UI is byte-identical to code35. No historical database progress was imported into advertising counts.

Manual acceptance remains REQUIRED: paged + vertical + novel19/20/40, delayed image readiness, Back/restart/browser return, exact A page10 -> unread B page1 and partial B restoration, failed B retry after fulfilled gate, A/guest/B/A, existing offline chapters. For a real loading failure, provide source, opaque chapter and MangaroReaderLoad stage/outcome; do not send tokens or private URLs. No claim that all source failures or physical-device gates have passed.

## Changed files

- `app/build.gradle.kts`
- `app/src/main/java/eu/kanade/presentation/novels/NovelReaderScreen.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/ads/AdPageReadiness.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/ads/SmartLinkController.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/ads/SmartLinkPolicy.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/ChapterLoader.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/HttpPageLoader.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/ReaderLoadRecovery.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/model/ReaderChapter.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/model/ReaderPositionOwnership.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/Viewer.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerViewer.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonViewer.kt`
- `app/src/test/java/eu/kanade/tachiyomi/data/ads/SmartLinkReaderEndTest.kt`
- `app/src/test/java/eu/kanade/tachiyomi/ui/reader/loader/ReaderReliabilityTest.kt`
- `docs/ads/CRITICAL_READER_HOTFIX_20261016.md`
