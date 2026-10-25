# PR13: findings after the af79 signed Release

This continues PR13; it does not replace its existing search or catalog pipeline.
The af79 JVM/CI success is not acceptance of subsequent edits or of the Release UI.

## Reproduced defects

The signed af79 Release on emulator-5556 kept two corroborated comic groups apart:
one source declared `webtoon`, another `manhwa`, with matching declared names and
TurtleMe credit. A string-format veto ran before identity comparison. Shared
`CatalogFormatCompatibility` now separates reader medium, concrete publication
subtype and delivery format. Compatibility removes a veto; it cannot prove same
work identity. Concrete conflicting subtypes, prose/comic, editions and trusted
identifier contradictions remain vetoes in the existing complete-link resolver.
Home's authority matching and source metadata cache use the same compatibility.

Full multiword queries also admitted unrelated works because a single common word
was sufficient. Query scoring now requires informative-token coverage or bounded
token typo evidence. Articles do not make an unrelated title a meaningful match.
Acronyms remain derived relevance hints, never work identity. Weak source matches
remain explicitly available as uncertain additional results.

An exact early title could consume the four-request metadata budget before later
providers arrived. The early phase now reserves two slots even for exact matches;
completed sparse detail responses cannot starve never-checked candidates. The
four-request limit, cancellation and per-source pacing remain in force.

## External bibliographic declarations are not verified identifiers

MangaDar's explicit work-level source row was ignored. Its source-bound detail
parser now retains allowed catalogue references separately as `declaredExternalIds`
with status `PENDING_VERIFICATION`. They are deliberately NOT `externalIds` and
cannot supply grouping authority. Footer/related links, arbitrary hosts, unsafe
URLs, article posters, malformed namespaces and a different work's redirect do
not supply declarations. Contradictory declarations fail rather than picking one.

A captured public Erica source row links to MangaUpdates `hyi03qs`; the anonymous
official API lookup of decimal ID 39091387636 returned HTTP 404 during review.
That is not verified identity and has not been promoted. Another normal public
catalogue record was accessible, so a valid URL shape alone would have been an
unsafe shortcut. The Starz public page supplied no original creator credit; its
Article author was a poster, not the work author. No creator was invented.

This extraction does not claim that every external catalogue reference has been
validated, or that missing-author Erica editions are proven equivalent. Unknown
identities stay separate; a prose edition cannot become an interchangeable comic
source. A future promotion requires an actual first-party record, matching work,
medium/adaptation, source binding and conflict checks within the existing budget.

## Acceptance boundaries

The earlier automation excluded cards with identical visible titles and could
therefore click a weaker unrelated Mount query result. This is a test-locator gap,
not proof of an application navigation defect. Updated local QA records ambiguous
source binding rather than inferring it from the title and does not skip the best
relevance card for that reason. A quiet observation window is distinguished from
a timeout. The frozen held-out corpus is 40 candidates, not 40 verified successes.

No library/source identity, chapter record, pinned edition, progress, XP, account,
advertising, production SQL, signing, version or public rollout policy is changed.
PR13 remains subject to exact-head CI and genuine signed Release acceptance.

The first resumed full run found one old phase expectation: it required all four
exact-match requests even while `allowWeakMatches=false` reported pending providers.
The regression now requires the first two exact requests at that phase AND the
original complete four-request ordered list after completion. This retains the
final exact-match priority assertion and adds the newly required reservation check;
no assertion was removed to suppress a network or production failure.

Local verification of these edits: 1,389 passed, 20 optional skipped, zero failures
(1,409 total app/domain/core-common cases), with SQLDelight verification. The
actual baseline-aware Spotless gate passed; the full repository still has 476
unchanged legacy violations, not a clean full-Spotless status. OSV passed on 866
resolved Maven versions; its ten negative tests and the nineteen formatting-policy
tests passed. All sixteen protected advertising source hashes matched. These local
results do not replace new-head GitHub CI, signed Release or held-out acceptance.

## The next signed Release exposed a second veto

The correctly featured 4d Release still rendered two tbat/TBATE groups. Its real
records included a five-name comma artist enumeration, a semicolon artist/studio
list and a third edition naming one artist. The earlier format-only fixture had
omitted these fields. A new captured-data fixture first failed, then was extended
to all three editions and failed again until every member pair was corroborated.
Complete-link conflict protection has not been relaxed.

Artist strings remain intact. Strict semicolon-credit subset matching is unchanged.
Extra artists are not exhaustive work-identity contracts: an otherwise conflicting
list may be corroborated by a shared writer, independent exact bilingual full
names and an informative art credit. Without bilingual names, an exact full work
title and writer need a whole matching complete personal-name/alphanumeric penname
label from an enumeration. A comma surname/given-name fragment, personal-name
suffix, generic studio, disjoint artist or missing writer cannot supply that proof.
Conflicting IDs, concrete media, authors, years, sequels and editions still veto.
The result is reversible presentation evidence, never a new stored canonical ID.

