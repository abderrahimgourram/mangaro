# Short declared aliases at the final search presentation boundary

The PR #14 signed Release reproduced two primary cards under `master of`:
MangaDar `3975276517041363504:/manga/master-of-gu/` and MangaTime
`215553151312092548:/manhua/master-of-gu#6abc7e368ac42c14021471a4`.
Both declare manhua (COMIC). The Arabic edition explicitly declares the English
primary title as an alternative name. No conflicting external IDs or creators
are present on this pair. MangaTime's actual index contains 97 distinct native
chapter routes; MangaDar did not load its index during this reproduction. Its
missing index is unknown, not a verified zero. Public metadata and screenshots
are retained locally, outside Git.

## Exact failure

`WorkIdentityNormalizer.distinctive` requires two informative tokens of at least
three characters. The declared shared name contains a two-character name token.
Consequently `AtlasCandidateIndex.keys` omitted the Arabic edition's shared
alias, and `WorkIdentityResolverV2.presentationDecision` independently rejected
the same alias. The actual trace reports missing creator corroboration and no
shared retrieval key. The prior reciprocal-alias correction alone could not
reach this pair.

The groups were **not** independently constructed for primary and supplementary
results. `SearchViewModel.rankResults` passes all retrieved eligible candidates
through the same filter and projection, promotes each group's best query match,
then `State` partitions whole groups by relevance. Both reported cards were
already primary. Expanding supplementary results merely made the larger list
visible; it did not cause the identity split.

## Bounded correction

A presentation-only declared-name gate accepts two informative tokens of at
least two characters, with a long token and minimum full-name length. It is
shared by retrieval and the existing one-sided alias decision. It does not
invent translations or match arbitrary title substrings. Exact shared name and
declared primary/alias linkage remain required after every identity veto.
Canonical matching and its stricter distinctiveness gate are unchanged.

External ID, creator, original-title, medium, sequel, edition and complete-link
conflict checks remain authoritative. Artwork or chapter-count agreement cannot
override them. No source, library, chapter or history identity is rewritten.
Uncertain other editions remain separate when their evidence conflicts or is
insufficient. No title-specific production condition was introduced.

Supplementary expansion is ephemeral to the current input. It starts collapsed,
resets on input changes and is not saved across screen/process recreation.
The existing globally grouped result list supplies both the collapsed and
expanded collections and their unique group counts. An empty supplementary
partition has no toggle. Existing invalid/challenge-page eligibility filters
remain in force; no records are deleted.

## Regression evidence

The captured pair's retrieval, a different authored short-token translation,
and combined relevance/cache grouping tests failed on the old code. They pass
after correction. Separate negative cases retain novel/comic, side story,
conflicting external IDs/creators and group-wide consistency safeguards.
A real `SearchViewModel` regression covers asynchronous source arrivals, final
card/alternative projection, both native routes, distinct stored IDs and whole
group relevance partitioning. Release UI, CI and artifact evidence is recorded
separately; unit tests alone do not establish live-source acceptance.
