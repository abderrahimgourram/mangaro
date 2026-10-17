# Reviewed catalog identities — 2026-10-17

## Observed defect and evidence

An optimized code38 Release on the API34 x86_64 emulator displayed two
Otherworldly Evil Monarch cards in Popular → More. Its existing bounded
CatalogReview diagnostic reported NO_SHARED_VERIFIED_IDENTITY for these exact
public source references:

| Source | Source ID | Local row at observation | Source route | Provider ID |
|---|---|---|---|---|
| Manga Starz | 6058166507489091982 | 25 | /manga/otherworldly-evil-monarch | unknown |
| MangaLek | 918460697583900080 | 47 | /manga/otherworldly-evil-monarch/#775 | 775 |

Local row IDs are diagnostic context, never canonical identity. Both editions
lacked reviewed or shared external identity evidence. Existing safety logic
therefore deliberately kept them separate. The maintainer explicitly confirmed
these two editions represent the same original work on 2026-10-17. The bundled
record captures that reviewed relationship, not a title-based inference.

Public evidence:
- https://starzmanga.com/manga/otherworldly-evil-monarch/
- https://mangalik.net/manga/otherworldly-evil-monarch/

## Independent implementation

ReviewedCatalogRegistry validates a bounded, immutable registry from the APK
resource. An opaque stable work key links exact source routes or IDs already
validated by the source adapter. Titles, covers, fetching time and chapter labels
cannot create a relationship. Meaningful query parameters/fragments are retained.
Conflicting active claims reject the update; pending/revoked records never group.
Existing remote reviewed mappings have precedence. External identity/format
contradictions still prevent merging through the existing WorkLinker.

CatalogWorkEvidence feeds the same resolver into Home, Discover, Popular, Latest
and Search. Loading the local registry emits the existing projection revision;
restored cards are re-resolved, with alternatives retained. No database rows,
community records, chapters, files or source implementations are changed. Old
account-scoped explicit edition choices remain readable after group creation.

Popular and Latest retain the prior largest-validated-index representative
policy: actual unique indexed entries, not the largest chapter number. Unknown
counts remain unknown; no chapter-index/body requests are introduced. Feed update
evidence remains separate from representative selection.

The local tools/catalog-review page supports explicit review and revocation.
It has no remote API, makes no network requests and exports reviewed JSON for
source review and the next signed APK. No mandatory filtering website/backend is
needed. Registry limits: 1 MiB, 2048 records, 32 editions per record. Lookup is O(1).

## Research basis and limits

OpenRefine documents why syntactic clustering alone cannot resolve semantic
identity: https://openrefine.org/docs/technical-reference/clustering-in-depth
The W3C Reconciliation API describes evidence-based entity reconciliation:
https://www.w3.org/community/reports/reconciliation/CG-FINAL-specs-0.2-20230410/
No Mangamello implementation was copied.

Other duplicate-looking pairs remain review candidates. Lord of the Truth Fan
has not been recovered with sufficient source identity evidence. No claim is made
that every catalog duplicate across every source is solved automatically.

## Deterministic regression results

162 selected JUnit tests passed, zero failures/errors/skips, including 10 new
registry tests. These cover Home/Search agreement, paging, cached-group splitting,
restart identity, provider aliases, conflicting external IDs, last-good updates
and account-scoped edition preferences. Chapter counts (850/90) in these tests are
authored fixtures, not live counts for this work. Nine Node review-tool tests
passed, including 64-bit identity precision, explicit approval, revocation,
conflicts and unsafe input rejection. Android visual acceptance and artifact
verification are recorded separately with the private APK report.
