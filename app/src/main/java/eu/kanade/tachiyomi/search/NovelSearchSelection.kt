package eu.kanade.tachiyomi.search

import eu.kanade.tachiyomi.novels.Novel
import eu.kanade.tachiyomi.novels.NovelCatalogCards
import eu.kanade.tachiyomi.novels.NovelCatalogState
import eu.kanade.tachiyomi.novels.NovelContentTypeClassifier
import eu.kanade.tachiyomi.novels.NovelIdentity
import eu.kanade.tachiyomi.novels.NovelPage
import eu.kanade.tachiyomi.novels.NovelRepository
import eu.kanade.tachiyomi.novels.NovelSourceAvailability
import eu.kanade.tachiyomi.novels.UnifiedNovelWork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** Presentation over source-owned editions; no catalogue aliases, progress or library records are rewritten. */
internal class NovelSearchSelection(private val repository: NovelRepository, query: String = "") {
    private val interpretedQuery = WorkSearchQuery(query)
    data class Presentation(val works: List<UnifiedNovelWork>, val groups: List<SearchWorkGroup>)
    private val selection = SearchResultPresentationFilter(probablePresentation = true)
    fun snapshot() = selection.snapshot()
    fun restore(groups: List<SearchWorkGroup>) = selection.restore(groups)
    private val counts = ConcurrentHashMap<String, SearchChapterEvidence>()
    private val checked = ConcurrentHashMap.newKeySet<String>()
    private val remaining = java.util.concurrent.atomic.AtomicInteger(4)
    private val reservationLock = Any()
    private val checkedIndexes = ConcurrentHashMap.newKeySet<String>()
    private val indexBudget = java.util.concurrent.atomic.AtomicInteger(4)
    private val sampleBudget = java.util.concurrent.atomic.AtomicInteger(2)
    private val sampled = ConcurrentHashMap.newKeySet<String>()
    fun reset() {
        selection.restore(emptyList())
        counts.clear()
        checked.clear()
        checkedIndexes.clear()
        remaining.set(4)
        indexBudget.set(4)
        sampleBudget.set(2)
        sampled.clear()
    }
    fun candidate(n: Novel, priority: Int): SearchWorkCandidate {
        val base = NovelCatalogCards.candidate(n, repository.catalog.value.evidence[n.id], priority)
        val match = interpretedQuery.match(listOf(base.title) + base.aliases + listOfNotNull(base.originalTitle))
        return base.copy(
            queryMatchRank = match.rank,
            queryMatchReason = match.reason,
            chapters =
            counts[n.id]?.let { SearchChapterVerifier.merge(base.chapters, it) } ?: base.chapters,
        )
    }
    fun groups(
        novels: List<Novel>,
        established: List<UnifiedNovelWork> = NovelCatalogCards.select(
            NovelCatalogCards.project(repository.catalog.value),
            novels,
        ),
    ): List<SearchWorkGroup> {
        val priority = repository.activeSources.mapIndexed { i, s -> s.id to i }.toMap()
        val eligible = NovelCatalogCards.eligiblePage(repository.catalog.value, NovelPage(novels)).novels
        val candidates = eligible.distinctBy { it.id }.map { candidate(it, priority[it.sourceId] ?: Int.MAX_VALUE) }
        val byId = candidates.associateBy { it.id }
        // Presentation groups can contain provisional links. Only source-owned native
        // catalogue groups may supply established identity authority to the resolver.
        val native = repository.catalog.value
        val verified = native.works.groupBy { native.resolve(it.id) }.mapValues { (_, works) ->
            works.flatMap { it.editions }.distinctBy { it.id }.mapNotNull { byId[it.id] }
        }
        return selection.present(selection.generation, candidates, verified).orEmpty().also {
            WorkIdentityDiagnostics.observe("novel-search", candidates, it)
        }
    }

