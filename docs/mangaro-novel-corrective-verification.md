# Novel corrective verification — 2026-10-12

The reported download failure was Sunovels-only. Its locked/unavailable content and ambiguous identities remain upstream limitations. Shared download queue, worker and disk code were not changed for this repair.

Discovery, search, genre discovery and new library additions now use Kolnovel, Cenele and SeaNovel. Sunovels remains registered for existing saved entries, history, progress, queued downloads and offline content. The four-source signing allowlist, legacy/full validation and partial fallback remain unchanged. No database or ID migration is needed. Old Sunovels requests show an accurate unavailable-source message; stored chapter text stays usable.

Details previously requested one eight-page batch. They now continue bounded successful batches (up to eight batches / 120 seconds, each existing batch has a 20-second timeout). Verified source cursors and every page checkpoint are persisted through the existing atomic metadata store, including refresh checkpoints. Cancellation, official-total checks, old chapter preservation and no-progress rejection remain strict. Exhausting the session budget leaves a partial index with a load-more action.

Live acceptance on **emulator-5554**, Pixel 4a / API 30:

| Check | Actual result |
|---|---|
| Large Cenele index after force-stop | Restored 95 rows / page 1; automatically continued to 3215/3215, 39 pages, distinct IDs |
| Network failure and UI retry | Preserved 100 rows; actual retry button continued to source-declared 140/140 |
| Cenele download smoke | 632/632 index; one real chapter queued from UI and completed through shared WorkManager |
| Offline reader after restart | Disk origin; 217 paragraphs / 12703 characters displayed with Wi-Fi and data disabled |
| Reading position after another restart | Paragraph 7, offset 14 preserved |
| Signed production feed | Exact revision 4 manifest and envelope verified against the packaged public key; unchanged hashes |

Five final instrumentation executions succeeded. Two immediate force-stops deliberately interrupted checkpoint setup. An earlier retry harness scrolled past error controls and timed out; navigation was corrected without production changes or timeout increases.

Focused JVM suites: **55 passed, 0 failed, 1 skipped** (50 indexing/retirement/consumer/lifecycle/queue/offline tests, then 5 signature tests, including genuine fetched production bytes). Debug app and test APK compilation passed. The skipped JVM test requires Android illustration decoding; this session's live smoke was text-only. No release/minified build or physical-device claim is made.

Visual recovery selectively removes framed artwork cards and chip borders introduced by c00dacbbc, restores compact shelf widths and the earlier novel-grid composition. Search accessibility, font-responsive touch targets, cover caching/animation behavior and community selection logic are preserved. Screens were inspected at 360dp and 430dp with Arabic and mixed English titles. This is a corrective pass, not a new redesign.

Screenshots and logs: `/home/jalem/Downloads/Mangaro_Corrective_20261012/`. The startup-only `resume-initial.png` is not chapter-rendering evidence. `complete-index.png`, `retry-error.png`, `download-done.png` and `offline-reader.png` provide actual UI evidence. Previous reference captures remain in `Mangaro_Refined_Dark_20261012_Stage1/`.

No source parser, publication artifact, GitHub workflow, signing key, app version, APK release or publisher repository was changed. Pre-existing DownloadJob/library/source-audit/network/release-staging edits were preserved against `/tmp/mangaro-corrective-initial.json`. Emulator connection, density and font settings were restored after acceptance.
