# Release catalog identity repair

This repair starts at PR #12 (`ae8562c7`) and addresses observed Release data,
not a replacement search or catalog engine. Runtime acceptance uses only
`emulator-5556`, the original signing key, R8, and real provider responses.

## Reproduced evidence gaps

- The native MangaSwat series response contains `alternative` and explicit `type`
  fields which the adapter discarded. A 193-route complete manhwa could therefore
  lack the related novel shown by its 188-route registered MangaTime edition.
- Query-owned `MangaSearchEvidence` retained structured identity but dropped the
  separate source-declared-name record on cache restoration. The adapter-to-cache
  regression failed with an empty alias set before correction.
- Creator comparison treated source-declared cross-script aliases and explicit
  family/given-name formatting as different people. The observed One Piece
  records use `Oda, Eiichiro` and `Oda Eiichirou (尾田栄一郎)`.
- Artist-credit lists were compared as indivisible strings: `LICO` conflicted
  with `ARCHE; LICO`. A complete credit subset is compatible; overlapping lists
  with contradictory additional contributors remain conflicting.
- Provider interleaving prioritized metadata absence before query relevance.
  Unrelated fast-provider rows could consume all four requests before late exact
  candidates arrived. The original failing regression requested the wrong rows.

## Conservative comparison and cache rules

The existing identity resolver, blocking index, selection policy and UI remain
in use. Titles alone never authorize grouping. Creator syntax normalization is
comparison evidence only, never a persisted canonical ID. Declared cross-script
aliases and explicit comma ordering are accepted. The narrow long Japanese
`-rou`/`-ro` spelling equivalence requires a declared Japanese name on an explicit
manga record, an additional exact name component, and the full remaining work
vetoes. No general edit distance, surname-only matching or title exception is
introduced. Conflicting declared Japanese names still veto this comparison.

Artist comparison accepts an exact complete subset using the explicit semicolon
credit delimiter. Commas inside a person's name are not separate artist votes.
The blocking index retrieves the same contributor evidence the resolver uses;
retrieval keys themselves are not identity authority. Media, sequel, edition,
trusted-ID, original-title and creator contradictions remain vetoes.

Source-declared names and format survive the bounded query cache, together with
its provenance fingerprint. Changed source records and explicit refresh invalidate
the cached proof. New exact query matches take precedence within existing request
budgets. While providers are pending, unrelated weak matches do not consume the
budget and two metadata slots are reserved for late exact matches. Weak but
plausible cross-language candidates remain visible and can be verified when the
query completes. No extra chapter bodies are crawled.

## Reviewed Connected Stories membership

`ReviewedStoryIdentity` supplements an EXISTING reviewed relationship with a
publisher-reviewed original name, translated name, named creator and explicit
medium. Both name families and the creator are required, bound to the actual
source, route and local edition. A title is only a cue for an eight-second,
account-scoped metadata refresh, at most once in five minutes. The UI runs that
opportunity before returning for missing identity. Failed, mismatching or
conflicting responses do not install relationship proof. This never creates a
new relationship, rewrites an external ID or moves a user's preferred edition.

The existing Mount Hua review is corroborated by the original publisher:

- https://m.comic.naver.com/webtoon/list?titleId=769209
- https://www.webtoons.com/en/action/return-of-the-blossoming-blade/list?title_no=2849

Its existing enrolled source and novel routes remain intact. The composite
membership mechanism has no story-name or source-ID condition in executable
code. An unenrolled edition without sufficient proof stays unresolved. Other
reviewed relationships retain their existing exact-route/trusted-ID behavior.
Revoked, ambiguous and conflicting editorial records continue to fail closed.

The only local persisted update in this metadata path is public identity memo,
initialization status and creator credits, using the current account token.
The repository re-reads the current row inside one transaction and checks source,
route, account and expected identity before merging only the public keys. A late
response cannot rewind newer chapter-integrity evidence or overwrite a private
memo. Partial catalogue rows retain fuller declarations only for the same route,
contract, title and non-conflicting medium; full details and changed contracts
replace the declaration. Unknown creator placeholders cannot erase known credits. Source URL, title, favorites,
chapters, read flags, bookmarks, downloads and XP are never rewritten.

## Validation boundary

Regression fixtures supplement actual signed Release acceptance; they do not
prove remote availability. The first local full suite and the first PR CI run
failed four MangaStarz live tests with HTTP 403. An isolated run passed all five.
The new metadata-cache test leaked a global `NetworkHelper` with the fixture user
agent `Test Agent`; enforcing class order reproduced the same four failures.
Saving/restoring the Injekt scope fixed the order dependency: all eleven ordered
cache, artwork and live-source tests passed without skips or weakened assertions.
Final full-suite and exact-head CI outcomes remain separate verification gates.