    /** The real search grid consumes these exact groups and their selected source editions. */
    fun presentation(novels: List<Novel>, established: List<UnifiedNovelWork>): Presentation {
        val groups = groups(novels, established)
        val routes = novels.associateBy { SearchEditionKey(SearchWorkKind.NOVEL, it.sourceId, it.id) }
        return Presentation(
            WorkPresentationProjection.project(groups, routes).map { work ->
                UnifiedNovelWork(work.key, work.editions, work.selected.id)
            },
            groups,
        )
    }
    suspend fun cached(novels: List<Novel>) = withContext(Dispatchers.IO) {
        for (n in novels.distinctBy { it.id }.take(12)) {
            val index = repository.indexSnapshot(n)
            val access = repository.catalog.value.evidence[n.id]
            val fresh = if (index == null || index.editionId != n.id) {
                SearchChapterEvidence(readable = access?.accessWorks)
            } else {
                val listed = index.chapters.distinctBy { it.id }
                val distinct = listed.count { it.available }
                val full = index.complete && index.nextPage == null && index.fetchedPages.isNotEmpty() &&
                    (index.declaredTotal == null || index.declaredTotal == listed.size)
                SearchChapterEvidence(
                    distinct.takeIf { full || it > 0 },
                    full,
                    if (full &&
                        index.availableCount == 0
                    ) {
                        false
                    } else {
                        access?.accessWorks
                    },
                    index.updatedAt,
                )
            }
            counts[n.id] = if (index != null && index.editionId != n.id) {
                fresh
            } else {
                SearchChapterVerifier.merge(counts[n.id] ?: SearchChapterEvidence(), fresh)
            }
        }
    }
    suspend fun enrich(novels: List<Novel>) = withContext(Dispatchers.IO) {
        cached(novels)
        val sources = novels.distinctBy { it.id }.filter { it.id !in checked }.groupBy { it.sourceId }.values.toList()
        // Slots arrive as complete provider lists. Interleave them before spending the
        // shared budget, so a large first list cannot crowd out every alternative.
        val targets = buildList {
            for (i in 0 until (sources.maxOfOrNull { it.size } ?: 0)) {
                sources.forEach { it.getOrNull(i)?.let(::add) }
            }
        }.sortedBy { n ->
            if (AtlasCandidateIndex.authoritative(candidate(n, 0))) {
                2
            } else if (!n.author.isNullOrBlank() &&
                (n.originalTitle != null || n.alternativeTitles.isNotEmpty())
            ) {
                1
            } else {
                0
            }
        }.take(remaining.get().coerceAtLeast(0))
        withTimeoutOrNull(6_000) {
            targets.chunked(2).forEach { batch ->
                coroutineScope {
                    batch.map { n ->
                        async {
                            ensureActive()
                            val reserved = synchronized(reservationLock) {
                                remaining.get() > 0 && checked.add(n.id).also { if (it) remaining.decrementAndGet() }
                            }
                            if (!reserved) return@async
                            try {
                                withTimeoutOrNull(3_000) { repository.detail(n) }
                            } catch (c: CancellationException) {
                                // A started request consumes its budget; an interrupted result may be
                                // retried using the remainder. Unstarted batches are never marked done.
                                checked.remove(n.id)
                                throw c
                            } catch (_: Exception) { }
                        }
                    }.awaitAll()
                }
            }
        }
        // Enumerate only already-correlated competing editions. Use the existing persistent
        // metadata index/coalescing/last-good recovery; never fetch chapter text here.
        val detailed = novels.map { n ->
            repository.catalog.value.work(n.id)?.editions?.firstOrNull { it.id == n.id } ?: n
        }
        val candidates = detailed.filter(NovelContentTypeClassifier::eligible).map { candidate(it, 0) }
        val byId = candidates.associateBy { it.id }
        val established = repository.catalog.value.works.associate { work ->
            work.id to work.editions.mapNotNull { byId[it.id] }
        }
        val competing = WorkIdentityResolverV2().resolve(candidates, established).groups.values
            .filter { it.size > 1 }.flatten().map { it.id }.toSet()
        for (n in detailed.distinctBy {
            it.id
        }.filter { it.id in competing && counts[it.id]?.complete != true }.take(4)) {
            ensureActive()
            val reserved = synchronized(reservationLock) {
                indexBudget.get() > 0 && checkedIndexes.add(n.id).also { if (it) indexBudget.decrementAndGet() }
            }
            if (!reserved) continue
            try {
                withTimeoutOrNull(8_000) { repository.completeIndex(n, maxPages = 2) }
            } catch (
                c: CancellationException,
            ) {
                checkedIndexes.remove(n.id)
                throw c
            } catch (
                _: Exception,
            ) { /* Last-good index remains available; unknown never becomes zero. */ }
        }
        cached(detailed)
        // Existing source preflight, at most two genuine available chapter samples per query.
        // No reading position, completion or XP operation is performed by this public-content probe.
        val samples = detailed.distinctBy { it.id }.filter { it.id in competing && it.id !in sampled }
            .sortedByDescending { counts[it.id]?.distinct ?: -1 }
        for (novel in samples) {
            ensureActive()
            if (sampleBudget.get() <= 0) break
            val index =
                repository.indexSnapshot(novel)?.takeIf { it.editionId == novel.id && it.availableCount > 0 }
                    ?: continue
            val access = repository.catalog.value.evidence[novel.id]
            val ttl = if (access?.accessWorks == true) 21_600_000L else 30_000L
            if (access?.accessWorks != null &&
                System.currentTimeMillis() - access.accessCheckedAt in 0 until ttl
            ) {
                continue
            }
            val reserved = synchronized(reservationLock) {
                sampleBudget.get() > 0 && sampled.add(novel.id).also { if (it) sampleBudget.decrementAndGet() }
            }
            if (!reserved) continue
            try {
                withTimeoutOrNull(4_000) { repository.verifyEditionAccess(novel, index, maxSamples = 1) }
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) { /* Transport failure is not proof of zero or a read event. */ }
        }
        cached(detailed)
    }
    data class EvidenceInput(val rows: List<Novel>, val visible: List<Novel>, val catalog: NovelCatalogState)

    /** One collector per query. Source arrivals and our own detail ingestion are conflated,
     * never allowed to cancel the requests that supplied their identity evidence. */
    suspend fun observeEvidence(updates: Flow<EvidenceInput>, onUpdated: () -> Unit) {
        updates.conflate().collect { input ->
            val grouped = withContext(Dispatchers.Default) {
                val detailed = input.rows.filter { NovelSourceAvailability.active(it.sourceId) }.distinctBy { it.id }
                    .map { n -> input.catalog.work(n.id)?.editions?.firstOrNull { it.id == n.id } ?: n }
                val ids = SearchWorkIdentity.enrichmentCandidates(detailed.map { candidate(it, 0) })
                val visible = input.visible.take(12).mapTo(hashSetOf()) { it.id }
                detailed.filter { it.id in ids || it.id in visible }
            }
            cached(input.visible)
            enrich(grouped)
            onUpdated()
        }
    }
    fun evidence(id: String) = counts[id] ?: SearchChapterEvidence()
}
