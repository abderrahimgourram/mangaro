package eu.kanade.presentation.stories

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.novels.NovelDetailsScreen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.stories.ConnectedStoriesRepository
import eu.kanade.tachiyomi.data.stories.ConnectedStoryMetadata
import eu.kanade.tachiyomi.data.stories.ConnectedStoryUiModel
import eu.kanade.tachiyomi.data.stories.StoryDestination
import eu.kanade.tachiyomi.data.stories.StoryEditionRef
import eu.kanade.tachiyomi.data.stories.StoryFormat
import eu.kanade.tachiyomi.data.stories.VerifiedStoryRelationshipRegistry
import eu.kanade.tachiyomi.data.stories.connectedProseCandidate
import eu.kanade.tachiyomi.data.stories.identityEvidence
import eu.kanade.tachiyomi.data.stories.lookupKey
import eu.kanade.tachiyomi.novels.NovelRepository
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.home.PreferredMangaVariants
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.util.lang.LocalAccountScope
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** One immutable bundled public metadata graph per process. No feed or backend activation. */
private val connectedStoryMetadata = ConnectedStoryMetadata()

private object BundledStories {
    @Volatile private var loaded: StateFlow<VerifiedStoryRelationshipRegistry>? = null

    @Synchronized fun load(context: Context): StateFlow<VerifiedStoryRelationshipRegistry> {
        loaded?.let { return it }
        val registry = runCatching {
            context.resources.openRawResource(R.raw.connected_story_relationships).bufferedReader().use {
                VerifiedStoryRelationshipRegistry.parse(it.readText())
            }
        }.getOrElse { VerifiedStoryRelationshipRegistry.empty() }
        return MutableStateFlow(registry).asStateFlow().also { loaded = it }
    }
}

/** No chapter navigation, library insertion, account mutation or automatic edition switching. */
internal fun openConnectedStory(
    navigator: Navigator,
    originScreenKey: String,
    model: ConnectedStoryUiModel,
    registry: VerifiedStoryRelationshipRegistry,
    workspace: LocalAccountScope.State,
    available: (StoryDestination) -> Boolean,
): Boolean {
    if (navigator.lastItem.key != originScreenKey || !model.current(workspace) ||
        registry.relationship(model.originEvidence) != model.relationship || !available(model.selected)
    ) {
        return false
    }
    when (val target = model.selected) {
        is StoryDestination.Comic -> navigator.push(MangaScreen(target.manga.id))
        is StoryDestination.Prose -> navigator.push(NovelDetailsScreen(target.novel))
    }
    return true
}

@Composable
internal fun rememberConnectedStoryGraph(): StateFlow<VerifiedStoryRelationshipRegistry>? {
    val context = LocalContext.current.applicationContext
    val registry by produceState<StateFlow<VerifiedStoryRelationshipRegistry>?>(null, context) {
        value = withContext(Dispatchers.IO) { BundledStories.load(context) }
    }
    return registry
}

