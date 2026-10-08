# Storage and startup maintenance

This change preserves version 1.20.6/16 and all pre-existing UI changes. No device,
emulator, network inventory, or cold-start benchmark was used. The reported
500 MB footprint cannot be attributed precisely without a phone storage inventory.

## Findings and policy

| Storage | Before | Policy |
| --- | --- | --- |
| Ordinary library covers | Persistent external/internal `covers`, no budget | New writes in `cacheDir/covers`; reuse legacy files; 64 MiB combined idle LRU target |
| Coil images | Installed Coil 3.5.0 defaults: 2% free disk, 10–250 MiB | Explicit 64 MiB using the existing `coil3_disk_cache` directory |
| Reader image/page-list cache | Existing 100 MiB DiskLruCache | Preserve 100 MiB, snapshot/journal handling and existing user preferences |
| HTTP cache | Existing 5 MiB OkHttp LRU | Unchanged |
| Custom covers, downloads, library/history, sessions, preferences, sigils | Essential user data | Excluded from maintenance |
| Shared images and `cover-write-UUID.tmp` staging files | Shared images cleared wholesale on each share; no idle staging cleanup | Keep for 3 days, then remove only recognizable disposable files while idle |
| Interrupted chapter downloads | Resumable numbered `.tmp` pages in chapter `_tmp` directories | Never age-sweep; remove leftover numeric fragments only after all chapter pages have joined and passed completion checks |
| SQLite/WAL, source-rule snapshots, download index | Persistent authoritative data or existing bounded/reused snapshots | No deletion, VACUUM, schema change or speculative checkpoint |
| WebView cache/cookies | Managed by Android WebView | Unchanged; no global cache/cookie clearing that could affect source sessions or active ads |

The cover target is eventual, not a total app-storage cap. Active files and writes
newer than 10 minutes take precedence over eviction. Completed downloads can
legitimately occupy much more space than these cache budgets. OS cache eviction
can also remove ordinary cache files; custom covers remain persistent.

## Maintenance safety

WorkManager uses unique periodic work (24 hours, first run no earlier than 4
hours) and coalesced pressure hints after 8 MiB of new cover writes (at least a
15-minute delay). Both require device idle and adequate battery. No foreground
scan, polling loop or permanent service is added.

Each sweep checks process visibility, downloader activity and cancellation.
Concurrent pressure and periodic sweeps serialize inventory and eviction so
stale totals cannot cause extra eviction of useful covers.
Inventory is streamed and only the oldest 512 candidates are retained in memory.
Each category deletes at most 512 files per pass; a 20-second cooperative timeout
limits scanning. Incomplete passes retry with exponential backoff. Direct-file
whitelists, canonical parent checks and symlink exclusion prohibit recursion into
downloads or custom covers. Decoder/writer leases, changed-size/mtime checks and
recent-file grace periods protect in-use files. Filesystem calls themselves may
take longer on unusually slow storage; cancellation is checked between entries.

Covers are written to same-directory staging files and atomically renamed after
a successful write. Failure preserves the existing valid cover. Promoted Coil
snapshots are closed before removal, allowing elimination of duplicate cached
copies. Snapshot removal is best-effort when other requests still own an entry.

## Startup critical path

| Source-level work | Before | After |
| --- | --- | --- |
| Expensive singleton warmup | Four gets posted to the main executor | Necessary database/source/download construction on IO, after required migrations |
| Internal source registration | Eagerly constructed during `Application.onCreate` | Registered in the existing lazy source factory |
| Intro readiness | Home discovery network results, or an 8-second ceiling | Local initialization and actual navigation frame; no network readiness gate |
| Cover prefetch behind intro | Up to 11 additional covers | Removed; normal visible-item image loading retained |
| Unused Home queries | Full library plus recent-updates subscriptions | Removed; neither field was consumed by Home UI |
| EGL texture-limit query | Synchronous first-run main-thread query | IO, retaining the safe initial threshold and explicit user settings |
| Optional maintenance scheduling | Application/first foreground callbacks | Once after the usable frame, including restored Activities |

Migration completion is still awaited. Session restoration, durable account and
sigil event observation and headless widget updates remain functional. Their
local construction occurs on IO. Discovery content still loads normally; the
navigation shell is usable independently of optional network results.

`Mangaro.localServices` is an Android Trace section for the necessary local
initialization path. Existing `reportFullyDrawn()` and baseline-profile startup
benchmarks remain available for later phone profiling. No timing claim is made
from static inspection or host Gradle duration.

## Database and memory

The removed Home subscriptions avoid unused repeated reads without changing
database schemas or migrations. Existing chapter-cache commits already flush the
journal and schedule LRU trimming; the redundant synchronous pre-commit flush is
removed. Cache journal compaction remains handled by DiskLruCache.

Cover buffering is capped at 16 MiB; larger images retain the normal streamed
Coil path rather than being truncated. Cached-cover HTTP responses are explicitly
closed. Native Coil decoder resources are recycled even if decoding throws.
Existing Coil memory cache (15% RAM), Android memory-pressure callbacks, reader
loader cancellation and ad WebView disposal are retained.

## Verification and changed files

Focused JVM tests cover LRU and bounded batches, active leases, recency, old
staging cleanup, preservation of downloads/custom covers/database files, repeated
maintenance, foreground protection, cancellation, changed files/symlinks, atomic
cover-write failures, concurrent sweep safety and once-only deferred startup ordering. These do not replace
phone measurements of process-kill, reboot, offline or large-library startup.

Implementation files:

- `App.kt`, `di/AppModule.kt`, `ui/main/MainActivity.kt`
- `ui/home/HomeTab.kt`, `ui/home/HomeViewModel.kt`
- `data/cache/CacheMaintenance.kt`, `StorageMaintenanceJob.kt`, `FirstUsableFrameGate.kt`
- `data/cache/CoverCache.kt`, `ChapterCache.kt`
- `data/coil/MangaCoverFetcher.kt`, `TachiyomiImageDecoder.kt`
- `data/saver/ImageSaver.kt`, `ui/reader/ReaderViewModel.kt` (share lifetime only)
- `data/download/Downloader.kt` (completed-page fragment cleanup only)
- `src/test/java/eu/kanade/tachiyomi/data/cache/CacheMaintenanceTest.kt`

No changes to advertisements/consent, XP, achievement rules, account/security,
source parsers, reconciliation, reader position/mode, website or release setup.
