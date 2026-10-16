# Sunovels native direct engine — local Debug acceptance candidate

Implemented against the current dirty Android worktree on
`feature/adsterra-production`, HEAD `a3cddc54fe2d69e6d37ac91d2ba88c4134d45902`.
Existing edits were preserved. No Android runtime/UI/device tests, ADB, emulator,
Release generation, push, deployment, scheduler change or D1 mutation occurred.

## Source selection and independence

`SunovelsSource.directEnabled` is `BuildConfig.DEBUG`. The existing repository
selects the direct source whenever that gate is true, including when the old
Debug gateway flags are enabled. The default build used for acceptance has those
gateway flags disabled. Direct discovery is available in Debug through the
existing source availability policy. Release discovery activation remains unchanged.
The direct source has no gateway, D1, Supabase or other Mangaro server endpoint.
The existing optional community/account features remain separate from source access.

The host source contracts compile the actual `SunovelsSource`, `NovelHttp`,
`NovelChapterIndexer`, content sanitizer, models, metadata store, identity
reconciler and local search. Only BuildConfig/logging/selector platform context
is stubbed. An OkHttp interceptor replays captured public pages, rejects every
non-Sunovels host, and never opens a socket. This tests actual source and transport
code with all gateways unavailable; it is not an Android runtime test.
Static guards verify source selection, gateway-disabled Release defaults,
reader preflight, offline disk access, download fences, seed hashes and ARM64 APK
contents. Physical-device acceptance is still required.

## Public source behavior

The source's public search form advertises English/Arabic titles and navigates to
`/search?title=<encoded term>`. There is no invented JSON endpoint or private API.
Catalog uses `/library?page=<zero-based>&category=<optional public category>`.
The inspected catalog has 24 cards per page and source-backed current/next page
controls. Search is a closed list where the public page provides no pagination;
an empty search remains empty. Additional catalog pages load on demand, and the
source's displayed order is preserved rather than claiming unverified chronological
publication order.

Details parse JSON-only public Flight page data and resolve source references for
Arabic/original title, author, categories, cover and nullable description/status.
They validate ownership independently of chapter listing completeness. Reliable
updated timestamps are used only when actually present in source metadata.
Existing novel and chapter URL identities are never rewritten in library,
bookmarks, progress or download records. New discovery URLs are canonical.

Chapter pages use the public novel URL with `activeTab=chapters&page=<zero-based>`.
The engine validates the requested page, novel owner, declared total, source
termination controls, page-size/order constraints and exact published row URLs.
Chapter zero and verified decimal addresses are accepted; no address is generated
from a chapter number. Decimal support is covered by a synthetic parser contract,
not a claim that a live decimal reader was sampled.

## Actual bounded public observations

`sunovels-direct-evidence/public-report.json` records HTTP status, bytes, timings,
response hashes and classification, without chapter body text. Fifteen GETs total,
zero retries and zero gateway calls; starts were separated by at least 1.5 seconds.
Total uncompressed response bytes: **2,106,293**. Observed response latency:
**246–1,674 ms**. All sampled responses were HTTP 200; this does not imply public
readability. No cookies, sessions, login, CAPTCHA bypass or stealth headers used.

| Work | Verified index | Reader result |
| --- | --- | --- |
| birth-of-the-demonic-sword | Page 1: 50 rows starting at `/0`; page 2: 50 rows; declared 2,336 rows over 47 pages | `/0`: unlocked valid content; diagnostic counts 14 sanitized paragraphs and 2,769 characters |
| reverend-insanity | Page 1: 50 rows; page 24: 50 published rows with 48 locked addresses and two conflicting address groups; declared 2,343 rows over 47 pages | `/1`: public valid content; `/1153`: locked and independently ambiguous |
| shadow-crown-the-rebirth-of-the-cursed-prince | Two source rows; both visibly locked | `/1`: locked with no public chapter content; Start Reading has no eligible first chapter |

English `Reverend` and Arabic `القس` each returned two works. Broad `a` returned
12 works; an improbable no-match query returned zero. HTTP 200 is never itself
used to mark a chapter readable. No full 47-page traversal was performed during
diagnostics and no novel-wide chapter crawl is performed on app startup.

