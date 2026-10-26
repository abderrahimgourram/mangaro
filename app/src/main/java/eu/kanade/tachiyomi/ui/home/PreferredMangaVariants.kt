package eu.kanade.tachiyomi.ui.home

import eu.kanade.tachiyomi.search.searchCandidate
import kotlinx.coroutines.flow.update
import mihon.domain.source.health.SourceHealthMonitor
import tachiyomi.domain.chapter.service.ChapterListIntegrity
import tachiyomi.domain.manga.model.CoverUrl
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.service.CatalogEditionEvidence
import tachiyomi.domain.manga.service.CatalogEditionSelection
import tachiyomi.domain.manga.service.CatalogSelectionContext
import tachiyomi.domain.manga.service.SourceWorkReference
import tachiyomi.domain.manga.service.WorkLinker
import tachiyomi.domain.manga.service.WorkMetadata
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap

/** Presentation preference only. IDs and library/download/history ownership never change. */
object PreferredMangaVariants {
    private data class Quality(
        val work: WorkMetadata,
        val complete: Boolean,
        val metadata: Boolean,
        val actualChapterCount: Int?,
        val successfulAt: Long,
        val publishedAt: Long?,
        val coverUrl: String?,
        val owner: tachiyomi.core.common.util.lang.LocalAccountScope.Token,
        val indexUsable: Boolean? = null,
        val uncertain: Boolean = false,
        val availabilityAt: Long = 0,
        val discoveryObserved: Boolean = false,
    )
    val changes = kotlinx.coroutines.flow.MutableStateFlow(0L)
    private val qualities = ConcurrentHashMap<Long, Quality>()

    // Source index counts are public metadata, not an account's read counts. Only
    // discovery promotion below is session-scoped; provided cards keep their valid evidence.
    private fun quality(id: Long) = qualities[id]

    private val invalid = ConcurrentHashMap.newKeySet<Long>()
    fun eligible(id: Long): Boolean = id !in invalid
    private val readers = ConcurrentHashMap<Long, Pair<Boolean, Long>>()
    fun remember(manga: Manga) {
        if (rememberQuality(manga)) changes.update { it + 1 }
    }

    /** Discovery conversion should invalidate presentation once per batch, not once per cover. */
    fun rememberAll(mangas: Collection<Manga>) {
        var changed = false
        mangas.forEach { if (rememberQuality(it)) changed = true }
        if (changed) changes.update { it + 1 }
    }
    private fun rememberQuality(manga: Manga): Boolean {
        val allowed = tachiyomi.domain.manga.model.DiscoveryEligibility.valid(manga.title, manga.url)
        val changed = if (allowed) invalid.remove(manga.id) else invalid.add(manga.id)
        val quality = Quality(
            CatalogWorkEvidence.source(manga),
            ChapterListIntegrity.retainedComplete(manga),
            manga.initialized,
            ChapterListIntegrity.actualChapterCount(manga),
            ChapterListIntegrity.lastSuccessfulAt(manga),
            ChapterListIntegrity.catalogPublishedAt(manga),
            CoverUrl.valid(manga.thumbnailUrl),
            tachiyomi.core.common.util.lang.LocalAccountScope.capture(),
        )
        var replaced = false
        qualities.compute(manga.id) { _, old ->
            // An older catalogue snapshot must not overwrite a newer reconciled count.
            val next = if (old != null && old.owner == quality.owner && old.work.reference == quality.work.reference &&
                old.successfulAt >= quality.successfulAt
            ) {
                old.copy(
                    work = quality.work,
                    metadata = old.metadata || quality.metadata,
                    coverUrl =
                    quality.coverUrl ?: old.coverUrl,
                )
            } else {
                quality.copy(
                    discoveryObserved =
                    old?.takeIf {
                        it.owner == quality.owner && it.work.reference == quality.work.reference
                    }?.discoveryObserved ==
                        true,
                )
            }
            next.also { replaced = old != it }
        }
        if (qualities.size > 1024) {
            qualities.entries.sortedWith(compareBy({ it.value.successfulAt }, { it.key })).take(qualities.size - 1024)
                .forEach { qualities.remove(it.key, it.value) }
        }
        CatalogWorkEvidence.request(listOf(quality.work))
        return changed || replaced
    }

