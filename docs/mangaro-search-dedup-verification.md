# Cross-source search verification — 2026-10-12

Search now renders one selected source entry for each confidently matched work. Raw variants remain internal. Selected source IDs, manga/novel URLs, chapter ownership, library membership and reading history are not rewritten. Source-specific/migration searches retain their source scope; Sunovels remains excluded from active novel discovery.

The shared presentation selector uses trusted catalogue IDs, explicit titles/aliases, conservative Arabic/Latin normalization and contradiction checks for creators, media kind, formats, years and editions. Fuzzy matching needs a corroborated creator plus independent year/format evidence. Every cluster member must match every other member. Uncertain identities remain separate; covers and speculative translations are not matching inputs. A new contradictory metadata response may split a group without duplicate lazy-list keys.

Counts come from owned, digest-checked SQL chapter rows for manga and verified persisted chapter-index snapshots for novels. Duplicate URLs and existing trusted source-local chapter aliases count once. Distinct chapters with identical display numbers stay distinct. Highest chapter numbers, URL suffixes and advertised totals are never ranking inputs. Complete novel snapshots require terminal pagination and agreement with any declared total.

Ranking first compares actual verified distinct entries. Reading availability, index completeness, verification recency, configured source priority and stable source ID break ties. Partial counts are lower bounds. A lower bound can beat a smaller known complete count, but two unfinished indexes cannot prove final order: the provisional selection remains stable until stronger evidence arrives. Cards show actual selected-source metadata and mark incomplete comparisons as provisional.

Ordinary search performs no chapter-index traversal or chapter-text downloads. It reads existing proof stores for likely duplicate candidates and allows at most four metadata enrichments per accepted query, two concurrent requests, a six-second budget. Source search requests retain their existing bounds. Query generations, per-query selection sessions and cached group keys prevent stale responses and pagination duplicates.

Live evidence on emulator-5554 / Pixel 4a / API 30:

| Query | Evidence | Selected result |
|---|---|---|
| Manga: Solo | Before: 96 source cards; after: 43 work cards; 22 matched groups, eight responding sources | Unknown counts honestly provisional |
| Manga: Solo Leveling | SQL-verified distinct entries: Azora 204, MangaSwat 201, Hijala 180; actual Azora/Hijala images decoded | Azora 204; provisional because four other candidate counts remain unverified |
| Novel: نظام مصاص الدماء الخاص بي | Three raw entries, two variants for the same novel | One matched work card; provisional count unavailable |
| Novel: عبد الظل | Complete published indexes: Cenele 3215, SeaNovel 3190, Kolnovel 712; genuine sample texts: 97, 98, 62 paragraphs respectively | Cenele 3215, largest among all three verified candidates |

Kolnovel's count is the distinct published index, not a claimed official/global novel total. Acceptance-only indexing was explicitly separated from ordinary search and used genuine source responses. Manga seeding skipped any existing chapter rows or favorites, preserving user chapter state; no synthetic production evidence was created.

Source-specific manga blockers: Manga Starz transport/index verification, MangaDar, TeamX and MangaLek did not establish COMPLETE count authority in the bounded acceptance check. They remain unknown candidates; no parser defect is inferred from challenge/transport behavior.

Timing observations are not controlled benchmarks: the earlier Solo harness took 12.92s; the initial post-change Solo harness took 28.34s including a fixed metadata wait and different network conditions. The cached Solo Leveling run completed source searches in 5.21s and the entire UI harness in 13.26s. Novel Vampire System before/after harnesses took 8.19s/9.93s; Shadow Slave acceptance including samples took 17.22s. Final ordinary searches on the final APK passed again: Solo Leveling 13.34s, Shadow Slave 10.15s, including the fixed metadata wait. No guaranteed latency improvement is claimed.

Focused tests: 88 passed, zero failures/errors, one skipped. They cover aliases, Arabic normalization, media/edition separation, source-owned identity, actual SQL counts versus 3333/9999 display numbers, pagination overlap, interrupted/query-changed work, stable cached cards, provisional evidence, unavailable-source fallback, cache compatibility and reading progress. Debug app/test compilation passed. The skipped JVM illustration-decoding case requires Android.

Screenshots, exact logs, test totals and preservation hashes: `/home/jalem/Downloads/Mangaro_Search_Dedup_20261012/`. Eight existing novel library/position files retained identical hashes across final live acceptance. Earlier indexing, download, cover, reader-scope and signed-source work was preserved. No publisher workflow, production revision 4, key, version, Android remote or APK release was changed.