## Local metadata, refresh and request budgets

The implementation reuses `NovelHttp`, the existing atomic `NovelMetadataStore`,
existing novel registry, index checkpoints and existing SQLite download queue.
There is no new transport or metadata database. A small derived in-memory search
index normalizes Arabic marks/alefs/ta marbuta, original titles, aliases and authors.
It is rebuilt only when the persistent registry changes. It requires no Android
FTS5 availability. Source search still uses the public website; local search and
browse are used for immediate/offline discovery with an explicit cached-data notice.

The optional bootstrap is loaded only on first Debug direct discovery, not app
startup. It contains 1,461 accepted generation-4 works, sourced through the existing
permission-gated acquisition data. Export checks the existing collection,
redistribution, source identity, scope review and expiry gates. It does not invent
permission or publish new content. The seed is discovery metadata, not chapter
availability evidence. Existing observed editions win over seed entries.

Seed: **695,271 uncompressed bytes / 105,981 gzip bytes**. Embedded only in
`app/src/debug/assets/novels/`, with a version/count/size/hash receipt. The `.gzip`
extension keeps Android asset merging from silently expanding `.gz` into a plain
JSON asset. The APK inspection verifies the exact compressed bytes.

Fresh discovery TTL is five minutes; persisted direct discovery may be displayed
for 30 days and older packaged metadata remains an explicitly historical fallback.
Fresh details TTL is ten minutes, with persistent last-good details shown immediately
while source refresh runs. Source HTTP metadata has a two-minute bounded memory
cache (eight entries, two million characters). Conditional validators are used only
when returned by a source and not prohibited by `no-store`. These live source pages
returned no ETag/Last-Modified and declared no-store: no real 304 savings are claimed.
The synthetic HTTP contract separately verifies conditional 304 handling.

Initial details index loading is at most **two pages**, independently of metadata.
Load More resumes rather than restarting. Resuming a partial direct checkpoint
revalidates its last page fingerprint before fetching up to two more pages: at most
three requests for that action. Explicit Retry starts a bounded fresh checkpoint
while preserving last-good rows. Range loading is bounded to eight pages per action;
large selections require further user actions. Existing requests for all chapters
cannot cause an unbounded Sunovels index traversal. Complete index freshness is six
hours. A changed total or boundary fingerprint rejects continuation and preserves
cached rows. Coverage completion and accessible chapter count are distinct.

Novel HTTP limits remain four requests globally/two per source host. Sunovels starts
are spaced by 1.5 seconds. Direct network/429/503 failures do not immediately retry;
shared exponential cooldown increases from 1.5 seconds up to 60 seconds, respecting
Retry-After up to one hour. Authentication/403 blocks are not bypassed and suppress
repeated URL requests for one hour. Challenges parsed from a 200 page also enter
that cooldown. Request bodies, timeouts and redirects remain bounded.

## Reader, missing chapters and downloads

Reader navigation uses the existing `prepareReaderChapter` preflight. It verifies
novel/chapter ownership, public record identity, matching chapter/title, lock state,
container closure, nonempty sanitized story content and destination. Locked or
ambiguous chapters cannot open an empty reader or be successful downloads. Conflicting
published rows are retained inside an unavailable address group, including all original
titles; they are not treated as interchangeable progress identities.

Verified pages checkpoint after every page and survive later failures. A removed
URL is marked unavailable only after a complete verified refresh confirms its
absence. Old URLs and user state remain stored. Previously validated downloaded
text remains readable when a later public listing locks/removes that chapter,
subject to the unchanged ownership/ambiguous-identity protections.

The existing reader prefetch remains one cached-index eligible neighbor after
reading approaches the end, only on an unmetered active network. It does not crawl
chapter metadata speculatively. Downloads retain two queue lanes, at most one active
chapter per source, atomic gzip files, checksums, generation/cancellation fences,
retry controls and offline paths. The disk schema and short-text reader contract
version remain unchanged. No account/library/bookmark/history deletion or migration.

The malformed-cache contract found that a bare truncated word could parse as a JSON
primitive and hide the backup. Metadata storage now validates object/array snapshots
before reading/writing and falls back to its existing backup for malformed files.

