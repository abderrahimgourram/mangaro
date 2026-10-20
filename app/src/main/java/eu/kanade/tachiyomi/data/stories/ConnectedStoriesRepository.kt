package eu.kanade.tachiyomi.data.stories

import eu.kanade.tachiyomi.novels.*
import eu.kanade.tachiyomi.ui.home.CatalogWorkEvidence
import eu.kanade.tachiyomi.ui.home.PreferredMangaVariants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import tachiyomi.core.common.util.lang.LocalAccountScope
import tachiyomi.domain.chapter.service.ChapterListIntegrity
import tachiyomi.domain.manga.model.DiscoveryEligibility
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.manga.service.WorkMetadata

/** Public catalog metadata only; no fetch/search/insert/history/progress/download/XP operations. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ConnectedStoriesRepository(
    private val registry: StateFlow<VerifiedStoryRelationshipRegistry>,
    private val mangas: MangaRepository,
    private val novels: StateFlow<NovelCatalogState>,
    private val comicAvailable: (Long) -> Boolean,
    private val sourceChanges: Flow<Unit> = flowOf(Unit),
    private val workspace: StateFlow<LocalAccountScope.State> = LocalAccountScope.changes,
    private val metadata: (Manga) -> WorkMetadata = CatalogWorkEvidence::from,
    private val novelActive: (String) -> Boolean = NovelSourceAvailability::active,
    private val comicReadable: (Long) -> Boolean? = PreferredMangaVariants::readerUsable,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    fun observe(origin: StoryDestination): Flow<ConnectedStoryUiModel?> = combine(registry,sourceChanges) { reviewed, _ -> reviewed }.flatMapLatest { reviewed ->
        val from = when (origin) {
            is StoryDestination.Comic -> comic(origin.manga)
            is StoryDestination.Prose -> prose(origin.novel,novels.value)
        }
        val relation = reviewed.relationship(from)
        if (relation == null) flowOf(null) else workspace.flatMapLatest { state ->
            if (!state.ready) flowOf(null) else {
                // At most eight enrolled comic routes, not an unrestricted library scan or source search.
                val comicFlows = relation.adaptation.editions.map { route ->
                    combine((listOf(route.url) + listOf(route.url + "/").filter { route.lookupKey().url == route.url && '?' !in route.url && '#' !in route.url }).distinct().map { url ->
                        mangas.getMangaByUrlAndSourceIdAsFlow(url,route.source.toLong())
                    }) { variants -> variants.firstOrNull { it != null } }.catch { failure ->
                        if (failure is CancellationException && failure !is LocalAccountScope.Changed) throw failure
                        emit(null)
                    }
                }
                combine(combine(comicFlows) { it.filterNotNull() }, novels) { comics, catalog ->
                    val known = relation.original.editions.mapNotNull { route ->
                        val id = route.source + ":" + route.url
                        catalog.work(id)?.editions?.firstOrNull { it.id == id }
                    }
                    val rows = comics.map(::comic) + known.map { prose(it,catalog) }
                    val from = when (origin) {
                        is StoryDestination.Comic -> comic(origin.manga)
                        is StoryDestination.Prose -> prose(origin.novel,catalog)
                    }
                    ConnectedStoryResolver.resolve(reviewed,from,rows,state.token)
                }.onStart { emit(null) }.catch { failure ->
                    if (failure is CancellationException && failure !is LocalAccountScope.Changed) throw failure
                    emit(null) // Optional metadata must never fail the main details screen.
                }
            }
        }
    }.catch { failure ->
        if (failure is CancellationException && failure !is LocalAccountScope.Changed) throw failure
        emit(null)
    }.distinctUntilChanged().flowOn(dispatcher)

    private fun comic(manga: Manga): StoryCandidate = connectedComicCandidate(manga, metadata, comicAvailable, comicReadable)
    private fun prose(novel: Novel, catalog: NovelCatalogState) = connectedProseCandidate(novel,catalog,novelActive)
}

internal fun connectedComicCandidate(manga: Manga,
    metadata: (Manga) -> WorkMetadata = CatalogWorkEvidence::from,
    comicAvailable: (Long) -> Boolean = { true },
    comicReadable: (Long) -> Boolean? = { null }): StoryCandidate {
        val proof = metadata(manga)
        return StoryCandidate(StoryDestination.Comic(manga),proof.externalIds,proof.reviewedWorkKey,
            eligible = manga.id > 0 && comicAvailable(manga.source) && DiscoveryEligibility.valid(manga.title,manga.url),
            formatCompatible = proof.format?.let(tachiyomi.domain.manga.service.WorkTitleNormalizer::normalize) !in
                setOf("novel", "light novel", "web novel", "رواية") &&
                proof.edition?.let(tachiyomi.domain.manga.service.WorkTitleNormalizer::normalize) !in
                setOf("sequel", "side story", "spin off", "remake", "reboot"),
            readable = comicReadable(manga.id),
            verifiedCount = ChapterListIntegrity.actualChapterCount(manga).takeIf { ChapterListIntegrity.complete(manga) })
    }
