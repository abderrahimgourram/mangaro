# Phase 1 — search foundation acceptance

Initial branch: feature/mangaro-search-foundation-rework. Initial HEAD:
`fc21b82081c6bb1e1e2c24862d9301a968d15cae`. Initial worktree/index clean.
Development continues on `feature/mangaro-255-stability-20261018`; no integration
merge, public push or recovery-history change. Private recovery remote remains
`0c9f95b565b0ae5aca2343430128eaaf6f5eb025`.

## Evidence and disposition

The provider-map ID tie-breaker, frozen full-context session ownership, raw-cache
reprojection, background identity matching and shared NovelSearchProjection are
still present. Existing tests exercise real source-response-to-ViewModel state,
routing, pagination, stale provider callbacks and cache return. Novel format
eligibility precedes grouping. No replacement identity engine is necessary.

Normal Gradle debug compilation and 142 focused tests passed, zero failures or
skips. Command: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest` with
filters SearchFoundation*Test, SearchFoundationProductionTest, SearchExperienceTest,
SearchDeduplicationRegressionTest, SearchEnrichmentLifecycleTest, NovelSearchProjectionTest,
NovelSearchSelectionTest, AtlasProductionSearchTest, AtlasRecordedEvaluationTest,
AtlasCoreSafetyTest, SearchResultPresentationFilterTest, NovelContentType*Test,
VerifiedCatalogIdentityTest and MangaTimeSearchEvidenceTest (`--console=plain`).
Runtime: 169.06 seconds. JUnit evidence retained privately outside Git.

Owner-reviewed Solo Leveling editions 3 -> 1, TBATE 2 -> 1, and recorded Shadow
Slave text editions retain grouping; distinct sequels/side stories and contradictory
identities remain separate. Existing corpus is replayed, not newly scraped or
phone-observed. Unresolved providers with insufficient identity metadata remain
unmerged. See docs/search/VERIFICATION.md for corpus cardinalities and methodology.

The historical three SearchExperience and one MangaTime fixture-contract problems
remain corrected; no assertions were weakened in this phase. The eight legacy
cloud fixture ownership failures are NOT part of this green search result and
will be addressed separately in phase 3. Private PR Dependency Review remains
blocked by repository entitlement, not accepted security review; no workflow is
disabled. PR #1 stays draft and unmerged.

## Preservation and acceptance

Phase 1 adds this report only; all production source is byte-identical to initial
HEAD. No ads, sources, format classifier, auth, downloads, library, progress,
version, signing or cloud configuration changes. No device operations.
Host acceptance passes for this scope; physical-screen acceptance and unknown
real-world identity pairs remain owner review items.