## Reproduction

From the Android root (public diagnostic requires the existing source repository's
BeautifulSoup environment):

```bash
/home/jalem/Downloads/mangaro-novel-sources/.venv/bin/python tools/sunovels-direct/inspect-public.py --sources-repo /home/jalem/Downloads/mangaro-novel-sources --output /tmp/sunovels-direct-public
/home/jalem/Downloads/mangaro-novel-sources/.venv/bin/python tools/sunovels-direct/inspect-public.py --sources-repo /home/jalem/Downloads/mangaro-novel-sources --output /tmp/sunovels-direct-public --extra-search-only
python3 tools/sunovels-direct/run-contracts.py --fixtures /tmp/sunovels-direct-public --output /tmp/sunovels-direct-contracts
./gradlew :app:assembleDebug
python3 tools/sunovels-direct/validate-integration.py
git diff --check
```

The diagnostic ceiling is 16 requests including robots. Extra search probes require
the previous report and do not repeat earlier requests. The JVM runner uses the
project's cached compiler/libraries; it may fetch the matching OkHttp JVM artifact
from Maven Central when only the Android artifact is cached. It extracts the actual
shared HTML helper, not a replacement Sunovels parser. Full raw captures stay in
`/tmp/mangaro-sunovels-direct-audit/public`; only count/hash observations are included
in the evidence directory.

Final verification: **48 host source/HTTP/cache contracts passed**. Local search over
1,461 records averaged **0.213 ms** over 100 host JVM queries; bootstrap parsing
was 32.991 ms and registry ingestion 762.354 ms. Host JVM used heap at report time
was 125,337,840 bytes with a 384 MiB maximum heap; this includes the diagnostic
harness and captured documents, is not Android RSS, and is not an app memory promise.
The complete source/transport replay took 28.654 seconds including deliberate
1.5-second request pacing. No Android performance measurement was performed.

Files changed in this task are enumerated exactly in
`sunovels-direct-evidence/changed-files.json`. The source repository, existing
SeaNovel implementation, actual download-disk implementation, novel reader screen,
Android build configuration and pinned signed rule envelope/key were byte-checked
against the start-of-task snapshot and remain unchanged.

Build output: `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`, application
`app.manhwaar.reader.dev`. Build/contract results and the exact APK SHA-256 are in
`sunovels-direct-evidence/validation.json`. Measurements are host JVM/source-side;
Android memory, device latency, UI behavior and migration acceptance are untested.

## Manual phone acceptance checklist

1. Install the ARM64 Debug APK as `.dev`, preserving the existing Release app and
   data. Confirm the default build has no gateway URL/flags enabled. Use a phone/router
   domain block for the staging gateway if desired; keep `sunovels.com` reachable.
2. Browse Sunovels, load another catalog page, and search `Reverend` / `القس`.
   Open each of the three exact works. Metadata must appear independently of index
   loading and from cache when connectivity is removed.
3. Birth: verify `/0`, read it, move to another verified chapter, and Load More.
   Each action must advance the bounded checkpoint rather than restart at page one.
4. Reverend: public chapter `/1` reads; locked/ambiguous `/1153` and `/1154` remain
   unavailable with original conflicting row titles. Do not try to unlock them.
5. Shadow Crown: both locked rows remain unavailable; Start Reading stays disabled
   and no empty reader session opens.
6. Interrupt an index update/network connection. Existing rows must stay visible;
   retry and Load More must recover without fabricating coverage or losing progress.
7. Queue several eligible chapters, pause/cancel/retry a task, then switch to airplane
   mode and reopen downloaded chapters, bookmarks and saved reading positions.
   Locked/empty chapters must never be marked downloaded.
8. Close/reopen the Debug app offline: cached discovery, details and indexes survive.
   Existing valid local downloads remain readable. Confirm no full-catalog HTTP scan.
9. Check SeaNovel, other novel/manga sources, account sync and InMobi remain intact.
   Record phone/API level, latency, disk/memory observations and failures.
10. Report explicit acceptance or defects. Infrastructure removal requires a separate
    explicit confirmation after successful acceptance; this checklist does not authorize it.