@Composable
internal fun ConnectedStoriesSection(origin: StoryDestination, inset: Boolean = false) {
    val context = LocalContext.current.applicationContext
    val navigator = LocalNavigator.currentOrThrow
    val screenKey = remember { navigator.lastItem.key }
    val graph = rememberConnectedStoryGraph() ?: return
    val reviewed by graph.collectAsStateWithLifecycle()
    // An unregistered work costs an indexed lookup, no SQL/source/catalog observers.
    val identityRevision by PreferredMangaVariants.changes.collectAsStateWithLifecycle()
    val catalog by NovelRepository.get(context).catalog.collectAsStateWithLifecycle()
    val originEvidence = remember(origin, identityRevision, catalog, reviewed) {
        if (origin is StoryDestination.Prose) {
            connectedProseCandidate(
                origin.novel,
                catalog,
                eu.kanade.tachiyomi.novels.NovelSourceAvailability::active,
                reviewed,
            )
        } else {
            origin.identityEvidence()
        }
    }
    val workspace by LocalAccountScope.changes.collectAsStateWithLifecycle()
    // Do not return before legacy editions have a chance to acquire missing source-owned names.
    // This request never switches editions or fetches chapter bodies.
    LaunchedEffect(reviewed, origin.edition, workspace.token) {
        if (!workspace.ready || origin !is StoryDestination.Comic) return@LaunchedEffect
        val token = workspace.token
        withContext(Dispatchers.IO + LocalAccountScope.Element(token)) {
            connectedStoryMetadata.refreshOrigin(reviewed, originEvidence, token) {
                LocalAccountScope.check(token)
                val source = Injekt.get<SourceManager>().get(origin.manga.source) as? HttpSource
                    ?: return@refreshOrigin
                val request = eu.kanade.tachiyomi.source.model.SManga.create().apply {
                    url = origin.manga.url
                    title = origin.manga.title
                    memo = origin.manga.memo
                }
                val fresh = source.getMangaUpdate(request, emptyList(), true, false).manga
                LocalAccountScope.check(token)
                check(fresh.url == origin.manga.url)
                check(
                    tachiyomi.domain.manga.service.WorkTitleNormalizer.normalize(fresh.title) ==
                        tachiyomi.domain.manga.service.WorkTitleNormalizer.normalize(origin.manga.title),
                )
                val merged = origin.manga.copy(
                    memo = kotlinx.serialization.json.JsonObject(origin.manga.memo + fresh.memo),
                    author = fresh.author?.takeIf { it.isNotBlank() } ?: origin.manga.author,
                    artist = fresh.artist?.takeIf { it.isNotBlank() } ?: origin.manga.artist,
                )
                // A title-only or conflicting response cannot install evidence for a relationship.
                if (reviewed.relationship(StoryDestination.Comic(merged).identityEvidence()) ==
                    null
                ) {
                    return@refreshOrigin
                }
                if (!Injekt.get<MangaRepository>().updateSourceMetadata(origin.manga, merged)) return@refreshOrigin
                PreferredMangaVariants.remember(merged)
            }
        }
    }
    val relation = reviewed.relationship(originEvidence) ?: return
    val repository = remember(graph, context) {
        val sources = Injekt.get<SourceManager>()
        ConnectedStoriesRepository(
            graph,
            Injekt.get<MangaRepository>(),
            NovelRepository.get(context).catalog,
            comicAvailable = { sources.get(it) is HttpSource },
            sourceChanges = merge(sources.sources.map { Unit }, PreferredMangaVariants.changes.map { Unit }),
            additionalComics = { registry, relation, token ->
                PreferredMangaVariants.relatedStoryEditions(registry, relation, token)
            },
        )
    }
    LaunchedEffect(reviewed, origin.edition, workspace, relation) {
        if (!workspace.ready) return@LaunchedEffect
        val token = workspace.token
        withContext(Dispatchers.IO + LocalAccountScope.Element(token)) {
            val mangaRepository = Injekt.get<MangaRepository>()
            val novelRepository = NovelRepository.get(context)
            val sources = Injekt.get<SourceManager>()
            suspend fun local(route: StoryEditionRef): StoryDestination? {
                LocalAccountScope.check(token)
                return if (route.format == StoryFormat.TEXT_NOVEL) {
                    novelRepository.catalog.value.work(route.source + ":" + route.url)?.editions
                        ?.firstOrNull { it.id == route.source + ":" + route.url }?.let(StoryDestination::Prose)
                } else {
                    val found = mangaRepository.getMangaByUrlAndSourceId(route.url, route.source.toLong())
                        ?: if (route.lookupKey().url == route.url && '?' !in route.url && '#' !in route.url) {
                            mangaRepository.getMangaByUrlAndSourceId(route.url + "/", route.source.toLong())
                        } else {
                            null
                        }
                    found?.let(StoryDestination::Comic)
                }
            }
            try {
                connectedStoryMetadata.prepare(
                    reviewed,
                    (
                        if (origin.edition.format ==
                            StoryFormat.MANHWA
                        ) {
                            relation.adaptation
                        } else {
                            relation.original
                        }
                        ).editions.first(),
                    token,
                    usable = { route ->
                        val target = local(route)
                        target != null && target.title.isNotBlank() &&
                            tachiyomi.domain.manga.model.CoverUrl.valid(target.cover) != null
                    },
                    fetch = { route ->
                        LocalAccountScope.check(token)
                        if (route.format == StoryFormat.TEXT_NOVEL) {
                            if (eu.kanade.tachiyomi.novels.NovelSourceAvailability.active(route.source)) {
                                novelRepository.detail(eu.kanade.tachiyomi.novels.Novel(route.source, route.url, ""))
                            }
                        } else {
                            val source = sources.get(route.source.toLong()) as? HttpSource
                            if (source != null) {
                                val request = eu.kanade.tachiyomi.source.model.SManga.create().apply {
                                    url = route.url
                                    title = ""
                                }
                                val fresh = source.getMangaUpdate(request, emptyList(), true, false).manga
                                LocalAccountScope.check(token)
                                check(
                                    StoryEditionRef(route.format, route.source, fresh.url).lookupKey() ==
                                        route.lookupKey(),
                                )
                                check(
                                    fresh.title.isNotBlank() &&
                                        tachiyomi.domain.manga.model.CoverUrl.valid(fresh.thumbnail_url) != null,
                                )
                                // Existing catalog insertion does not favorite, read, download, or award XP.
                                mangaRepository.insertNetworkManga(listOf(fresh.toDomainManga(source.id)))
                            }
                        }
                    },
                )
            } catch (
                changed: LocalAccountScope.Changed,
            ) { /* Old account cannot update the active destination. */ } catch (
                cancelled: kotlinx.coroutines.CancellationException,
            ) {
                throw cancelled
            } catch (
                _: Exception,
            ) { /* Optional metadata failure never blocks details or reader access. */ }
        }
    }
    val related by remember(repository, origin, workspace.token) {
        repository.observe(origin)
    }.collectAsStateWithLifecycle(initialValue = null)
    val model =
        related?.takeIf { it.current(workspace) && reviewed.relationship(originEvidence) == it.relationship } ?: return
    Box(Modifier.fillMaxWidth().then(if (inset) Modifier.padding(horizontal = 16.dp, vertical = 12.dp) else Modifier)) {
        ConnectedStoryCard(model) {
            openConnectedStory(navigator, screenKey, model, graph.value, LocalAccountScope.changes.value) { target ->
                when (target) {
                    is StoryDestination.Comic -> Injekt.get<SourceManager>().get(target.manga.source) is HttpSource
                    is StoryDestination.Prose -> eu.kanade.tachiyomi.novels.NovelSourceAvailability.active(
                        target.novel.sourceId,
                    ) &&
                        eu.kanade.tachiyomi.novels.NovelContentTypeClassifier.eligible(target.novel) &&
                        NovelRepository.get(context).catalog.value.work(target.novel.id)?.editions?.any {
                            it.id ==
                                target.novel.id
                        } ==
                        true
                }
            }
        }
    }
}