    /** Reuse bounded Search verification without writing any Manga/chapter/library rows. */
    internal fun observeIndex(manga: Manga, incoming: CatalogEditionEvidence) {
        var changed = rememberQuality(manga)
        qualities.computeIfPresent(manga.id) { _, old ->
            if (old.owner != tachiyomi.core.common.util.lang.LocalAccountScope.capture() ||
                old.work.reference != SourceWorkReference(manga.source, manga.id, manga.url)
            ) {
                return@computeIfPresent old
            }
            val retain = incoming.indexedChapters == null || incoming.indexSucceededAt < old.successfulAt ||
                (
                    old.complete && !incoming.complete &&
                        (incoming.indexedChapters ?: -1) <= (old.actualChapterCount ?: -1)
                    )
            val next = old.copy(
                actualChapterCount = if (retain) old.actualChapterCount else incoming.indexedChapters,
                complete = if (retain) old.complete else incoming.complete,
                successfulAt = if (retain) old.successfulAt else incoming.indexSucceededAt,
                indexUsable = incoming.indexUsable,
                uncertain = incoming.uncertain,
                discoveryObserved = true,
            )
            val nextChanged = next != old
            changed = changed || nextChanged
            if (nextChanged) next.copy(availabilityAt = System.nanoTime()) else old
        }
        incoming.readable?.let { reader(manga.id, it) }
        if (changed) changes.update { it + 1 }
    }
    private fun currentAvailability(q: Quality?) = q?.takeIf {
        System.nanoTime() - it.availabilityAt in 0 until 30_000_000_000L
    }
    fun reader(mangaId: Long, usable: Boolean) {
        val old = readerUsable(mangaId)
        readers[mangaId] = usable to System.nanoTime()
        if (old != usable) changes.update { it + 1 }
    }
    fun readerUsable(mangaId: Long): Boolean? = readers[mangaId]?.takeIf {
        System.nanoTime() - it.second in 0 until if (it.first) 300_000_000_000L else 30_000_000_000L
    }?.first
    private fun health(sourceId: Long) = when (SourceHealthMonitor.shared.health(sourceId).state) {
        SourceHealthMonitor.State.HEALTHY -> 2
        SourceHealthMonitor.State.DEGRADED -> 1
        SourceHealthMonitor.State.UNAVAILABLE -> 0
    }
    fun evidence(item: HomeDiscoveryItem): CatalogEditionEvidence {
        val quality = quality(item.mangaId)?.takeIf {
            it.work.reference ==
                SourceWorkReference(item.sourceId, item.mangaId, item.url)
        }
        return CatalogEditionEvidence(
            quality?.actualChapterCount,
            quality?.publishedAt,
            health(item.sourceId),
            quality?.successfulAt ?: 0,
            readerUsable(item.mangaId),
            complete = quality?.complete == true,
            indexUsable =
            currentAvailability(quality)?.indexUsable
                ?: if (health(item.sourceId) == 0 && readerUsable(item.mangaId) != true) false else null,
            uncertain =
            currentAvailability(quality)?.uncertain == true,
        )
    }
    fun compare(a: HomeDiscoveryItem, b: HomeDiscoveryItem, context: CatalogSelectionContext): Int {
        val contextual = CatalogEditionSelection.compare(context, evidence(a), evidence(b))
        return if (contextual !=
            0
        ) {
            contextual
        } else {
            compare(a.sourceId, a.mangaId, a.coverData.url, b.sourceId, b.mangaId, b.coverData.url)
        }
    }
    fun compare(sourceA: Long, idA: Long, coverA: String?, sourceB: Long, idB: Long, coverB: String?): Int {
        val a = quality(idA)?.takeIf { it.work.reference.sourceId == sourceA }
        val b = quality(idB)?.takeIf { it.work.reference.sourceId == sourceB }
        fun quality(q: Quality?, source: Long, id: Long) = CatalogEditionEvidence(
            indexedChapters = q?.actualChapterCount,
            indexSucceededAt = q?.successfulAt ?: 0,
            complete = q?.complete == true,
            readable = readerUsable(id),
            sourceReliability = health(source),
            indexUsable = currentAvailability(q)?.indexUsable,
            uncertain = currentAvailability(q)?.uncertain == true,
        )
        val compared = CatalogEditionSelection.compareQuality(quality(a, sourceA, idA), quality(b, sourceB, idB))
        if (compared != 0) return compared
        return compareValuesBy(
            sourceB.toString() to (b?.work?.reference?.url ?: idB.toString()),
            sourceA.toString() to (a?.work?.reference?.url ?: idA.toString()),
            { it.first },
            { it.second },
        )
    }

