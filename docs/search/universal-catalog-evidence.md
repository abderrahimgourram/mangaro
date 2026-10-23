# Shared catalog evidence refinement

This change extends the existing source, Atlas presentation, novel catalog and discovery pipelines. It introduces no independent search engine, backend contract, identity migration or personal-record consolidation.

## Candidate admission

`WorkCandidateClassification` classifies structural routes and explicit API resource types. Navigation roots, taxonomies, author/publisher listings, search routes and chapter routes cannot become discovery work cards. Plausible unfamiliar/native routes remain pending and visible. Titles, cover presence and unknown chapter counts are not vetoes. `HtmlWorkCandidateValidation` rejects canonical redirects to navigation and explicit root listing schemas; a nested chapter ItemList remains valid. The native Mknov parser respects resource types while counting raw API rows for pagination.

Search validates both initial and next pages before local conversion. Cached ranking, discovery fallback and novel catalog projection recheck the same rules without deleting saved library entries. An all-invalid nonempty response is an error, not confirmed source emptiness. This is targeted read-only cache invalidation, not a blanket data cleanup.

## Identity and authority

The existing resolvers retain medium separation, trusted-ID conflict vetoes, source-qualified references and corroborated aliases. Native novel grouping ignores unknown creator placeholders and accepts compatible trusted external identifiers; same-source records remain separate without such proof. Cenele's nationality kicker is no longer an original title. Existing country-only values are excluded from identity evidence without rewriting the record or URL.

Home's provisional presentation groups must not be promoted into native identity authority on a later search projection. Only source-owned native groups supply that authority. Public recommended comic editions are re-resolved against current reviewed records before expansion, so a withdrawn source reference cannot reappear from a cached reviewed key.

## Edition quality and navigation

The existing shared `CatalogEditionSelection` compares usable/non-conflicting indexes, genuine reader samples, distinct available chapter routes, completeness and source health. Labels and declared totals are never used as measured counts. When evidence is comparable, actual 950 beats 180 and actual 200 beats 33 regardless of chapter labels. A verified reader success retains priority over an untested larger index. Unknown is separate from confirmed complete zero. Equal quality uses stable source-qualified references rather than response arrival order.

Novel Home/catalog and Search reuse the same Atlas grouping and comparator. Partial index observations are published as lower bounds, with last-good complete counts retained across weaker refreshes. Index time and reader-access time are separate: fetching an index cannot renew an old reader failure or success. Search reuses the existing preflight, capped at two editions and one chapter per edition per query, with four-second timeouts. Existing metadata/index budgets remain bounded and cancellable. No completion, reading-position or XP event is created.

Comic Search publishes public index evidence to the existing bounded discovery preference cache. Only explicitly observed search editions, current account-session scope, enabled sources and verified same-medium identity can expand a default recommendation. Personal reading state is not copied. Provided cards keep public index evidence across account changes; recommendation promotion and explicit choices remain scoped. The cache is bounded to 1024 editions, expansion to 128 references.

Automatic grouping remains pure. Explicit source choices are applied when Home/Discover cards are rendered, and clicks use the captured exact ID. An asynchronous preference change cannot resolve a different destination during click dispatch. Existing library, download and detail destinations are not redirected.

## Review and verification

Regression tests cover candidate boundaries, real SearchViewModel admission before persistence, native mixed-resource API pagination, Home/Search evidence reuse, native/provisional authority, stale reviewed relationships, last-good counts, actual chapter routes, bounded reader preflight and deterministic incremental projection. Existing Search/Atlas/source/account/reader suites remain mandatory. Runtime evidence belongs outside Git; a fixture PASS is not a live-provider PASS.

Touched legacy Kotlin files retire their exact-content formatting exemptions and pass the original rules in full. Original formatter configuration and production advertising bytes are unchanged. No SQL, authentication, version, signing, public release or updater behavior changes are included.
