# Connected Stories

Optional bidirectional **ADAPTATION_OF** edges, not same-work aliases. The immutable bundled graph uses existing comic reviewed-work keys and the existing novel canonical seed/edition contracts. It never modifies Atlas, catalog reconciliation, source IDs, chapters or personal records.

## Reviewed original-story pairs (2026-10-19)

- Solo Leveling: the three exact original comic editions already confirmed in `catalog_reviewed_links.json` ↔ `novel.seanovel:https://seanovel.org/novels/solo-leveling`.
- The Beginning After the End: the two exact original comic editions already confirmed there ↔ `novel.mknov:https://mknov.com/novel/618` and `novel.seanovel:https://seanovel.org/novels/novel-618`.

The adaptation statements are supported by the [Solo Leveling publisher](https://www.tappytoon.com/en/book/solo-leveling-official), [novel publisher](https://yenpress.com/series/solo-leveling-novel), [TBATE author](https://www.turtleme.com/tbate-comic) and [TBATE publisher](https://tapas.io/series/tbate-comic/info). Exact provider records were reviewed with the original-title and distinctive protagonist/premise information on [Solo Leveling](https://seanovel.org/novels/solo-leveling), [TBATE SeaNovel](https://seanovel.org/novels/novel-618) and [TBATE Mknov](https://mknov.com/novel/618). The Solo page identifies Sung Jinwoo, E-rank hunter and gates; the TBATE records identify King Grey and his second life in a magical world. This review is an explicit exact-edition record, not a runtime synopsis/title match. Provider nationality labels are not identity or prose/comic authority.

Mknov anonymous API metadata requests during review returned403; no access bypass/retry was attempted. Those observations are not a body-availability acceptance. No chapters were fetched for the feature. Public page observations may be cached by the research tool and are not permanent guarantees.

Ragnarok, ARISE, Jasmine and unreviewed extra candidates are not enrolled. No title-only expansion occurs. Revoked/pending/conflicted records, ambiguous edges, conflicting trusted IDs within a format or conflicting existing reviewed comic roots suppress the card. Novel and comic external IDs are compared within their respective format, never incorrectly required to be the same.

## Metadata boundary (repaired 2026-10-20)

`ConnectedStoriesRepository` observes enrolled comic routes and novel catalog editions. It remains a read-only projection. Missing metadata previously hid all cards until both formats had been visited independently.

The details-screen lifecycle now asks `ConnectedStoryMetadata` for an exact reviewed target only when no usable local target exists. It invokes the existing source details adapter (metadata only, `fetchChapters=false`), and uses the existing catalog insertion path without favoriting, progress, downloads or XP. At most three enrolled routes, eight seconds per attempt, twenty seconds total; failures cool down five minutes in a bounded 64-entry cache. Cancellation removes the attempt hint so returning to a screen can retry. Available offline metadata wins without network calls. No Compose-render-time requests, title search, crawl or chapter-body probe occurs.

Trailing slashes are lookup aliases solely for the three enrolled comic-provider route contracts; queries/fragments/identities are preserved. Original URLs and persisted rows are never rewritten. The graph still has two reviewed relationships. No new title-based enrollments were added.

The native novel details chooser includes eligible explicitly enrolled prose alternatives in a read-only projection; it retains its existing canonical ID/primary and never persists a cross-format merge. No relationship metadata is exposed in card copy.

Selection prefers known reader usability, then existing verified complete-index count, then stable enrolled-edition order. Chapter labels/advertised totals are not inputs. Index evidence does not certify all bodies. Query subscriptions and rendering are guarded by current account generation, although graph records themselves are public catalog metadata. A source failure produces null optional metadata; external lifecycle cancellation propagates. No progress, XP or library mutation is performed.

## Presentation and navigation

Shared minimal RTL component in phone/tablet comic details and native novel details. Headings are only «إصدار رواية» / «إصدار مانهوا». Existing cover loaders are used. Card pushes `NovelDetailsScreen(existing Novel)` / `MangaScreen(existing SQL ID)`. Back uses the existing Voyager stack. Origin-screen-key, graph, account and source checks reject stale/double clicks. No chapter starts, auto library additions or edition/progress transfer.

JVM tests cover resolver/registry, actual production repository flows and real Voyager push/pop without composing device screens. Static screen-wiring checks supplement these. Disposable-emulator tests additionally open both actual details screens, tap both cards, pop the original stack and verify no favorite/progress mutation. Cover identities use real public metadata; offline emulator images can be placeholders. Physical-phone acceptance and live image rendering still require owner testing. No new backend, remote feed, schema, signing/version change or dependency.
