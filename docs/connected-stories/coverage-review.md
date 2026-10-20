# Connected Stories coverage review

The bundled graph remains a small reviewed **cross-format relationship graph**. It is not an Atlas identity database, a feed, a cloud table, or a crawler. The screen has no relationship explanation, provider label or confidence score.

## Acquiring another relationship

1. Collect bounded public work metadata through existing source contracts, without chapter bodies. Keep exact source IDs, source-owned routes (including meaningful fragments), real covers, author/artist and publication version. Store small metadata projections only.
2. Use supplied aliases to retrieve **candidates only**. Obtain an explicit original/adaptation pair from the publisher or a catalog with typed relationships. Shared names, authors, and `source=WEB_NOVEL` alone do not establish that exact pair.
3. Record both publisher work URLs and the explicit relation evidence. Review sequels, side stories and alternative adaptations independently.
4. Run `python3 tools/connected_stories/review_candidates.py input.json new-review.json`. The offline tool indexes supplied names and exact editions. All output stays `PENDING`; it cannot fetch, approve, modify the application registry or publish anything. Maximum 1000 metadata rows, 64 relations, 100 name hints, 2 MB input; output creation cannot overwrite an earlier file.
5. Review both provider endpoints and their identities, then add a verified record to `connected_story_relationships.json`. Keep evidence/reviewer/date and run registry, resolver, production observation, navigation, and source contract tests. The original novel key uses the existing `NovelIdentity.initialWorkId` seed. Comic keys reuse existing reviewed `work:` keys or existing `VerifiedCatalogIdentity` external-ID namespace. Never create an Atlas mapping merely to support a card.
6. Confirm both routes in the actual details screens. Unknown, conflicted, revoked or missing target metadata hides the optional card. No chapters, progress, XP or library entries are merged.

## Runtime coverage

Exact enrolled editions remain supported. An additional source edition can resolve through **existing trusted same-format external IDs or an existing reviewed comic work key**. Registry indexes constrain lookup; conflicts and revoked exact editions still fail closed. Ordinary novel catalog co-membership (which may be provisional) does not authorize new adaptation links. Identity-enrichment events re-evaluate the read-only observation, including revocation. The optional cold-target metadata refresh retains its existing bounded budget; absence from the current search cache is not permanent unavailability.

## Reviewed extension: Overgeared (2020)

Publisher: https://tapas.io/series/overgeared-novel/info explicitly labels and links its comic adaptation, https://tapas.io/series/overgeared/info . The comic creator also points to the novel at https://tapas.io/episode/2294307 . Only bibliographic relationship facts are retained, not episode content.

The accessible MangaTime record `/manhwa/overgeared#6a8f5babf1a5e87af68c564b` supplies Sae-Nal Park / Team Argo and identifies the 2020 adaptation. Its catalog reference is https://anilist.co/manga/117460 . This is distinct from the discontinued 2017 adaptation, AniList 105664. SeaNovel's accessible prose record is `https://seanovel.org/novels/overgeared`, author Park Saenal. Sanitized metadata fixture: `app/src/test/resources/connected-stories/overgeared-public-metadata-20261020.json`. These are reviewed exact provider identities, not global title rules.

## API and license assessment (2026-10-20)

- AniList GraphQL supports `relations.edges.relationType`, node IDs/formats and `source`; it does not necessarily contain a Korean web novel node. Bounded live queries found `source=WEB_NOVEL` but no source relation for Return of the Blossoming Blade, and alternative adaptations for Overgeared. No automatic link was inferred. Official schema/docs: https://docs.anilist.co/reference/enum/mediarelation and https://docs.anilist.co/guide/terms-of-use . The terms limit data-storage/backup usage and require contacting AniList for commercial API use. **No new runtime AniList integration, bulk synchronization, SDK, copied code or dependency was added.** A broader commercial metadata service requires a separate terms assessment/permission.
- Tapas publisher pages expose an explicit original/comic relation for the reviewed Overgeared pair. Public bibliographic facts and canonical evidence URLs are recorded manually; no Tapas API contract, redistribution license for content/art, or chapter-copying permission is assumed. Existing source covers continue to use the existing image loader.
- MangaTime public tRPC search/details and SeaNovel metadata/index were inspected through the adapters' already supported public contracts. No changed source endpoints/parsers or added catalog crawl runs on Android.
- The offline review tool is original project code using Python standard library only. No third-party matching code or new Android dependency was added.

## Intentionally unapproved candidates

Otherworldly Evil Monarch: current SeaNovel prose metadata and reviewed comic editions exist, but this review did not obtain sufficiently specific publisher evidence for the exact comic adaptation. AniList returned an alternative animation, not a source-novel edge. Remains unlinked.

## Reviewed extension: Return of the Blossoming Blade

Return of the Blossoming Blade was initially pending because AniList lacked a source-node edge. Subsequent independent publisher review established the exact relationship: https://navercorp.com/media/pressReleasesDetail?seq=30834 explicitly states that the same-title webtoon began adapting the novel in March 2021. https://series.naver.com/novel/detail.series?productNo=4130558 links its related webtoon, https://m.comic.naver.com/webtoon/list?titleId=769209 . Credits are Biga (original), ARCHE/LICO (comic); provider metadata independently supplies those credits. The exact MangaTime edition `/manhwa/return-of-the-mount-hua-sect#6a49e00234c268b77054833e` and SeaNovel prose `https://seanovel.org/novels/return-of-the-blossoming-blade` are now reviewed and enrolled under the existing external-ID namespace, AniList 132144. A search response also contained unrelated Magic Emperor; that record and Mount Hua Sect's Genius Phantom Swordsman remain unlinked.

Publisher evidence preserves meaningful numeric URL parameters. Only the exact Naver host/path contracts above (`productNo`, `titleId`, and press-release `seq`, a single positive numeric value) are accepted. Extra parameters, unknown query contracts, fragments and credentials remain rejected. Source edition URL validation is unchanged. No publisher chapter content is retained.

Coverage is not universal. Missing historical novel reading statistics and production Supabase access are separate issues; this feature does not change them.

NAVER publisher evidence is used as reviewed bibliographic facts and canonical URLs only. No NAVER SDK, paid-content API, copied prose/art, runtime publisher calls or new metadata license is introduced. A larger automated service requires its own terms review.

### Provider cover discrepancy found during visual acceptance

The actual MangaTime Mount Hua cover URL in the recorded fixture returns artwork that appears unrelated to the work. Title/original name, Biga/ARCHE/LICO credits and narrative metadata identify the reviewed work, and the publisher relationship is explicit. This is a source asset inconsistency, not title-only relationship evidence. No fabricated replacement cover or source change was made. Bidirectional navigation passed, but this edition's cover still requires provider correction or owner review and is not visually approved.
