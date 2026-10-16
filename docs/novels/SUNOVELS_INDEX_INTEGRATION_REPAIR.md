# Sunovels chapter-index integration repair — 2026-10-13

## Corrected symptom and evidence boundary

The user confirmed on their phone that novel details, descriptions, genres and comments open normally.
The app does not crash or return to the catalog. The chapter section reports:

> تعذّر التحقق من فصل شمس الروايات أو فهرسه. أعد المحاولة أو افتح الموقع الأصلي.

This is `SunovelsSource.invalid()`'s public message. It identifies direct parser validation,
not a failure of Voyager navigation or a gateway metadata request. It does not identify which
of the ownership/count/pagination/row validation guards failed on that phone. No device log or
phone HTTP response was accessed; the precise phone-specific rejected document remains unknown.

Confirmed call path before this repair:

1. `NovelCard` pushes `NovelDetailsScreen(work.primary)` with a Serializable `Novel`.
2. `NovelRepository.sources` selects the Debug-only `SunovelsGatewaySource` wrapper.
3. Catalog mapping preserves source ID `novel.sunovels` and canonical novel URLs.
4. `NovelDetailsScreen` renders existing metadata immediately. Metadata and index jobs were
   already independent; chapter exceptions were already caught and rendered inline.
5. The index job calls `continueIndex` → `completeIndex` → `NovelChapterIndexer.collect`.
6. **The wrapper did not override `chapters()`**, so Kotlin interface delegation called
   `SunovelsSource.chapters()` for every work. Accepted D1 chapter metadata was never consumed.
7. Direct parsing requires public Flight novel ownership, chapter counts, pagination and valid
   rows. A validation rejection emits the reported message. A retry previously used the same
   parser/cache contract and direct route.

Therefore a gateway `503 INDEX_PARTIAL` was not the previous Android rejection: the chapter
endpoint was not called. Production PARTIAL revision 4 is not a global deny-list of stored
source adapters; authenticated source records merge with the bundled FULL snapshot. Neither
navigation encoding nor a globally invalid gateway metadata envelope was observed.

## Bounded real diagnostics

Read-only HTTPS requests used the actual Android user agents and normal TLS verification.
Private responses/results are under `/tmp/mangaro-sunovels-opening-audit/`.

Gateway: https://mangaro-sunovels-staging.arabizeai.workers.dev

| Real identifier | Metadata | Chapter page 1 |
| --- | --- | --- |
| `reverend-insanity` | 200; valid schema/source/identity | 200; 50 distinct public rows; next page 2; complete=false; declared 2,343 |
| `-1` | 200; valid metadata | 503 INDEX_PARTIAL |
| `-2` | 200; valid metadata | 503 INDEX_PARTIAL |
| `-3` | 200; valid sparse metadata; null description/count | 503 INDEX_PARTIAL |

Catalog and original-title search return real `PUBLIC_SITE` records, schemaVersion 1,
sourceId `novel.sunovels`, generation 3. Status reports 1,461 records, 61 accepted pages,
source DEGRADED. These gateway error envelopes deliberately omit success origin/generation.
Source-side contract assertions passed for all four metadata/index responses. These assertions
validate JSON contracts; they do not execute Android code.

Two additional bounded public index requests:

* https://sunovels.com/novel/reverend-insanity?activeTab=chapters&page=0 — HTTP 200,
  50 rows, owner ID 88, 2,343 declared chapters, 47 pages, next enabled.
* https://sunovels.com/novel/-1?activeTab=chapters&page=0 — HTTP 200,
  two rows, owner ID 1961, two declared chapters, one page, next disabled.

Existing independent source-side parser diagnostics passed ownership, counts, ordering,
row identities and pagination on both responses. This demonstrates publicly supported direct
pagination from this computer; it does not prove what the phone received. Existing saved
page-24/page-47 evidence still identifies two ambiguous addresses and one missing-title row.
No chapter bodies were fetched in this repair and no source crawl/deployment ran.

## Implemented repair

`SunovelsGatewaySource.chapters()` now calls the separate metadata chapter endpoint using
`SunovelsSource.requireNovel()`'s validated slug, never a full novel URL. It checks success
origin, index origin, generation, totals, page/row identities, pagination, title bounds and
availability. It uses a six-second, one-attempt lookup so an unavailable gateway leaves time
for direct fallback inside the existing bounded index session.

`INDEX_PARTIAL`/404 and transient transport/server failures fall back to the existing direct
Sunovels adapter for the requested page. Validation errors remain failures; they are not turned
into empty success. If both routes fail, the inline Arabic error says the index is unavailable
while novel metadata remains saved. Logging retains the direct failure's diagnostic reason.

Gateway and direct rows use the same `SunovelsSource.indexPage()` implementation. It validates
novel ownership, official bounds, row count/order, duplicates, numeric label/address conflicts
and the known Reverend Insanity ambiguous mappings. Distinct published rows sharing a URL remain
in `publishedRows`; that URL is unavailable, and no fractional destination is invented. Missing
titles and explicit LOCKED/MISSING_IDENTITY/REMOVED states affect their entries, not healthy
chapters elsewhere in the page. Gateway UNKNOWN/READABLE never authorizes a chapter body.