    internal fun sourceEnabled(id: Long): Boolean = runCatching {
        val preferences = uy.kohesive.injekt.Injekt.get<eu.kanade.domain.source.service.SourcePreferences>()
        if (id.toString() in preferences.disabledSources.get()) return@runCatching false
        val source = uy.kohesive.injekt.Injekt.get<tachiyomi.domain.source.service.SourceManager>().get(id)
        source?.lang?.takeIf(String::isNotBlank)?.let { it in preferences.enabledLanguages.get() } ?: true
    }.getOrDefault(true)

    private data class RelatedIndex(
        val revision: Long,
        val owner: tachiyomi.core.common.util.lang.LocalAccountScope.Token,
        val rows: Map<String, List<Quality>>,
    )

    @Volatile private var indexed: RelatedIndex? = null
    private val indexLock = Any()
    private fun relatedIndex(): RelatedIndex = synchronized(indexLock) {
        val revision = changes.value
        val owner = tachiyomi.core.common.util.lang.LocalAccountScope.capture()
        indexed?.takeIf { it.revision == revision && it.owner == owner }?.let { return@synchronized it }
        val policy = eu.kanade.tachiyomi.search.WorkIdentityResolverV2()
        val rows = qualities.values.asSequence().filter {
            it.discoveryObserved && it.owner == owner && it.actualChapterCount != null
        }.map { it.copy(work = CatalogWorkEvidence.resolve(it.work)) }.flatMap { q ->
            eu.kanade.tachiyomi.search.AtlasCandidateIndex.keys(policy.evidence(q.work.searchCandidate(), null), true)
                .asSequence().map { it to q }
        }.groupBy({ it.first }, { it.second })
        RelatedIndex(revision, owner, rows).also { if (changes.value == revision) indexed = it }
    }

    /** Only verified same-work references may expand a recommendation's alternatives.
     * No library record is changed and no network call is performed here. */
    fun related(items: List<HomeDiscoveryItem>, allowed: (Long) -> Boolean = ::sourceEnabled): List<HomeDiscoveryItem> {
        if (items.isEmpty()) return emptyList()
        val policy = eu.kanade.tachiyomi.search.WorkIdentityResolverV2()
        val known = items.map(::work).map { it.searchCandidate() }
        val keys = known.flatMap {
            eu.kanade.tachiyomi.search.AtlasCandidateIndex.keys(policy.evidence(it, null), true)
        }.toSet()
        val incoming = items.mapTo(hashSetOf()) { it.sourceId to it.url }
        val index = relatedIndex()
        val eligible = keys.flatMap { index.rows[it].orEmpty() }.distinctBy { it.work.reference }
            .filter { q ->
                allowed(q.work.reference.sourceId) && eligible(q.work.reference.mangaId) &&
                    (q.work.reference.sourceId to q.work.reference.url) !in incoming
            }
            .map { it to it.work.searchCandidate() }
        if (eligible.isEmpty()) return emptyList()
        // Indexed lookup only proposes candidates. The shared complete-link policy
        // still decides every group and prevents conflicting transitive bridges.
        val incomingIds = known.mapTo(hashSetOf()) { it.id }
        val groups = eu.kanade.tachiyomi.search.SearchResultPresentationFilter(probablePresentation = true)
            .present(0, known + eligible.map { it.second }).orEmpty()
        val relatedIds = groups.filter { group -> group.members.any { it.id in incomingIds } }
            .flatMap { it.members }.mapTo(hashSetOf()) { it.id }
        return eligible.asSequence().filter { it.second.id in relatedIds }.map { it.first }
            .map { q ->
                val ref = q.work.reference
                HomeDiscoveryItem(
                    ref.mangaId,
                    q.work.title,
                    tachiyomi.domain.manga.model.MangaCover(ref.mangaId, ref.sourceId, false, q.coverUrl, 0),
                    ref.sourceId,
                    items.firstOrNull { it.sourceId == ref.sourceId }?.sourceName
                        ?: runCatching {
                            uy.kohesive.injekt.Injekt.get<tachiyomi.domain.source.service.SourceManager>().get(
                                ref.sourceId,
                            )?.name
                        }.getOrNull().orEmpty(),
                    ref.url,
                )
            }.take(128).toList()
    }

