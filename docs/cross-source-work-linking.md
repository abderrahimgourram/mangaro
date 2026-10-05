# Conservative work links

Manga/source IDs and routes remain authoritative. `WorkLinker` derives deterministic canonical
**snapshot** relationships over already-known candidates. Snapshot IDs identify the current
member set, not permanent database IDs; they are never used for progress, downloads, community
keys or cloud identity. No SQL migration, title search, crawling or additional network fetches.

Discovery grouping now requires a shared external catalogue ID or normalized exact title/alias
plus a known matching author/artist. Source-local IDs are never treated as shared IDs. Conflicting
catalogue IDs, creators (for title matches), supplied year/format/edition and edition/number markers
reject matching. Every member pair must match, preventing unsafe transitive alias bridges.
Uncertain entries remain separate, including identical titles lacking corroboration.

Adapters may provide existing factual evidence through `SManga.memo["mangaro.workIdentity"]`:

```json
{
  "externalIds": {"anilist": "catalogue ID"},
  "aliases": ["source-supplied alternative title"],
  "year": 2018,
  "format": "manhwa",
  "edition": "original"
}
```

Accepted catalogue namespaces: anilist, myanimelist, mangadex, mangaupdates, kitsu. Evidence is
optional; no translations, aliases or IDs are synthesized. Existing adapters inspected here do
not currently populate this schema. Their ordinary title/author/artist metadata still supports
corroborated exact matches. Cross-language matching requires an adapter to supply actual evidence.
Discovery carries only this structured evidence into the existing manga memo, not arbitrary memo
fields. Details refresh updates the existing preference cache, with no extra source calls.

`ChapterListIntegrity.actualChapterCount` uses reconciled valid unique chapter routes, retaining
chapter 0, decimals, scanlations with distinct routes, specials and unnumbered rows. It never reads
claimed totals or maximum chapter number. Unknown counts remain null. Failed/in-flight checks
preserve the last successful count/time; successful COMPLETE/PARTIAL reconciliation updates them.
Existing proof state/digest still governs chapter completeness and missing-chapter diagnostics.

Aggregated discovery exposes `canonicalWorkId` and `actualChapterCount` in its existing model.
Selection prioritizes actual count, then successful refresh time, completeness/availability quality,
then stable source/manga ID. Cached usable winners remain available through temporary source
failure. Individual Library/Details records and reading preferences are not redirected or merged.
No new visible count badge is added where the existing design has no chapter-count field.

Manual checks: corroborated translations group; uncorroborated/sequel entries stay separate;
200 actual rows despite 230 claimed lose to 230 actual rows; temporary failure retains the winner;
explicitly opening original Library entries keeps their own chapters/history/downloads.

## Changed files

- domain/src/main/java/tachiyomi/domain/manga/service/WorkLinker.kt (new model/matcher)
- domain/src/main/java/tachiyomi/domain/chapter/service/ChapterListIntegrity.kt
- domain/src/main/java/mihon/domain/source/discovery/model/SourceDiscoveryItem.kt
- domain/src/main/java/mihon/domain/source/discovery/interactor/GetSourceDiscovery.kt
- app/src/main/java/eu/kanade/tachiyomi/ui/home/GroupDiscoveryItems.kt
- app/src/main/java/eu/kanade/tachiyomi/ui/home/PreferredMangaVariants.kt
- app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeViewModel.kt
- app/src/main/java/eu/kanade/tachiyomi/ui/home/DiscoveryCategoryGridViewModel.kt
- app/src/main/java/mihon/domain/source/interactor/UpdateMangaFromRemote.kt
- app/src/test/java/tachiyomi/domain/manga/service/WorkLinkerTest.kt (new)
- app/src/test/java/eu/kanade/tachiyomi/ui/home/CanonicalWorkPreferenceTest.kt (new)
- app/src/test/java/eu/kanade/tachiyomi/ui/home/GroupDiscoveryItemsTest.kt
- app/src/test/java/eu/kanade/tachiyomi/ui/home/HomeDiscoveryAggregationTest.kt
- docs/cross-source-work-linking.md (this document)

Validation: 56 focused tests passed (zero failures/errors/skips) and :app:assembleDebug passed.
No provider/runtime verification, release/version changes, app installation, ADB or emulator use.