`ChapterPage.verifiedTermination` defaults to true for all existing direct adapters. The staging
wrapper supplies the accepted index's actual completion flag. `NovelChapterIndexer` requires
this flag before marking an index complete, so a final partial gateway page cannot become a
fake complete index. It continues to persist verified pages and retain them after a later error.

The staging index parser key is now `sun-index-gateway-v3`. It revalidates older checkpoints from
page 1 through the repaired strategy. Last-good rows remain visible while refresh proceeds.
The Release/direct-only cache key remains the previous value. No edition/chapter identities,
library database migrations, bookmarks, positions, queues or offline-file paths are changed.
The reader's `sun-public-reader-v2` storage/checksum contract is unchanged.

An additional confirmed wrapper defect was corrected: successful sparse gateway details no
longer require a direct website fetch just because synopsis/count is null. A validated slug
and existing edition URL/key are preserved, including supported stored www/trailing-slash URLs.
Transport-only detail fallback remains. The details Compose source was preserved byte-for-byte
from the incoming worktree; no screen redesign or navigation change was needed.

Reading/downloading retains `NovelRepository.chapterText()`, ownership checks, valid memory/disk
content first, the repaired direct Sunovels reader, atomic writes, checksums and the existing
resumable queue. Optional gateway availability failures are logged and defer to the independently
validated public reader. Explicit matching LOCKED/AMBIGUOUS_CHAPTER/MISSING_IDENTITY/REMOVED
observations remain terminal. No text endpoint or access-control bypass was added.

## Build and staging isolation

Only build this configuration for staging:

```bash
./gradlew :app:assembleDebug \
  -PsunovelsGatewayStagingEnabled=true \
  -PsunovelsGatewayEndpointReviewed=true \
  -PsunovelsGatewayUrl=https://mangaro-sunovels-staging.arabizeai.workers.dev/ \
  -PsunovelsGatewayHost=mangaro-sunovels-staging.arabizeai.workers.dev
```

Debug package: `app.manhwaar.reader.dev`; Release package: `app.manhwaar.reader`.
A plain Debug build without flags disables this gateway. Release remains disabled even if
Debug staging properties are supplied. The installed phone build was not inspected.

ARM64 artifact: `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`.
Debug BuildConfig and APK DEX were inspected for the real HTTPS endpoint and adapter.
Final compilation/diff verification is recorded below after the last source edit.

No Cloudflare Worker/D1 changes, gateway deployment, catalog rebuild, signed-rule publication,
key changes, production activation, Android tests, device interaction or Release build occurred.
Both incoming worktrees and HEADs were retained. Unrelated existing changes were fingerprinted.

## Manual acceptance

1. Use this staging Debug ARM64 APK; confirm its package is `app.manhwaar.reader.dev`.
   Keep existing application data. Report any signing mismatch rather than uninstalling.
2. Open Reverend Insanity: metadata should remain visible and the first 50 accepted chapter rows
   should appear even if later direct pages fail. A partial notice/error must retain those rows.
3. Open `-1` (الوحشين الغمضين) and `-2`: their gateway INDEX_PARTIAL must trigger direct indexing,
   rather than closing details or reporting an empty successful index. `-1` has two observed
   public index rows. Public index availability is not proof its bodies are readable.
4. Retry a later failed page, leave/reopen the screen, and verify last-good rows remain.
   If validation still fails, report the exact novel URL/message and available safe app diagnostics.
5. Read publicly supported Reverend Insanity chapters 1/2; verify Arabic paragraph display and
   progress. Verify 1153/1153,2 and 1154/1154,2 are unavailable without body substitution.
6. Download eligible independent chapters, pause/resume/cancel/retry, then read offline. Verify
   prior files, positions and bookmarks remain; locked/ambiguous chapters must not become DONE.
7. Check other sources and the normal details UI remain unchanged.

Sunovels remains DEGRADED. Complete 2,343-row index coverage and phone reading/download behavior
remain unverified. No claim that all 1,461 works have accessible bodies or gateway chapter indexes
is made. The exact device-specific direct parser rejection requires manual confirmation.

## Final verification result

* Final staging `:app:assembleDebug`: BUILD SUCCESSFUL in 19s. No Android tests ran.
* APK: /home/jalem/Downloads/ManhwaAR_V0.2_Mihon_Fork_Kit/ManhwaAR_V0.2_Kit/ManhwaAR_Android/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
* APK size: 85712874 bytes; SHA-256: `8ab0112ee461ccc0b3d695b99fdb0c6c766c7f88ed9381fb5e19ab7e1c494849`.
* Final APK DEX contains the reviewed HTTPS URL, adapter, new index cache version and verified-termination contract.
* `git diff --check`: passed in both worktrees.
* Modified incoming Android files in this task: `app/src/main/java/eu/kanade/tachiyomi/novels/SunovelsGateway.kt`, `app/src/main/java/eu/kanade/tachiyomi/novels/SunovelsSource.kt`, `app/src/main/java/eu/kanade/tachiyomi/novels/NovelChapterIndexer.kt`, `app/src/main/java/eu/kanade/tachiyomi/novels/NovelModels.kt`, `app/src/main/java/eu/kanade/tachiyomi/novels/NovelRepository.kt`. This report is new.
* `NovelScreens.kt` equals its incoming SHA-256 exactly. Infrastructure versionable files also equal incoming fingerprints.