    /** Reuse bounded, account-scoped public evidence for a reviewed related-work
     * recommendation. Each route must independently satisfy the current registry;
     * this does not enroll a route, scan a library or fetch a source. */
    internal fun relatedStoryEditions(
        registry: eu.kanade.tachiyomi.data.stories.VerifiedStoryRelationshipRegistry,
        relation: eu.kanade.tachiyomi.data.stories.StoryRelationship,
        token: tachiyomi.core.common.util.lang.LocalAccountScope.Token,
        allowed: (Long) -> Boolean = ::sourceEnabled,
    ): List<eu.kanade.tachiyomi.data.stories.StoryEditionRef> = qualities.values.asSequence()
        .filter {
            it.owner == token && it.metadata && (it.actualChapterCount ?: 0) > 0 &&
                eligible(it.work.reference.mangaId) && allowed(it.work.reference.sourceId)
        }
        .sortedWith(compareByDescending<Quality> { it.actualChapterCount }.thenBy { it.work.reference.url })
        .mapNotNull { q ->
            val ref = q.work.reference
            val work = CatalogWorkEvidence.resolve(q.work)
            val candidate = eu.kanade.tachiyomi.data.stories.connectedComicCandidate(
                Manga.create().copy(
                    id = ref.mangaId,
                    source = ref.sourceId,
                    url = ref.url,
                    title = work.title,
                    thumbnailUrl = q.coverUrl,
                    author = work.author,
                    artist = work.artist,
                ),
                metadata = { work },
            )
            candidate.destination.edition.takeIf { registry.relationship(candidate) == relation }
        }.distinct().take(16).toList()

    fun actualChapterCount(item: HomeDiscoveryItem): Int? = quality(item.mangaId)?.takeIf {
        it.work.reference == SourceWorkReference(item.sourceId, item.mangaId, item.url)
    }?.actualChapterCount
    fun work(item: HomeDiscoveryItem): WorkMetadata {
        val reference = SourceWorkReference(item.sourceId, item.mangaId, item.url)
        return CatalogWorkEvidence.resolve(
            qualities[item.mangaId]?.work?.takeIf {
                it.reference == reference &&
                    (
                        tachiyomi.domain.manga.service.WorkTitleNormalizer.normalize(it.title) ==
                            tachiyomi.domain.manga.service.WorkTitleNormalizer.normalize(item.title) ||
                            it.aliases.any { alias ->
                                tachiyomi.domain.manga.service.WorkTitleNormalizer.normalize(alias) ==
                                    tachiyomi.domain.manga.service.WorkTitleNormalizer.normalize(item.title)
                            }
                        )
            }
                ?: WorkMetadata(reference, item.title),
        )
    }
    fun displayable(item: HomeDiscoveryItem): Boolean {
        val q = quality(item.mangaId)?.takeIf {
            it.work.reference == SourceWorkReference(item.sourceId, item.mangaId, item.url) &&
                (
                    tachiyomi.domain.manga.service.WorkTitleNormalizer.normalize(it.work.title) ==
                        tachiyomi.domain.manga.service.WorkTitleNormalizer.normalize(item.title) ||
                        it.work.aliases.any { alias ->
                            tachiyomi.domain.manga.service.WorkTitleNormalizer.normalize(alias) ==
                                tachiyomi.domain.manga.service.WorkTitleNormalizer.normalize(item.title)
                        }
                    )
        }
        return tachiyomi.domain.manga.model.WorkPresentationEligibility.state(
            item.title,
            item.url,
            q?.metadata == true,
            item.coverData.url ?: q?.coverUrl,
            q?.actualChapterCount,
        ).displayable
    }
    fun usable(source: Long, id: Long): Boolean = eligible(id) &&
        (
            quality(id)?.takeIf { it.work.reference.sourceId == source }?.let {
                it.metadata || CoverUrl.valid(it.coverUrl) != null
            } == true ||
                (quality(id)?.takeIf { it.work.reference.sourceId == source }?.actualChapterCount ?: 0) > 0 ||
                SourceHealthMonitor.shared.discoverable(source)
            )

    fun preferred(mangas: List<Manga>): Set<Long> {
        mangas.forEach(::remember)
        val byReference = mangas.associateBy { SourceWorkReference(it.source, it.id, it.url) }
        return WorkLinker.group(mangas.map(CatalogWorkEvidence::from)).mapNotNull { group ->
            group.members.mapNotNull { byReference[it.reference] }.filter {
                usable(it.source, it.id) && tachiyomi.domain.manga.model.DiscoveryEligibility.valid(it.title, it.url)
            }.maxWithOrNull { a, b -> compare(a.source, a.id, a.thumbnailUrl, b.source, b.id, b.thumbnailUrl) }?.id
        }.toSet()
    }
}
