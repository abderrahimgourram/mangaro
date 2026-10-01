# Product cleanup and chapter download acceptance — 2026-10-01

Only emulator-5554 was used. No physical device, source-rule feed/publisher, DB schema,
chapter reconciliation or completeness safeguard was changed.

SourceManager now resolves bundled sources only; installed extension APKs cannot override
or add a normal source. Legacy library/history/source rows and download directories remain.
Presentation hides provider labels and accessibility labels, including error notifications.
Home/search use bundled sources independently of inaccessible legacy extension filters.
Manga identities remain separate even when titles match.

Root cause: Downloader.queueChapters auto-started only for an empty queue. Failed or
restored entries blocked a new chapter, including an already queued retry. It now starts
an idle, unpaused requested queue. Retry fetches a new source page list; concurrent expired
image failures share one bounded source-resolved refresh, rejecting page count/order changes.
No Library restriction was added; the existing add-to-library prompt is optional.

Acceptance:
- Eighteen targeted unit tests: queue/retry policy, bounded URL refresh, internal registration,
  installed extension rejection and source-scoped discovery identity.
- MangaTime / Blue Lock / chapter 1: normal Downloader, 42 pages, favorite=false;
  offline Reader visibly rendered with DownloadPageLoader.
- MangaDar / Kingdom / chapter 1: normal Downloader, 52 pages, tall-image splitting enabled;
  offline Reader visibly rendered with DownloadPageLoader.
- Both downloaded states survived a separate process restart and opened offline again.
- Reader settings switched L2RPagerViewer and R2LPagerViewer while UI roots stayed Arabic RTL;
  the original per-manga setting was restored afterwards.
- Actual emulator system configuration changed en-US/ldltr -> ar-MA/ldrtl; Home title,
  tagline, settings action and section positions were identical before/after.
- Home/details accessibility trees contained no provider labels; Browse extension tabs were absent.
- Launcher uses a purple/gold M/book adaptive icon; round and monochrome assets included.

Build: :app:assembleRelease (ARM64 split); no Release installation on Vivo.
