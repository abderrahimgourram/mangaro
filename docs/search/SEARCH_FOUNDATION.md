# Shared search foundation

## Recovery and scope

Implementation starts from private recovery commit
`0c9f95b565b0ae5aca2343430128eaaf6f5eb025`. The recovery branch is immutable.
No persistent edition, chapter, library, account or download identity changes.
Advertising, novel content classification, source endpoints and release configuration
are outside this change. Search transport remains in the existing source adapters.

## Established problems

* Comic search owns a generation, a raw-result cache and a presentation selector.
  Novel search independently owns an epoch, Compose slots and a remembered selector.
  Their lifecycle and cache contracts cannot currently be tested together.
* Novel UI restoration keys only query/genre. Provider selection and the Sunovels
  status filter are missing; selector lifetime is keyed only by query text. The
  Compose-local epoch is recreated separately from retained screen memory.
* Comic source maps use a sorted-map comparator ending at display name/language.
  Different source IDs with the same name/language compare equal. Such a map can
  lose a source even though its response participated in an earlier projection.
* Comic cache records both raw rows and already rendered cards. Restoring cards
  directly would bypass revoked mappings; the current implementation correctly
  reprojects raw rows. That behavior must become an explicit shared contract.
* Existing Atlas groups authoritative identities and corroborated provisional
  relationships. Missing real cross-source evidence is not solved by a new cache
  or by merging equal titles. The previous short-title/creator and detail-cache
  recovery corrections remain part of the baseline.

## Target and ownership

1. **SearchContext** identifies content kind, trimmed query, ordered providers,
   languages and relevant filters. Query text alone is never a cache key.
2. **SearchSessionCoordinator** owns monotonic generations and acceptance of
   callbacks. Both frontends use the same contract. A -> B -> A creates a new
   generation, even when A has a valid raw cache entry. Cancellation remains in
   the existing coroutine owners; acceptance fencing is independent of whether
   an upstream operation cooperates with cancellation.
3. **SearchSnapshotCache** is a bounded, monotonic-clock, full-context cache.
   It stores source records and paging checkpoints; previous groups are only
   stability hints. Every cache hit passes through current identity rules.
4. **Source evidence adapters** remain MangaSearchEvidence and
   NovelSearchSelection.candidate. They preserve provider identities, declared
   aliases, actual index evidence and bounded metadata enrichment. No per-card
   chapter, body or image requests are added.
5. **SearchResultPresentationFilter** remains the single identity boundary,
   backed by Atlas/VerifiedCatalogIdentity and existing edition selection.
   A typed work-card projection maps its groups back to source-owned editions.
   Both comic and novel consumers use this projection, including incremental and
   cached results. No second matcher or canonical store is introduced.
6. **NovelSearchProjection** owns the production novel grid's eligibility,
   latest catalog metadata and grouping preparation. It runs off the UI thread.
   The screen consumes its output, while non-search discovery stays unchanged.

UI state continues to expose partial results, loading/failed providers and paging.
A source failure cannot delete another source's results. Same-context retry keeps
last-good content; another context cannot borrow it. Provider names/counts remain
confined to the existing deliberate edition chooser.

## Constraints

* Exact edition repeats collapse; all different source editions survive grouping.
* Reviewed links and trusted IDs retain provenance; contradictions veto a merge.
* Provisional creator/name corroboration never becomes persistent authority.
* Sequel, side-story and comic/prose distinctions remain intact.
* Novel eligibility runs before identity grouping; UNKNOWN remains eligible with
  uncertainty, and confirmed comics stay excluded.
* Actual indexed counts select representatives; labels and provider totals do not
  become verified counts. Counts are never summed across editions.
* Cached presentation cannot bypass current mappings or format evidence.
* No new network requests, persistent cache format or database migration.

## Reviewable phases and verification

1. Shared context/session/raw-cache/projection primitives with deterministic tests.
2. Comic ViewModel integration: full-context restoration, stale-result fencing,
   source-map identity, production source-response-to-card regression tests.
3. Novel screen integration: retained session ownership, full-context restoration,
   production grid projection tests and existing classifier/search regressions.
4. Cross-feature regression and paired corpus/performance evaluation. Record all
   baseline failures without weakening assertions. Private draft PR only.

Recorded public metadata from Solo Leveling, TBATE, Shadow Slave and their
negative examples supplements synthetic lifecycle workloads. Host production
pipeline replay is not physical-device UI acceptance and cannot establish that
every apparent duplicate has enough identity evidence to merge.
