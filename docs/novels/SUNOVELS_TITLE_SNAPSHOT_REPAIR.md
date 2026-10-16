# Sunovels title and stale-snapshot repair — 2026-10-14

Implemented in the existing native engine on `feature/adsterra-production`, HEAD `a3cddc54fe2d69e6d37ac91d2ba88c4134d45902`. Existing dirty work is preserved. No emulator/ADB/phone execution, Release/R8 build, push, deployment, workflow modification, signed-rule change or D1 operation occurred in this task.

## Evidence and scope

The reported physical-phone failure is accepted. Its exact saved Resume URL, persisted snapshot and failure diagnostic are still unavailable; this task does not assign `ولادة نوح` to `/0` or `/1` or claim physical-device success.

Fresh requests to `/robots.txt`, `/novel/birth-of-the-demonic-sword/0` and `/novel/birth-of-the-demonic-sword/1` all returned **HTTP 200**. Exactly **3 public HTTP requests**, including robots, were made. The two chapter responses were **111,932** and **163,806** bytes, with `0 الفصل` and `1 الفصل` headers. The actual Kotlin parser accepted both fresh responses against a descriptive saved-title fixture. These are separate known public pages, not proof of the phone's Resume mapping. Request hashes/statuses are in `sunovels-title-snapshot-evidence/public-requests.json`; story bodies were not copied into the report or repository.

Before modification, a deterministic variant with a bare numeric public title `0` and saved descriptive title `ولادة نوح` failed at the `parseChapter` conflicting-descriptive-titles guard, with `TITLE_MISMATCH` and exception text `Conflicting descriptive titles`. The pre-fix log captures the failure. Generic `0 الفصل`/`1 الفصل` handling was already present in this worktree. Remaining title-only gates, including disagreement between two descriptive titles, are now removed. This proves and repairs a validation defect; it does not establish which gate the original phone reached.

## Fixes

- Ownership is still verified through the source host/final route, expected novel slug, positive structured novel/chapter IDs and exact BigDecimal chapter number. Known ambiguous addresses and contradictory numeric headers remain rejected. Descriptive titles and cosmetic headers cannot independently reject verified public content. Manual revalidation retains the best non-empty descriptive title.
- Reused the existing sanitized story walker for p/div/article/span/text/br content. A single meaningful Arabic block of at least 120 characters is accepted. Empty/short DOM results can recover from the same verified unlocked reader record's public `content` or `body` field. Flight parsing scans every push in every script and reassembles split strings, including multiple pushes in one script. It never executes JavaScript or searches arbitrary payload strings for stories.
- Explicit visible lock banners and structured `isLocked` evidence are checked before extraction. Hidden elements, buttons and navigation remain filtered through existing sanitization. Visible attribution and legitimate visible story text are retained; no broad watermark-word deletion was added.
- Added JSON `indexParserVersion`, default **0** for old snapshots and **3** for fresh Sunovels checkpoints. The hashed index key also advances to v7. Old snapshots cannot take the complete/fresh fast path even when their old hash looks current. Novel opening uses the existing bounded two-page loader, restarting at public `page=0`/`page=1` or resuming a verified current checkpoint. Last-good rows stay visible; declared totals are updated from live source evidence. Partial coverage remains explicit.
- Manual reader Retry and availability checks clear client-generated transient backoff and re-probe temporary/legacy negatives. They preserve source `Retry-After`, 429 pacing, access barriers and confirmed-lock cooldowns. Targeted mapping recovery uses one published index page and an exact persisted URL match; it permits at most one extra reader GET. No title-to-URL guessing or chapter-ID rewriting occurs.
- Debug reader/preflight errors now display `REASON: ...` directly alongside `تشخيص الفصل`. The copyable report includes persisted ID, requested/final URL, expected/page number, saved/public title, status, bytes, selected strategy, text counts, outcome/evidence, attempts, cache origin and cooldown. No story body, cookies, credentials or private user identifiers are copied.

## Cache and migration safety

This is an additive field in the existing JSON index snapshot, **not a Room/SQLDelight migration**. `NovelMetadataStore` and its atomic-write/backup behavior are unchanged. Library entries, reading-position and bookmark schemas, download files/checksums and chapter URL identities are unchanged. Verified explicit lock evidence survives index merging. Only verified reader content and explicit source locks qualify for durable reader-evidence persistence; network/parser failures remain temporary. Checksummed downloaded copies retain the existing fast path, including on Retry.

The stale-count regression stores a synthetic legacy declared total of **2335** with accepted cached rows. The actual index collector and production visibility-merge helper refresh two source-backed pages, obtain declared total **2336**, and preserve old rows, library JSON, offsets/bookmarks and stored last-good text byte-for-byte. It does not claim all 2336 rows were newly fetched/verified, nor inspect the original phone's cache.

## Validation

