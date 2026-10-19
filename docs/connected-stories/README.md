# Connected Stories

Optional bidirectional **ADAPTATION_OF** edges, not same-work aliases. The immutable bundled graph uses existing comic reviewed-work keys and the existing novel canonical seed/edition contracts. It never modifies Atlas, catalog reconciliation, source IDs, chapters or personal records.

## Reviewed original-story pairs (2026-10-19)

- Solo Leveling: the three exact original comic editions already confirmed in `catalog_reviewed_links.json` ↔ `novel.seanovel:https://seanovel.org/novels/solo-leveling`.
- The Beginning After the End: the two exact original comic editions already confirmed there ↔ `novel.mknov:https://mknov.com/novel/618` and `novel.seanovel:https://seanovel.org/novels/novel-618`.

The adaptation statements are supported by the [Solo Leveling publisher](https://www.tappytoon.com/en/book/solo-leveling-official), [novel publisher](https://yenpress.com/series/solo-leveling-novel), [TBATE author](https://www.turtleme.com/tbate-comic) and [TBATE publisher](https://tapas.io/series/tbate-comic/info). Exact provider records were reviewed with the original-title and distinctive protagonist/premise information on [Solo Leveling](https://seanovel.org/novels/solo-leveling), [TBATE SeaNovel](https://seanovel.org/novels/novel-618) and [TBATE Mknov](https://mknov.com/novel/618). The Solo page identifies Sung Jinwoo, E-rank hunter and gates; the TBATE records identify King Grey and his second life in a magical world. This review is an explicit exact-edition record, not a runtime synopsis/title match. Provider nationality labels are not identity or prose/comic authority.

Mknov anonymous API metadata requests during review returned403; no access bypass/retry was attempted. Those observations are not a body-availability acceptance. No chapters were fetched for the feature. Public page observations may be cached by the research tool and are not permanent guarantees.

Ragnarok, ARISE, Jasmine and unreviewed extra candidates are not enrolled. No title-only expansion occurs. Revoked/pending/conflicted records, ambiguous edges, conflicting trusted IDs within a format or conflicting existing reviewed comic roots suppress the card. Novel and comic external IDs are compared within their respective format, never incorrectly required to be the same.

## Local metadata boundary

`ConnectedStoriesRepository` observes at most the enrolled comic exact-route records and existing NovelRepository catalog metadata. No remote search, source-detail/index/body request, account/cloud request or database insertion occurs. An unknown work exits before subscriptions. Missing target, inactive source, known unusable edition, missing actual cover, or confirmed comic in the prose catalog means no related card. Existing unknown-format policy is preserved for these explicitly reviewed prose editions.

The registry connects canonical work anchors; route lists enroll verified editions without inventing new permanent identities. Existing native details/edition choosers retain the full catalog's alternatives. `ConnectedNovelEditions` also supplies the exact reviewed prose members to the existing novel-details chooser when metadata exists in separate native catalog groups. This is a local details projection: it keeps the existing work ID/primary and selected edition, performs no catalog/database identity merge, and never includes the comic adaptation. Conflicting identities or format evidence veto the extra members. Opening details may perform the normal bounded index/details loading already owned by that screen; no such requests occur merely to render the related card. This conservative first version does not automatically extend the relationship to additional editions merely because a provisional same-title search group includes them. New verified enrollments require reviewed evidence. No relationship data is exposed in primary card copy.

Selection prefers known reader usability, then existing verified complete-index count, then stable enrolled-edition order. Chapter labels/advertised totals are not inputs. Index evidence does not certify all bodies. Query subscriptions and rendering are guarded by current account generation, although graph records themselves are public catalog metadata. A source failure produces null optional metadata; external lifecycle cancellation propagates. No progress, XP or library mutation is performed.

## Presentation and navigation

Shared minimal RTL component in phone/tablet comic details and native novel details. Headings are only «إصدار رواية» / «إصدار مانهوا». Existing cover loaders are used. Card pushes `NovelDetailsScreen(existing Novel)` / `MangaScreen(existing SQL ID)`. Back uses the existing Voyager stack. Origin-screen-key, graph, account and source checks reject stale/double clicks. No chapter starts, auto library additions or edition/progress transfer.

JVM tests cover resolver/registry, actual production repository flows and real Voyager push/pop without composing device screens. Static screen-wiring checks supplement these. Phone visual acceptance and image rendering still require owner testing. No new backend, remote feed, schema, signing/version change or dependency.
