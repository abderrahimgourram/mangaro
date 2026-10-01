# Manga integrity repair (2026-10-01)

Production path on emulator-5554 only; no Vivo access. Starting HEAD 9982fee.

## Reproduced causes

- MangaSwat TBATE manga identity `1702436`, source `7657007209499352344`: chapter endpoint `/v2/api/v2/chapters/?serie=1702436&order_by=-order&page_size=200` declares 256. Pages contain 200 + 56 rows, 256 unique remote IDs, zero repeated IDs. Chapter 235, ID 1743844, has `slug: ""`. Requiring a nonempty slug aborted the entire list, leaving the existing manga's SQL chapter count at zero. The API/Reader resolves chapters by numeric ID. Empty optional slugs now retain the numeric route and memo; other slugs retain pinned compatibility. Required IDs and exact pagination totals remain mandatory.
- Shared reconciliation matched moved remote identities and migrated local rows in place, but its deletion plan compared their old URLs. The same local IDs were then deleted (including cascading history). The regression test reproduced this deletion. Deletion now uses unmatched local IDs; incomplete/ambiguous results retain existing rows and collapse protection remains.
- Downloader page-list resolution did not call existing bounded stale-chapter recovery. Resolution/recovery now occurs before the existing filesystem identity lock, avoiding recursive locking; normal Downloader, queue, splitting and signed-image refresh remain intact.
- `copyFrom`, catalogue SQL insertion, and details cover updates accepted invalid optional values or erased optional metadata/memo/initialization. Network insertion now merges with the same source+URL row; invalid covers/empty optional metadata preserve valid values. Details enforce source scope. Coil previously cached successful non-image responses and had no cover refresh on stale 403/404/410. It now checks image representation and performs one bounded source details/image verification before persisting a replacement; failed repairs never write a cover. Concurrent failures are serialized per manga, with a 60-second failed-refresh cooldown.

## Live title variants (independent)

| Source | Available/parser/stored main-title chapters | Completeness |
|---|---:|---|
| MangaSwat | 256 / 256 / 256 (before: 0) | COMPLETE |
| TeamX | 256 / 256 / 256 | COMPLETE |
| MangaDar | 270 / 270 / 270 | COMPLETE |
| MangaLek | 259 / 259 / 259 | DEGRADED: no authoritative total proof |
| Hijala | 245 / 245 / 245 | DEGRADED: no authoritative total proof |

Counts outside MangaSwat are production parser results, not separately proven server totals. The table's live label for those is the available returned list. No same-title/source variants were merged. The bounded English search did not find the main title in Azora/MangaTime; MangaTime's “Attack on Titan The End The Beginning” and Jasmine side stories are different works, not TBATE variants.

## Device acceptance

- MangaSwat catalogue-produced identity → details → 256 SQL rows → details UI → old Reader (7 pages), recent Reader (22 pages); slugless chapter 235 also produces valid pages.
- PARTIAL 12/256 result retained all 256 local IDs; process restart retained 256 distinct IDs.
- Blue Lock large regression: live/internal/stored 371, COMPLETE. Simulated old slug on existing downloaded chapter ID 970 reconciled in place, preserving read/bookmark/last page/date/history and all local IDs. Existing 42-page download rendered offline before/after restart.
- MangaDar TBATE chapter ID 3131: simulated stale route caused a real HTTP 404; real Reader refresh restored the produced URL on the same ID and rendered 57 pages, with 270 stored chapters. A separate simulated stale route through normal Downloader recovered in place, downloaded all 57 pages and rendered offline before/after restart.
- SQL empty/data/placeholder cover responses and empty optional metadata preserved Blue Lock's valid cover/metadata. A clearly labeled simulated stale cover URL returned a real 404; Coil refreshed real source details, verified the replacement image, wrote only cover fields and rendered 512×766. Restart rendered the persisted cover. No naturally broken live cover was reproduced in the bounded sample; therefore the requested natural broken-cover Home/details/library case is unverified, not claimed as passed.
- ApplicationInfo launcher label is `Mangaro`; internal Arabic resources/RTL and Reader directions unchanged.

## Checks

`./gradlew :domain:test :app:testDebugUnitTest` passes (75 domain Debug tests, 204 app tests). Focused regression coverage includes moved-row deletion, partial preservation, URL collision rejection, optional metadata/cover preservation, image validation, and source isolation. Opt-in device commands use `-e integrity` modes (`raw`, `verify` with MangaSwat source ID, `identity`, `cover`, `downloadRecovery`, `persistence`); never invoke the broad default source audit. Release build required separately.