An unrelated Attack on Titan work also remained primary for a full-title query
through partial token overlap. Partial matches now need token coverage on both
sides. Useful near-title substitutions remain meaningful; weaker candidates remain
accessible as uncertain additional matches instead of being deleted.

Current full local regression: 1,392 passed, 20 optional skipped, zero failures
(1,412 total). Actual baseline-aware Spotless passed, 476 inherited files unchanged;
all 16 protected ad sources remain byte-identical. Existing dependency inventory and
OSV evidence is unchanged (866 versions, PASS); new-head CI must recheck it. These
results still do not constitute signed Release or held-out acceptance. The 4d APK
is explicitly a failed acceptance candidate, and PR13 remains draft until retested.

## Live source retrieval is independent of identity grouping

The full-feature original-key df71 Release grouped the three corroborated comic
editions for `tbat`, `TBATE` and the Arabic query into one primary group. This is
not universal acceptance: full-name queries and missing-creator editions still
contain unresolved identities. The Erica novel remains separate from comic
candidates. A frozen corpus consists of source-qualified candidates, not labelled
same-work ground truth; missing native editions cannot be credited by substituting
a similarly titled result.

A held-out displayed-title query exposed a separate MangaTime retrieval problem.
The adapter sent nonempty search queries with `sortBy: popularity`. Normal
anonymous requests to the source's public endpoint returned 24 broad popular
matches and omitted the requested native series ID. The actual public full-search
UI uses `sortBy: relevance`. Controlled comparisons retained query/page/limit:
changing batch transport alone or adding `searchMode: all` did not recover the
series; changing ordering to relevance did, placing the exact series first.
The undecorated query already returned four results under either ordering.

The fix changes only the nonempty textual search ordering. Blank-query discovery,
popular/latest requests, page numbers, page size, response pagination, work IDs,
chapter endpoints and reader behavior remain unchanged. It neither guarantees
source recall nor turns broad source matches into verified identity. Source-local
relevance can still return unrelated candidates; shared query scoring retains the
explicit uncertain-match boundary. No work title is special-cased.

A new production-request regression failed on the original popularity ordering,
then passed after the change. Separate assertions retain blank/popular discovery
and verify unchanged query, limit and page. Local evidence includes actual public
UI request shape and isolated parameter comparisons outside Git; it is not a
mocked response presented as live acceptance. Final CI and the new signed Release
must validate this commit before any merge.

## Terminal Compose state is separate from resolver correctness

A later signed-Release Mount query showed an older 188-chapter edition in the
card while the same group's chooser and policy exposed a different recommended
edition. Opening the chooser triggered another composition; subsequent navigation
reached the 194-row source. This lifecycle discrepancy cannot be accepted from
resolver snapshots alone. Initial detail captures also preceded asynchronous
related-story loading; the settled card and actual two-way navigation were then
observed. An early missing card is not a persistent relationship failure.

A new regression composes the actual production SearchViewModel.getManga inside a
stable work key, changes the preferred source edition and delays the database flow.
It failed on the original implementation: the displayed model kept the old ID,
source, route and title. produceState's effect keys restart collection but do not
reset its remembered value. The state is now scoped by account token, row ID,
source and route, keeping the work's outer scroll key intact. A late emission from
the previous edition cannot override the new initial model. A second scenario
checks a changed synthetic account token on the same route; it does not change or
claim acceptance of authentication itself. Android tracing is stubbed only in the
JVM harness, which uses the real Compose runtime and production ViewModel method.

This fixes a caller-state boundary, not another work matcher. New full tests,
exact-head CI and signed Release verification remain mandatory; the previous c3db
CI/Release does not validate these subsequent edits. Unknown-identity sources
still cannot be merged by title or artwork alone. Public MangaTime metadata for
the newly retrieved work actually has null author/artist and empty alternatives;
no uploader or invented author is substituted. Its real application index holds
205 distinct routes and the original native work ID, separate from reader-body
acceptance and from identity grouping.


## Fuzzy relevance must explain the candidate as well as the query

A later frozen-corpus Release observation exposed another general relevance gap:
a long, unrelated title satisfied every query token through scattered prefixes.
The complete-prefix and typo branches bypassed the partial-match candidate
coverage check, promoting that record to a primary search card. This is a query
matching failure, separate from edition identity resolution.

Fuzzy and prefix promotion now require substantial candidate-token coverage for
multiword queries, using the existing bounded token/edit-distance limits. Exact
names, source-declared aliases, whole phrases, literal keyword matches and useful
single-token discovery retain their earlier behavior. Weak source records remain
in the additional-results section; no identity is invented and no edition is
deleted. New regressions exercise both the query matcher and the actual search
State's primary/additional partition, with positive typo, alias and prefix
controls. Two of the six tests failed before the correction.

This newly exposed corpus case is development evidence from now on. The frozen
corpus itself is unchanged. Its retrieval measurements must remain separate from
unlabelled same-work accuracy; previous Release/CI results cannot validate this
new change. Another signed Release and exact-head CI are required.