The ordinary full repository retains formatting debt; touched legacy files retire
their exemptions and pass the original rules through the approved exact-content
baseline gate.

## Related recommendation quality and artwork

Reverse related-work lookup now accepts up to sixteen additional source-qualified
routes from the existing bounded public-quality cache. Each must independently
satisfy the current reviewed relationship, current account token and enabled-source
policy. The fetched database row is checked again by the existing resolver. This
allows a verified stronger edition to participate without changing library entries,
progress, relationship enrollment or the original edition's chapters. Cached
adapter evidence remains separate from bundled authority so withdrawn review data
is re-resolved rather than resurrected from an enriched snapshot.

MangaTime's reviewed Mount Hua asset changed from two known bad filenames to a
third rotating asset for the same server series ID. The existing artwork correction
now validates the provider, HTTPS host and stable source series ID instead of an
obsolete asset hash. It uses the previously reviewed official Naver artwork;
unrelated works, other hosts and custom user covers remain unchanged. This is an
explicit artwork review entry, not a general identity inference from a cover or a
title. A regression covers the exact newly observed URL, unrelated series/host,
source mismatch and the unchanged custom-cover path.

## Universal query and catalogue follow-up (PR13, acceptance pending)

Live original-key Release observations on 2026-10-24 found 26 candidates for
`erica`, rather than the earlier owner-observed 16. Seven had Erica titles.
The other candidates included titles containing **America**: the old substring
relevance rule matched `erica` inside that word. TeamX's live search document
also explicitly returned two unrelated titles under the requested keyword; this
was not a footer-selector defect. Neither observation proves the seven
similarly named editions are the same medium or work. In particular, the
public MangaDar detail header declares one of them a novel, while the Azora
payload declares its record `MANHWA`. Missing credits stay missing.

`WorkSearchQuery` now supplies shared bounded title/alias/token relevance and
acronyms derived from known title tokens. These generated acronyms never enter
identity aliases, trusted identifiers or relationship authority. Ambiguous
acronyms can match several independent works. Interpretation cannot invent a
full title that no enabled source or existing metadata has supplied.

Search re-evaluates relevance **after** public detail/cache enrichment. A group's
best matching member determines query order; the existing availability/count
policy chooses its representative. The 200-route edition is not demoted merely
because its translated display name matches less exactly than a 33-route
edition. Weak source-only/infix matches remain accessible through an explicit
additional-results control. Fewer primary results due to relevance are reported
separately from actual same-work grouping.

Slash-stripped known catalogue roots, such as `/manga`, are directories too.
Ambiguous singular paths such as `/work` remain pending verification: an early
expanded rule incorrectly rejected these and was caught by the complete suite.
No title blacklist, zero-count filter or library cleanup is used.

MangaDar's source-bound header aliases and explicit format now survive native
parsing. A small sanitized real public header/credit excerpt is included in the
fixtures; its test wrapper supplies the structural context omitted by excerpting.
Azora retains source-declared alternatives and format. Its `createdby` publishing
account is no longer borrowed as a bibliographic author. No uploader identity,
response body or source authentication data enters identity diagnostics.

Home uses the existing conflict-aware Search policy for group membership and
the existing shared chapter-quality comparator for edition choice. Bounded,
account-scoped public Search index evidence can nominate a better Home edition;
the same complete-link resolver must still accept the whole group. No stored
library, chapter, bookmark or progress identity is moved. A parenthesized
translation annotation is comparable to the unmarked name only if the source
itself declares that unmarked alias; independent corroboration and all trusted
conflict vetoes remain necessary.

Mixed provider batches can contain separately typed prose and comics. Every
result group must contain exactly one medium. The former whole-batch exception
regression is replaced with explicit checks that both groups, every comic
edition, the 200-route winner and the separate prose route survive. The Home
negative fixture now actually omits creator evidence: it previously supplied
`Known Creator` while describing itself as title-only. Its two-card and unchanged
route assertions remain. Source-wiring assertions accept formatter whitespace
and braces while still requiring the exact production target and arguments.
Functional ViewModel, identity, navigation projection and preservation tests
remain independent of those wiring checks.

The original formatter rules are unchanged. Modifying the previously exempt
novel screen requires formatting that entire file and expanding its wildcard
imports; the large whitespace diff is not a reader/UI rewrite. Each touched
legacy exemption is deliberately retired with a reason. Advertising bytes remain
protected. Full-clean repository Spotless is still not claimed.

This follow-up is not final Release acceptance. The development and held-out
Release matrices, final exact-head CI and deployment decision must be recorded
separately. Unknown identity is not a successful duplicate removal; external
403/timeout is not zero chapters. PR13 must remain open if real acceptance still
finds an unresolved supported defect.
