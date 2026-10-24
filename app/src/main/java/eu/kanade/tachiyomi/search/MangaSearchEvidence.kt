package eu.kanade.tachiyomi.search

import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.focus.FocusEngine
import eu.kanade.tachiyomi.focus.FocusOperation
import eu.kanade.tachiyomi.focus.FocusTask
import eu.kanade.tachiyomi.focus.WorkPriority
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.RankedSearchResult
import eu.kanade.tachiyomi.ui.home.CatalogWorkEvidence
import eu.kanade.tachiyomi.ui.home.PreferredMangaVariants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.ChapterListIntegrity
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.service.VerifiedCatalogIdentity
import java.util.concurrent.ConcurrentHashMap

/** Query-owned evidence. Bounded list verification is restricted to competing same-medium editions. */
internal class MangaSearchEvidence(
    private val getManga: GetManga,
    private val getChapters: suspend (Long) -> List<Chapter>,
    private val storeMetadata: (suspend (Manga, Manga) -> Boolean)?,
) {
    constructor(getManga: GetManga, getChapters: suspend (Long) -> List<Chapter>) : this(getManga, getChapters, null)

    private val chapterVerifier = SearchChapterVerifier()
    private val metadata = ConcurrentHashMap<String, Manga>()
    private val counts = ConcurrentHashMap<String, SearchChapterEvidence>()
    private val attempted = mutableSetOf<String>()
    private var remaining = 4
    private var forceMetadata = false
    private data class Cached(
        val at: Long,
        val author: String?,
        val artist: String?,
        val identity: kotlinx.serialization.json.JsonElement?,
        val declaredNames: kotlinx.serialization.json.JsonElement?,
        val baseline: List<String?>,
    )
    private val details = linkedMapOf<String, Cached>()
    private var epoch = 0L
    private fun baseline(manga: Manga) = listOf(
        manga.title,
        manga.author,
        manga.artist,
        manga.memo[tachiyomi.domain.manga.service.WorkMetadata.MEMO_KEY]?.toString(),
        manga.memo[AtlasDeclaredNames.MEMO_KEY]?.toString(),
    )

    /** Cache restoration ranks synchronously, before SQL refresh. Reuse only still-valid,
     * source-qualified public metadata; never carry a group solely because it existed before. */
    @Synchronized private fun cachedMetadata(manga: Manga): Manga? = details[key(manga)]?.takeIf {
        System.nanoTime() - it.at in 0 until 300_000_000_000L && it.baseline == baseline(manga)
    }?.let { cached ->
        manga.copy(
            author = cached.author,
            artist = cached.artist,
            memo = kotlinx.serialization.json.JsonObject(
                manga.memo + buildMap {
                    cached.identity?.let { put(tachiyomi.domain.manga.service.WorkMetadata.MEMO_KEY, it) }
                    cached.declaredNames?.let { put(AtlasDeclaredNames.MEMO_KEY, it) }
                },
            ),
        )
    }

    @Synchronized fun resetBudget(
        recheck: Boolean = false,
    ) {
        epoch++
        metadata.clear()
        attempted.clear()
        remaining = 4
        forceMetadata = recheck
        chapterVerifier.reset(recheck)
        if (recheck) {
            counts.clear()
            details.clear()
        }
    }
    fun key(manga: Manga) = "${manga.source}:${manga.url}"
    fun requestMappings(rows: List<RankedSearchResult>) = CatalogWorkEvidence.request(
        rows.map {
            CatalogWorkEvidence.from(metadata[key(it.manga)] ?: it.manga)
        },
    )
    fun candidate(row: RankedSearchResult, priority: Int): SearchWorkCandidate {
        val current = metadata[key(row.manga)] ?: row.manga
        val manga = cachedMetadata(current) ?: current
        val identity = CatalogWorkEvidence.from(manga).let {
            it.copy(aliases = (it.aliases + AtlasDeclaredNames.aliases(manga)).take(24).toSet())
        }
        return SearchWorkCandidate(
            key(manga), manga.source.toString(),
            if (
                eu.kanade.tachiyomi.novels.NovelContentTypeClassifier.declared(listOfNotNull(identity.format))?.type ==
                eu.kanade.tachiyomi.novels.NovelContentType.TEXT_NOVEL
            ) {
                SearchWorkKind.NOVEL
            } else {
                SearchWorkKind.COMIC
            },
            manga.title,
            identity.aliases, identity.externalIds, identity.author, identity.artist, identity.format,
            identity.edition, identity.year, priority,
            evidence(manga).let { it.copy(readable = PreferredMangaVariants.readerUsable(manga.id) ?: it.readable) },
            catalogIdentity = VerifiedCatalogIdentity.from(identity),
            queryMatchRank = row.queryMatchRank,
            metadataOrigin = if (metadata.containsKey(key(manga)) ||
                manga !== current
            ) {
                "source-details-or-local-record"
            } else {
                AtlasDeclaredNames.origin(manga) ?: "source-result"
            },
        )
    }
    private fun evidence(manga: Manga): SearchChapterEvidence {
        val retained = counts[key(manga)] ?: cachedChapters(manga)
        return chapterVerifier.cached(manga)?.let { SearchChapterVerifier.merge(retained, it) } ?: retained
    }

    /** Same reconciled-index proof as Home; no chapter labels, advertised totals or new requests. */
    private fun cachedChapters(manga: Manga): SearchChapterEvidence {
        val count = ChapterListIntegrity.actualChapterCount(manga) ?: return SearchChapterEvidence()
        return SearchChapterEvidence(
            count,
            ChapterListIntegrity.retainedComplete(manga),
            checkedAt = ChapterListIntegrity.lastSuccessfulAt(manga),
        )
    }
    private fun authoritative(work: tachiyomi.domain.manga.service.WorkMetadata): Boolean =
        work.reviewedWorkKey != null || work.externalIds.any { (namespace, value) ->
            namespace in setOf("anilist", "myanimelist", "mangadex", "mangaupdates", "kitsu", "novelupdates") &&
                AtlasCandidateIndex.validId(value)
        }

    private fun hasAlternativeNames(manga: Manga, work: tachiyomi.domain.manga.service.WorkMetadata): Boolean {
        val title = tachiyomi.domain.manga.service.WorkTitleNormalizer.normalize(manga.title)
        return work.aliases.any { tachiyomi.domain.manga.service.WorkTitleNormalizer.normalize(it) != title }
    }

    private fun metadataPriority(manga: Manga): Int {
        val known = CatalogWorkEvidence.from(manga)
        if (authoritative(known)) {
            return 2
        }
        if (cachedMetadata(manga) != null ||
            (
                manga.initialized && WorkIdentityNormalizer.creator(manga.author) != null &&
                    hasAlternativeNames(manga, known)
                )
        ) {
            return 1
        }
        return 0
    }

    suspend fun refresh(
        rows: List<RankedSearchResult>,
        allowNetwork: Boolean = true,
        allowWeakMatches: Boolean = true,
    ) = withContext(Dispatchers.IO) {
        val owner = synchronized(this@MangaSearchEvidence) { epoch }
        // Choose the bounded frame AFTER prioritizing unresolved editions. Otherwise
        // two large known groups can fill the frame and hide every useful detail probe.
        // Retain the first four display candidates for SQL/cache integrity hydration.
        val distinct = rows.distinctBy { key(it.manga) }
        val priority = compareBy<RankedSearchResult>(
            { if (!forceMetadata && authoritative(CatalogWorkEvidence.from(it.manga))) 1 else 0 },
            { it.queryMatchRank },
            { metadataPriority(it.manga) },
        )
        val frame = (distinct.sortedWith(priority).take(12) + distinct.sortedBy { it.queryMatchRank }.take(4))
            .distinctBy { key(it.manga) }.map { row ->
                ensureActive()
                row to (
                    getManga.await(row.manga.id)?.takeIf {
                        it.source == row.manga.source && it.url == row.manga.url
                    } ?: row.manga
                    )
            }.sortedWith(
                compareBy(
                    { (_, manga) -> if (!forceMetadata && authoritative(CatalogWorkEvidence.from(manga))) 1 else 0 },
                    { (row, _) -> row.queryMatchRank },
                    { (_, manga) -> metadataPriority(manga) },
                ),
            )
        for ((row, manga) in frame) {
            ensureActive()
            // The current record is authoritative; don't resurrect removed external IDs from an older memo.
            val proof = if (ChapterListIntegrity.proof(manga) == null) {
                SearchChapterEvidence()
            } else {
                val chapters = getChapters(manga.id)
                if (!ChapterListIntegrity.retainedVerifiedRows(manga, chapters)) {
                    SearchChapterEvidence()
                } else {
                    SearchChapterEvidence(
                        ChapterListIntegrity.uniqueIndexedCount(chapters, manga.source),
                        ChapterListIntegrity.retainedComplete(manga),
                        checkedAt = ChapterListIntegrity.lastSuccessfulAt(manga),
                    )
                }
            }
            currentCoroutineContext().ensureActive()
            synchronized(this@MangaSearchEvidence) {
                if (owner != epoch) return@withContext
                metadata[key(manga)] = manga
                counts[key(manga)] = proof
            }
            if (proof.distinct != null) {
                PreferredMangaVariants.observeIndex(
                    manga,
                    tachiyomi.domain.manga.service.CatalogEditionEvidence(
                        indexedChapters = proof.distinct,
                        complete = proof.complete,
                        readable = proof.readable,
                        indexSucceededAt = proof.checkedAt,
                    ),
                )
            }
            val cached = cachedMetadata(manga)
            if (cached != null) {
                synchronized(this@MangaSearchEvidence) {
                    if (owner != epoch) return@withContext
                    metadata[key(manga)] = cached
                }
                continue
            }
            // First-source arrival may hydrate SQL/cache immediately, but must not spend
            // the whole query's detail budget before cross-provider candidates exist.
            if (!allowNetwork) continue
            // An early broad provider response must not exhaust the query before exact
            // matches arrive. Weak cross-language candidates are still eligible once
            // source search completes; they remain visible throughout.
            val matchTier = row.queryMatchRank ushr 32
            if (!allowWeakMatches && row.queryMatchRank != Long.MAX_VALUE && matchTier > 4) continue
            val known = CatalogWorkEvidence.from(manga)
            if (!forceMetadata && manga.initialized && WorkIdentityNormalizer.creator(manga.author) != null &&
                (hasAlternativeNames(manga, known) || known.externalIds.isNotEmpty() || known.reviewedWorkKey != null)
            ) {
                continue
            }
            val allowed = synchronized(this@MangaSearchEvidence) {
                owner == epoch && remaining > 0 &&
                    (
                        allowWeakMatches || row.queryMatchRank == 0L || row.queryMatchRank == Long.MAX_VALUE ||
                            remaining > 2
                        ) &&
                    attempted.add(key(manga)).also { if (it) remaining-- }
            }
            if (!allowed) continue
            try {
                val updated = withTimeoutOrNull(3_000) {
                    withContext(FocusTask(WorkPriority.ENRICHMENT, null, null, operation = FocusOperation.METADATA)) {
                        FocusEngine.network(row.source.id.toString()) {
                            row.source.getMangaUpdate(
                                manga.toSManga(),
                                emptyList(),
                                fetchDetails = true,
                                fetchChapters = false,
                            ).manga
                        }
                    }
                } ?: continue
                currentCoroutineContext().ensureActive()
                // Legacy CatalogueSource details may return fields only (unset URL/title).
                // Bind those fields to the original request; reject any explicit different route.
                val responseRoute = runCatching { updated.url }.getOrNull()
                if (!responseRoute.isNullOrBlank() && responseRoute != manga.url) continue
                var memoFields = kotlinx.serialization.json.JsonObject(manga.memo + updated.memo)
                val detailTitle = runCatching { updated.title }.getOrNull()?.takeIf {
                    it.isNotBlank() && SearchWorkIdentity.normalize(it) != SearchWorkIdentity.normalize(manga.title)
                }
                if (detailTitle != null) {
                    val identityKey = tachiyomi.domain.manga.service.WorkMetadata.MEMO_KEY
                    val identity = memoFields[identityKey] as? kotlinx.serialization.json.JsonObject
                    val aliases = (identity?.get("aliases") as? kotlinx.serialization.json.JsonArray).orEmpty() +
                        kotlinx.serialization.json.JsonPrimitive(detailTitle)
                    memoFields = kotlinx.serialization.json.JsonObject(
                        memoFields + (
                            identityKey to
                                kotlinx.serialization.json.JsonObject(
                                    identity.orEmpty() +
                                        ("aliases" to kotlinx.serialization.json.JsonArray(aliases.distinct())),
                                )
                            ),
                    )
                }
                val enriched = manga.copy(
                    author =
                    tachiyomi.domain.manga.service.SourceMetadataRefresh.creator(updated.author) ?: manga.author,
                    artist =
                    tachiyomi.domain.manga.service.SourceMetadataRefresh.creator(updated.artist) ?: manga.artist,
                    memo = memoFields,
                    initialized = manga.initialized || updated.initialized,
                )
                synchronized(this@MangaSearchEvidence) {
                    if (owner != epoch) return@withContext
                    metadata[key(manga)] = enriched
                    details[key(manga)] =
                        Cached(
                            System.nanoTime(),
                            enriched.author,
                            enriched.artist,
                            enriched.memo[tachiyomi.domain.manga.service.WorkMetadata.MEMO_KEY],
                            enriched.memo[AtlasDeclaredNames.MEMO_KEY],
                            baseline(manga),
                        )
                    while (details.size > 128)details.remove(details.keys.first())
                }
                // Persist only public source metadata through the repository's atomic
                // account/ref guard. A private memo or a newer response cannot be rewound.
                try {
                    if (storeMetadata?.invoke(manga, enriched) == true) PreferredMangaVariants.remember(enriched)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { /* Query evidence remains usable if optional persistence is unavailable. */ }
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) { /* Unknown remains unknown. */ }
            delay(750) // Serial metadata enrichment; source-friendly even across interleaved results.
        }
        requestMappings(rows)
        if (allowNetwork) {
            val candidates = rows.mapIndexed { index, row -> candidate(row, index) }
            val resolved = WorkIdentityResolverV2().resolve(candidates)
            val competing = resolved.groups.values
                .filter { it.size > 1 }.flatten().map { it.id }.toSet()
            for (row in rows.distinctBy { key(it.manga) }.sortedBy { it.queryMatchRank }.filter {
                val manga = metadata[key(it.manga)] ?: it.manga
                val known = evidence(manga)
                key(it.manga) in competing && it.source is eu.kanade.tachiyomi.source.online.HttpSource &&
                    (allowWeakMatches || it.queryMatchRank == 0L || it.queryMatchRank == Long.MAX_VALUE) &&
                    !(
                        known.complete && !known.indexUncertain && known.indexUsable != false &&
                            System.currentTimeMillis() - known.checkedAt in 0 until 300_000L
                        )
            }.take(4)) {
                currentCoroutineContext().ensureActive()
                val manga = metadata[key(row.manga)] ?: row.manga
                val retained = evidence(manga)
                if (retained.complete && !retained.indexUncertain && retained.indexUsable != false &&
                    System.currentTimeMillis() - retained.checkedAt in 0 until 300_000L
                ) {
                    continue
                }
                val verified = chapterVerifier.verify(row, manga, retained)
                synchronized(this@MangaSearchEvidence) {
                    if (owner != epoch) return@withContext
                    counts[key(manga)] = verified
                }
                PreferredMangaVariants.observeIndex(
                    manga,
                    tachiyomi.domain.manga.service.CatalogEditionEvidence(
                        indexedChapters = verified.distinct,
                        complete = verified.complete,
                        readable = verified.readable,
                        indexSucceededAt = verified.checkedAt,
                        indexUsable = verified.indexUsable,
                        uncertain = verified.indexUncertain,
                    ),
                )
                delay(250)
            }
        }
        if (metadata.size > 256) {
            metadata.clear()
            counts.clear()
        }
    }
}