- **151 host contracts PASS**, retaining the original 83 and all preceding 127 assertions. Existing div/br fixture text was made long enough for the requested 120-character floor; the empty-response fixture now empties both DOM and referenced Flight content, while a separate test verifies DOM-placeholder recovery. No lock/ownership assertion was removed.
- Covers saved `ولادة نوح` versus public `/0` and `/1`, bare `1743`, different descriptive titles, single long paragraphs, div/br/span text, multi-push Flight and `body` fields, blank/interrupted pages, explicit banners, stale version/count refresh, preserved progress/bookmarks/content, immediate manual timeout recovery, targeted one-page 404 recovery, Retry-After and lock cooldowns.
- Existing `/30`, `/135`, `/675`, Shadow Crown and Reverend ambiguity contracts remain passing. Gateway requests are **zero**; replay rejects every non-Sunovels host.
- Final `:app:assembleDebug` **PASS in 32s**; static integration/APK guards **PASS**; `git diff --check` **PASS**. Source-side contracts took **48.68s** (compilation excluded). Local search mean **0.203 ms** on the host fixture catalog of 1461 records; this is not an Android runtime benchmark.
- No new physical/emulator runtime results are claimed. The prior optimized emulator audit remains a separate historical report.

## Request and memory bounds

Ordinary public resolution keeps one coalesced in-flight fetch per chapter, source starts at least 1500 ms apart and existing two per-host slots. Cosmetic row reconciliation uses at most one additional published index page. An explicit identity/missing-response repair is bounded to two reader GETs plus one index page. Stale snapshot opening loads two index pages, not a full catalog or 47-page chapter index. Existing offline/memory copies require zero public GETs. All Flight pushes are scanned within an 8 MiB HTTP response budget and a 4 MiB public text-field budget; traversal/depth and cache limits remain bounded. Final diagnostic retention in contracts was **8,811 characters** across a maximum of 32 reports. Android memory/performance were not measured in this task.

## Changed code and tools

- `app/src/main/java/eu/kanade/tachiyomi/novels/SunovelsSource.kt`
- `app/src/main/java/eu/kanade/tachiyomi/novels/SunovelsRequestPolicy.kt`
- `app/src/main/java/eu/kanade/tachiyomi/novels/NovelHttp.kt`
- `app/src/main/java/eu/kanade/tachiyomi/novels/NovelModels.kt`
- `app/src/main/java/eu/kanade/tachiyomi/novels/NovelChapterIndexer.kt`
- `app/src/main/java/eu/kanade/tachiyomi/novels/NovelRepository.kt`
- `app/src/main/java/eu/kanade/tachiyomi/novels/SunovelsResolution.kt`
- `app/src/main/java/eu/kanade/presentation/novels/NovelScreens.kt`
- `app/src/main/java/eu/kanade/presentation/novels/NovelReaderScreen.kt`
- `app/src/main/java/eu/kanade/presentation/novels/SunovelsReaderEntry.kt`
- `tools/sunovels-direct/SourceContract.kt`
- `tools/sunovels-direct/run-contracts.py`
- `app/src/main/java/eu/kanade/tachiyomi/novels/SunovelsIndexSnapshots.kt`
- `tools/sunovels-direct/validate-integration.py`

Documentation/evidence added: this report and `docs/novels/sunovels-title-snapshot-evidence/` (public-request metadata, pre-fix regression log, final host/build logs and JSON validation/inventory). Existing architectural/audit reports were preserved.

## Debug artifact and manual acceptance

APK: `/home/jalem/Downloads/ManhwaAR_V0.2_Mihon_Fork_Kit/ManhwaAR_V0.2_Kit/ManhwaAR_Android/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`

Package: `app.manhwaar.reader.dev`; ARM64; 76,887,808 bytes.

SHA-256: `f42b16d065d3c6493e00ac74439e5fa7a10b088c7433c2f98064612898967c01`

1. Update the existing `app.manhwaar.reader.dev` with this Debug APK; do not uninstall or clear app storage.
2. Open Birth, allow the bounded index refresh, and check source-declared **2336** with honest partial coverage. Confirm the existing bookmark and saved position remain.
3. Use Resume for `ولادة نوح`. If it fails, copy `تشخيص الفصل`; record the exact persisted chapter ID, requested/final URL and visible reason. Do not infer `/0` or `/1` from the title.
4. Check the separately published public `/0`, `/1` and Reverend `/30`; verify original attribution and text render. Confirm `/675`, Shadow and ambiguous Reverend addresses remain protected.
5. After a transient connectivity failure, reconnect and use Retry. Confirm recovery without clearing data; if a server cooldown is active, honor it. Restart the app and check Resume/bookmarks and an existing offline download.

This source-side implementation is ready for manual acceptance. The original phone's exact failure stage and cache contents remain to be confirmed through its diagnostic report.
