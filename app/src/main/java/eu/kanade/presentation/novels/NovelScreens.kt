package eu.kanade.presentation.novels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.focus.FocusContext
import eu.kanade.tachiyomi.focus.FocusSurface
import eu.kanade.tachiyomi.focus.FocusTarget
import eu.kanade.tachiyomi.novels.Novel
import eu.kanade.tachiyomi.novels.NovelCatalogCards
import eu.kanade.tachiyomi.novels.cleanNovelCover
import eu.kanade.tachiyomi.novels.NovelChapter
import eu.kanade.tachiyomi.novels.NovelChapterGroup
import eu.kanade.tachiyomi.novels.NovelChapterGroupBuilder
import eu.kanade.tachiyomi.novels.NovelChapterIndex
import eu.kanade.tachiyomi.novels.NovelDownloadState
import eu.kanade.tachiyomi.novels.NovelGenre
import eu.kanade.tachiyomi.novels.NovelGenres
import eu.kanade.tachiyomi.novels.NovelIdentity
import eu.kanade.tachiyomi.novels.NovelIndexState
import eu.kanade.tachiyomi.novels.NovelPage
import eu.kanade.tachiyomi.novels.NovelRepository
import eu.kanade.tachiyomi.novels.NovelSourceAvailability
import eu.kanade.tachiyomi.novels.NovelSourceFailure
import eu.kanade.tachiyomi.novels.SeaNovelSource
import eu.kanade.tachiyomi.novels.SunovelsAccess
import eu.kanade.tachiyomi.novels.SunovelsChapterState
import eu.kanade.tachiyomi.novels.SunovelsGatewayConfig
import eu.kanade.tachiyomi.novels.SunovelsGatewaySource
import eu.kanade.tachiyomi.novels.SunovelsReaderDiagnostics
import eu.kanade.tachiyomi.novels.SunovelsSource
import eu.kanade.tachiyomi.novels.UnifiedNovelWork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import tachiyomi.core.common.util.lang.WesternDigits
import eu.kanade.presentation.theme.MangaroDesignSystem as Design

internal fun novelError(error: Exception, logDetails: Boolean = true): String {
    if (logDetails) android.util.Log.w("MangaroNovels", "Novel operation failed", error)
    val failure = error as? NovelSourceFailure
    val message = failure?.publicMessage ?: "تعذّر التحميل حاليًا. حاول مجددًا."
    val code = failure?.code?.takeIf { it.matches(Regex("[A-Z0-9_]{1,64}")) }
    return if (SunovelsSource.diagnosticsEnabled && code != null) "$message [$code]" else message
}

@Composable
internal fun NovelShell(
    title: String,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val navigator = LocalNavigator.currentOrThrow
    NovelForegroundRefresh(NovelRepository.get(LocalContext.current))
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(Modifier.fillMaxSize().background(Design.BackgroundDark).safeDrawingPadding()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    navigator.pop()
                }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "رجوع", tint = Design.TextSecondary) }
                Text(
                    title,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.White,
                )
                actions()
            }
            val storageError by NovelRepository.get(LocalContext.current).storageError.collectAsState()
            storageError?.let {
                Text(
                    it,
                    Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    color = Color(0xFFE5B5AB),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            content()
        }
    }
}

@Composable
internal fun NovelCover(novel: Novel, modifier: Modifier) {
    val context = LocalContext.current
    val repository = remember(context) { NovelRepository.get(context) }
    val catalog by repository.catalog.collectAsState()
    val cleanCover = remember(novel.cover) { cleanNovelCover(novel.cover) }
    val effectiveCover = remember(cleanCover, catalog, novel.id) {
        cleanCover ?: catalog.work(novel.id)?.editions?.firstNotNullOfOrNull { cleanNovelCover(it.cover) }
    }
    LaunchedEffect(novel.id, effectiveCover) {
        if (effectiveCover == null && (novel.sourceId == "novel.kolnovel" || novel.sourceId == "novel.mknov")) {
            try {
                repository.detail(novel)
            } catch (_: Exception) {}
        }
    }
    val loader = repository.covers
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val view = androidx.compose.ui.platform.LocalView.current
    val motion = android.provider.Settings.Global.getFloat(
        context.contentResolver,
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) > 0f
    var animation by remember(effectiveCover) { mutableStateOf<android.graphics.drawable.Animatable?>(null) }
    var visible by remember(effectiveCover) { mutableStateOf(false) }
    DisposableEffect(owner, animation, motion, visible) {
        val current = animation
        fun update() {
            if (motion && visible &&
                owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
            ) {
                current?.start()
            } else {
                current?.stop()
            }
        }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ -> update() }
        owner.lifecycle.addObserver(observer)
        update()
        onDispose {
            owner.lifecycle.removeObserver(observer)
            current?.stop()
        }
    }
    BoxWithConstraints(
        modifier.onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInWindow()
            visible = bounds.width > 0 && bounds.height > 0 && bounds.bottom > 0 && bounds.right > 0 &&
                bounds.top < view.height && bounds.left < view.width
        }.clip(RoundedCornerShape(14.dp)).background(Design.SurfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.MenuBook, null, Modifier.size(40.dp), tint = Design.GoldPrimary.copy(alpha = .5f))
        val width = with(density) { maxWidth.toPx() }.toInt().coerceIn(1, 960)
        val height = with(density) { maxHeight.toPx() }.toInt().coerceIn(1, 1280)
        val request = remember(effectiveCover, width, height, motion, context) {
            effectiveCover?.let { url ->
                coil3.request.ImageRequest.Builder(context).data(url)
                    .size(width, height).diskCacheKey(url).memoryCacheKey("$url|motion=$motion")
                    .apply { if (!motion) decoderFactory(coil3.decode.BitmapFactoryDecoder.Factory()) }.build()
            }
        }
        request?.let {
            AsyncImage(
                it,
                null,
                imageLoader = loader,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onSuccess = { state ->
                    animation =
                        (state.result.image as? coil3.DrawableImage)?.drawable as? android.graphics.drawable.Animatable
                },
            )
        }
    }
}

@Composable
private fun NovelCard(
    novel: Novel,
    metadata: String? = null,
    onAlternatives: (() -> Unit)? = null,
    onOpen: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Design.SurfaceDark)
            .then(
                if (onAlternatives == null) {
                    Modifier.clickable(onClick = onOpen)
                } else {
                    Modifier.combinedClickable(onClick = onOpen, onLongClick = onAlternatives)
                },
            ).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NovelCover(novel, Modifier.fillMaxWidth().aspectRatio(.72f))
        Text(
            novel.title,
            style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.ContentOrRtl),
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        metadata?.let { Text(it, color = Design.LavenderPrimary, style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
internal fun NovelFailure(message: String, url: String, retry: () -> Unit) {
    val browser = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        eu.kanade.presentation.components.MangaroErrorState(message, retry)
        TextButton(onClick = {
            runCatching { browser.openUri(url) }
        }) { Text("فتح الرواية في الموقع", color = Design.LavenderPrimary) }
    }
}

/** Debug-only, bounded metadata report; never copies story text, cookies or user/account data. */
@Composable
internal fun SunovelsChapterDiagnostic(
    novel: Novel,
    chapter: NovelChapter,
    allowResumeMapping: Boolean = false,
    seaReport: String? = null,
) {
    if (!SunovelsSource.diagnosticsEnabled ||
        (novel.sourceId !in setOf(SunovelsSource.SOURCE_ID, SeaNovelSource.SOURCE_ID) && !allowResumeMapping)
    ) {
        return
    }
    val context = LocalContext.current
    val repository = NovelRepository.get(context)
    val scope = rememberCoroutineScope()
    var report by remember(chapter.id) { mutableStateOf<String?>(null) }
    Text(
        "المصدر: " + when (novel.sourceId) {
            SeaNovelSource.SOURCE_ID -> "SeaNovel (novel.seanovel)"
            SunovelsSource.SOURCE_ID -> "Sunovels (novel.sunovels)"
            else -> novel.sourceId
        },
        color = Design.LavenderPrimary,
        style = MaterialTheme.typography.labelSmall,
    )
    SunovelsReaderDiagnostics.get(chapter.id)?.takeIf { it.failureCode != "NONE" }?.let {
        Text(
            it.failureTag(),
            color = Design.LavenderPrimary,
            style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
        )
    }
    seaReport?.lineSequence()?.firstOrNull { it.startsWith("failure=") && it != "failure=NONE" }?.let {
        Text(
            "REASON: " + it.removePrefix("failure="),
            color = Design.LavenderPrimary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    TextButton(onClick = {
        scope.launch {
            report = seaReport ?: repository.sunovelsDiagnostic(novel, chapter)
        }
    }) { Text("تشخيص الفصل", color = Design.LavenderPrimary) }
    report?.let { value ->
        AlertDialog(
            onDismissRequest = { report = null },
            title = { Text("تشخيص الفصل") },
            text = {
                androidx.compose.foundation.layout.Box(Modifier.heightIn(max = 420.dp)) {
                    Text(
                        value,
                        Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(
                        android.content.Context.CLIPBOARD_SERVICE,
                    ) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Novel Chapter Diagnostic", value))
                }) { Text("Copy Diagnostic Report") }
            },
            dismissButton = { TextButton(onClick = { report = null }) { Text("إغلاق") } },
        )
    }
}

/** One catalogue, streamed source-by-source; provider identity stays in maintenance/edition information. */
private class NovelBrowseMemory {
    var selection: eu.kanade.tachiyomi.search.NovelSearchSelection? = null
    val sessions = eu.kanade.tachiyomi.search.SearchSessionCoordinator()
    val snapshots = eu.kanade.tachiyomi.search.SearchSnapshotCache<NovelBrowseSnapshot>()
    val evidenceRevision = mutableIntStateOf(0)
    val failed = mutableStateOf(emptySet<String>())
    var query: eu.kanade.tachiyomi.search.SearchContext? = null
    val slots = mutableStateOf(emptyMap<String, List<Novel>>())
    val pages = mutableStateOf(emptyMap<String, Int?>())
    val routes = mutableStateOf(emptyMap<String, List<NovelGenre>>())
    var loadedKey: eu.kanade.tachiyomi.search.SearchContext? = null
    fun cache(context: eu.kanade.tachiyomi.search.SearchContext) {
        if (context.query.isNotBlank()) {
            snapshots.put(
                context,
                NovelBrowseSnapshot(
                    slots.value.mapValues {
                        it.value.toList()
                    },
                    pages.value.toMap(),
                    routes.value.toMap(),
                    selection?.snapshot().orEmpty(),
                ),
            )
        }
    }
}
private data class NovelBrowseSnapshot(
    val slots: Map<String, List<Novel>>,
    val pages: Map<String, Int?>,
    val routes: Map<String, List<NovelGenre>>,
    val groups: List<eu.kanade.tachiyomi.search.SearchWorkGroup>,
)
private data class NovelBrowsePresentation(
    val works: List<UnifiedNovelWork> = emptyList(),
    val genres: List<String> = emptyList(),
    val recent: List<UnifiedNovelWork> = emptyList(),
    val groups: List<eu.kanade.tachiyomi.search.SearchWorkGroup> = emptyList(),
    val query: String? = null,
    val generation: Long = 0,
)

class NovelHomeScreen(private val initialGenre: String? = null) : Screen() {
    @Transient private var browseMemory: NovelBrowseMemory? = null
    internal fun searchGroups() = browseMemory?.selection?.snapshot().orEmpty()

    @Composable override fun Content() {
        val context = LocalContext.current
        val repository = remember(context) { NovelRepository.get(context) }
        val navigator = LocalNavigator.currentOrThrow
        val openReader = rememberSunovelsReaderEntry()
        val catalog by repository.catalog.collectAsState()
        val library by repository.library.collectAsState()
        var input by rememberSaveable { mutableStateOf("") }
        var term by rememberSaveable { mutableStateOf("") }
        var genre by rememberSaveable { mutableStateOf(initialGenre) }
        var sunStatus by rememberSaveable { mutableStateOf<String?>(null) }
        var generation by remember { mutableIntStateOf(0) }
        val memory = remember { browseMemory ?: NovelBrowseMemory().also { browseMemory = it } }
        val sourceAdapters = repository.activeSources
        val searchContext = eu.kanade.tachiyomi.search.SearchContext(
            eu.kanade.tachiyomi.search.SearchWorkKind.NOVEL,
            term,
            sourceAdapters.map { it.id },
            filters = mapOf("genre" to genre.orEmpty(), "sunovelsStatus" to sunStatus.orEmpty()),
        )
        val session = remember(searchContext, generation) { memory.sessions.begin(searchContext) }
        val selection = remember(session) {
            eu.kanade.tachiyomi.search.NovelSearchSelection(repository, term).also { selected ->
                memory.snapshots.get(searchContext)?.let { selected.restore(it.groups) }
                memory.selection = selected
            }
        }
        val evidenceRevision by memory.evidenceRevision
        var slots by memory.slots
        var pages by memory.pages
        var routes by memory.routes
        val gridState = rememberLazyGridState()
        var busy by remember { mutableStateOf(emptySet<String>()) }
        var failed by memory.failed
        val scope = rememberCoroutineScope()
        var moreJob by remember { mutableStateOf<Job?>(null) }
        LaunchedEffect(input) {
            kotlinx.coroutines.delay(350)
            if (input.trim() !=
                term
            ) {
                term = input.trim()
                if (term.isNotBlank()) genre = null
            }
        }
        LaunchedEffect(session) {
            if (memory.loadedKey == searchContext &&
                (session.context.query.isBlank() || memory.snapshots.get(searchContext) != null)
            ) {
                return@LaunchedEffect
            }
            val request = session
            val requestQuery = request.context.query
            val requestGenre = request.context.filters["genre"]?.takeIf(String::isNotBlank)
            val requestStatus = request.context.filters["sunovelsStatus"]?.takeIf(String::isNotBlank)
            moreJob?.cancel()
            if (memory.query != searchContext) {
                val cached = memory.snapshots.get(searchContext)
                slots = cached?.slots.orEmpty()
                pages = cached?.pages.orEmpty()
                cached?.let { routes = it.routes }
            }
            memory.query = searchContext
            failed = emptySet()
            busy = sourceAdapters.map { it.id }.toSet()
            kotlinx.coroutines.coroutineScope {
                sourceAdapters.forEach { source ->
                    launch {
                        try {
                            if (requestGenre == null && slots[source.id].isNullOrEmpty()) {
                                repository.discoverySnapshot(
                                    source,
                                    requestQuery,
                                    1,
                                    if (source is SunovelsGatewaySource) {
                                        SunovelsGatewaySource.filter(
                                            null,
                                            requestStatus,
                                        )
                                    } else {
                                        null
                                    },
                                )?.let { cached ->
                                    repository.ingest(cached.novels)
                                    if (memory.sessions.accepts(request)) {
                                        slots = slots + (source.id to cached.novels)
                                        pages =
                                            pages + (source.id to cached.nextPage)
                                        if (cached.genres.isNotEmpty()) routes = routes + (source.id to cached.genres)
                                    }
                                }
                            }
                            if (requestGenre != null && routes[source.id].isNullOrEmpty()) {
                                val initialPage = repository.discover(source, "", 1, null)
                                if (memory.sessions.accepts(request)) {
                                    routes =
                                        routes + (source.id to initialPage.genres)
                                }
                            }
                            val route = routes[source.id].orEmpty().firstOrNull {
                                NovelGenres.key(it.name) == requestGenre
                            }?.value
                            val result = if (requestGenre != null && route == null) {
                                val available = withContext(Dispatchers.Default) {
                                    val query = NovelIdentity.normalize(requestQuery)
                                    repository.catalog.value.works.flatMap { it.editions }.filter {
                                        it.sourceId == source.id &&
                                            it.genres.any { g -> NovelGenres.key(g) == requestGenre } &&
                                            (
                                                requestQuery.isBlank() || NovelIdentity.normalize(
                                                    it.title,
                                                ).contains(query)
                                                )
                                    }
                                }
                                NovelPage(available)
                            } else {
                                repository.discover(
                                    source,
                                    requestQuery,
                                    1,
                                    if (source is SunovelsGatewaySource) {
                                        SunovelsGatewaySource.filter(
                                            route,
                                            requestStatus,
                                        )
                                    } else {
                                        route
                                    },
                                )
                            }
                            if (memory.sessions.accepts(request)) {
                                slots = slots + (source.id to result.novels)
                                pages = pages + (source.id to result.nextPage)
                                if (result.genres.isNotEmpty()) routes = routes + (source.id to result.genres)
                            }
                        } catch (
                            c: CancellationException,
                        ) {
                            throw c
                        } catch (
                            e: Exception,
                        ) {
                            novelError(e)
                            if (memory.sessions.accepts(request)) failed = failed + source.id
                        } finally {
                            if (memory.sessions.accepts(request)) busy = busy - source.id
                        }
                    }
                }
            }
            if (memory.sessions.accepts(request)) {
                memory.loadedKey = searchContext
                memory.cache(searchContext)
            }
        }
        // Catalogue matching and genre normalization never run during main-thread composition.
        val catalogSnapshot = catalog
        val slotsSnapshot = if (memory.query == searchContext) {
            slots
        } else {
            memory.snapshots.get(searchContext)?.slots.orEmpty()
        }
        val routesSnapshot = routes
        val querySnapshot = term
        val genreSnapshot = genre
        val resumeEdition = if (term.isBlank() &&
            genre == null
        ) {
            library.firstOrNull { it.position != null }?.novel?.id
        } else {
            null
        }
        val presentation by produceState(
            NovelBrowsePresentation(),
            catalogSnapshot,
            slotsSnapshot,
            routesSnapshot,
            querySnapshot to genreSnapshot,
            evidenceRevision,
            resumeEdition,
            session,
        ) {
            val projected = withContext(Dispatchers.Default) {
                val lists = sourceAdapters.map { slotsSnapshot[it.id].orEmpty() }
                val editions =
                    buildList {
                        for (i in 0 until (
                            lists.maxOfOrNull {
                                it.size
                            } ?: 0
                            )) {
                            lists.forEach { it.getOrNull(i)?.let(::add) }
                        }
                    }
                val activeWorks = NovelCatalogCards.project(catalogSnapshot)
                val eligibleEditions = NovelCatalogCards.eligiblePage(catalogSnapshot, NovelPage(editions)).novels
                val search = if (querySnapshot.isNotBlank()) {
                    eu.kanade.tachiyomi.search.NovelSearchProjection.project(
                        catalogSnapshot,
                        editions,
                        selection,
                        activeWorks,
                    )
                } else {
                    null
                }
                val groups = search?.groups.orEmpty()
                // Render the same verified groups whose winners/counts were selected. Alternatives
                // remain catalog editions; selecting a card never rewrites the catalog or progress.
                val works = if (search != null) {
                    search.works
                } else if (editions.isEmpty() && genreSnapshot == null) {
                    activeWorks.take(64)
                } else {
                    NovelCatalogCards.select(activeWorks, eligibleEditions)
                }
                val genres = (
                    routesSnapshot.filterKeys(NovelSourceAvailability::active).values.flatten().map {
                        it.name
                    } +
                        activeWorks.flatMap { it.editions }.flatMap { it.genres }
                    )
                    .distinctBy(NovelGenres::key).sortedBy(NovelGenres::key)
                val recent = NovelCatalogCards.select(
                    activeWorks,
                    NovelCatalogCards.eligiblePage(
                        catalogSnapshot,
                        NovelPage(
                            slotsSnapshot["novel.kolnovel"].orEmpty().take(
                                3,
                            ) + slotsSnapshot["novel.cenele"].orEmpty().take(3),
                        ),
                    ).novels,
                )
                val resume = activeWorks.filter { work -> work.editions.any { it.id == resumeEdition } }
                val visibleRecent = if (querySnapshot.isBlank() &&
                    genreSnapshot == null
                ) {
                    NovelCatalogCards.excluding(recent, resume)
                } else {
                    emptyList()
                }
                NovelBrowsePresentation(
                    NovelCatalogCards.excluding(works, visibleRecent + resume),
                    genres,
                    visibleRecent,
                    groups,
                    querySnapshot,
                    session.generation,
                )
            }
            if (memory.sessions.accepts(session)) value = projected
        }
        val works = if (term.isNotBlank() &&
            (presentation.query != term || presentation.generation != session.generation)
        ) {
            emptyList()
        } else {
            presentation.works
        }
        val genres = presentation.genres
        val recent = presentation.recent
        val searchGroupsByKey = remember(presentation.groups) { presentation.groups.associateBy { it.key } }
        var alternativesKey by remember(term) { mutableStateOf<String?>(null) }
        val searchAlternatives = works.firstOrNull { it.id == alternativesKey }
        if (term.isNotBlank() && searchAlternatives != null) {
            ModalBottomSheet(
                onDismissRequest = { alternativesKey = null },
                containerColor = Design.SurfaceDark,
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Text(
                        "اختر الطبعة",
                        Modifier.padding(16.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                        items(searchAlternatives.editions, key = { it.id }) { edition ->
                            TextButton(
                                onClick = {
                                    alternativesKey = null
                                    navigator.push(NovelDetailsScreen(edition))
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    edition.title + " — " +
                                        repository.activeSources.firstOrNull {
                                            it.id == edition.sourceId
                                        }?.name.orEmpty(),
                                    color = Design.LavenderPrimary,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
        val visibleSearchEditions = remember(term, works) {
            if (term.isBlank()) emptyList() else works.flatMap { it.editions }.distinctBy { it.id }
        }
        val currentSearchEditions by rememberUpdatedState(visibleSearchEditions)
        LaunchedEffect(selection) {
            if (term.isBlank()) return@LaunchedEffect
            selection.observeEvidence(
                snapshotFlow {
                    eu.kanade.tachiyomi.search.NovelSearchSelection.EvidenceInput(
                        slots.values.flatten(),
                        currentSearchEditions,
                        catalog,
                    )
                },
            ) { if (memory.sessions.accepts(session)) memory.evidenceRevision.intValue++ }
        }
        LaunchedEffect(gridState, term) {
            snapshotFlow {
                if (gridState.isScrollInProgress) {
                    emptySet<String>()
                } else {
                    gridState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String }.toSet()
                }
            }.distinctUntilChanged().collectLatest { visible ->
                if (visible.isNotEmpty()) {
                    kotlinx.coroutines.delay(750)
                    if (term.isNotBlank()) return@collectLatest // Search has its own bounded metadata-only enrichment.
                    val editions = withContext(Dispatchers.Default) {
                        repository.catalog.value.works.filter {
                            it.id in visible
                        }.flatMap { it.editions }.map { it.id }.toSet()
                    }
                    repository.verifyCandidates(editions)
                }
            }
        }
        fun more() {
            if (moreJob?.isActive == true) return
            val next = pages.filter { (id, page) -> page != null && id !in busy }
            if (next.isEmpty()) return
            val request = session
            val requestGenre = request.context.filters["genre"]?.takeIf(String::isNotBlank)
            val requestStatus = request.context.filters["sunovelsStatus"]?.takeIf(String::isNotBlank)
            busy = busy + next.keys
            moreJob = scope.launch {
                next.forEach { (id, page) ->
                    launch {
                        try {
                            val source = repository.source(id)
                            val route = routes[id].orEmpty().firstOrNull {
                                NovelGenres.key(it.name) == requestGenre
                            }?.value
                            val result = repository.discover(
                                source,
                                request.context.query,
                                page!!,
                                if (source is SunovelsGatewaySource) {
                                    SunovelsGatewaySource.filter(
                                        route,
                                        requestStatus,
                                    )
                                } else {
                                    route
                                },
                            )
                            if (memory.sessions.accepts(request)) {
                                slots =
                                    slots + (id to (slots[id].orEmpty() + result.novels).distinctBy { it.id })
                                pages =
                                    pages + (id to result.nextPage)
                                memory.cache(searchContext)
                            }
                        } catch (
                            c: CancellationException,
                        ) {
                            throw c
                        } catch (
                            e: Exception,
                        ) {
                            novelError(e)
                            if (memory.sessions.accepts(request)) failed = failed + id
                        } finally {
                            if (memory.sessions.accepts(request)) busy = busy - id
                        }
                    }
                }
            }
        }
        NovelShell("الروايات", actions = {
            IconButton(onClick = {
                navigator.push(NovelDownloadsScreen())
            }) { Icon(Icons.Outlined.Download, "تنزيلات الروايات", tint = Design.GoldPrimary) }
            IconButton(onClick = {
                navigator.push(NovelLibraryScreen())
            }) { Icon(Icons.Outlined.Bookmarks, "مكتبة الروايات", tint = Design.GoldPrimary) }
        }) {
            eu.kanade.presentation.components.MangaroSearchField(
                value = input,
                onValueChange = { input = it },
                onSearch = {
                    term = input.trim()
                    if (term.isNotBlank()) genre = null
                },
                placeholder = "ابحث عن روايتك القادمة",
                modifier = Modifier.fillMaxWidth().padding(horizontal = Design.PageInset, vertical = 8.dp),
            )
            if (genres.isNotEmpty()) {
                LazyRow(
                    Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item {
                        eu.kanade.presentation.components.MangaroFilterChip("الكل", genre == null, onClick = {
                            genre =
                                null
                        })
                    }
                    items(genres, key = NovelGenres::key) { name ->
                        eu.kanade.presentation.components.MangaroFilterChip(
                            NovelGenres.label(name),
                            genre == NovelGenres.key(name),
                            onClick = {
                                input = ""
                                term = ""
                                genre = NovelGenres.key(name)
                            },
                        )
                    }
                }
            }
            if (SunovelsGatewayConfig.enabled &&
                !SunovelsSource.directEnabled
            ) {
                LazyRow(
                    Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val statuses = listOf(
                        null to "شمس الروايات: كل الحالات",
                        "Ongoing" to "مستمر",
                        "Completed" to "مكتمل",
                        "New" to "جديد (تصنيف المصدر)",
                    )
                    items(statuses, key = { it.first.orEmpty() }) { (value, label) ->
                        eu.kanade.presentation.components.MangaroFilterChip(label, sunStatus == value, onClick = {
                            if (sunStatus != value) {
                                memory.loadedKey = null
                                sunStatus = value
                            }
                        })
                    }
                }
            }
            BoxWithConstraints(Modifier.weight(1f)) {
                LazyVerticalGrid(
                    columns = if (maxWidth < 600.dp) GridCells.Fixed(2) else GridCells.Adaptive(160.dp),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(Design.CardGap),
                    verticalArrangement = Arrangement.spacedBy(Design.CardGap),
                ) {
                    library.firstOrNull { it.position != null }?.takeIf { resumeEdition != null }?.let { current ->
                        item(key = "continue", span = { GridItemSpan(maxLineSpan) }) {
                            Surface(
                                Modifier.fillMaxWidth().clickable {
                                    openReader(current.novel, current.position!!.chapter)
                                },
                                shape = RoundedCornerShape(14.dp),
                                color = Design.SurfaceDark,
                            ) {
                                Row(
                                    Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    NovelCover(current.novel, Modifier.size(48.dp, 72.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "متابعة القراءة",
                                            color = Design.GoldPrimary,
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                        Text(
                                            current.novel.title,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Icon(Icons.Outlined.PlayArrow, null, tint = Design.GoldPrimary)
                                }
                            }
                        }
                    }
                    if (term.isBlank() && genre == null &&
                        recent.isNotEmpty()
                    ) {
                        item(key = "updates", span = { GridItemSpan(maxLineSpan) }) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("آخر التحديثات", color = Design.TextPrimary, style = Design.SectionHeaderStyle)
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(recent, key = { it.id }) { work ->
                                        Row(
                                            Modifier.width(
                                                232.dp,
                                            ).clip(RoundedCornerShape(14.dp)).background(Design.SurfaceDark)
                                                .clickable {
                                                    navigator.push(NovelDetailsScreen(work.primary))
                                                }.padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            NovelCover(work.primary, Modifier.size(48.dp, 72.dp))
                                            Text(
                                                work.primary.title,
                                                Modifier.weight(1f),
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                color = Color.White,
                                                style = Design.CardTitleStyle,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item(key = "catalog-title", span = { GridItemSpan(maxLineSpan) }) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (genre ==
                                    null
                                ) {
                                    "اكتشف الروايات"
                                } else {
                                    NovelGenres.label(genre!!)
                                },
                                Modifier.weight(1f),
                                color = Design.TextPrimary,
                                style = Design.SectionHeaderStyle,
                            )
                            if (busy.isNotEmpty()) {
                                CircularProgressIndicator(
                                    Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = Design.LavenderPrimary,
                                )
                            }
                        }
                    }
                    if (genre != null) {
                        item(key = "genre-scope", span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                "النتائج المتاحة لهذا التصنيف",
                                color = Design.LavenderPrimary,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    if (failed.isNotEmpty()) {
                        item(key = "partial-error", span = { GridItemSpan(maxLineSpan) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "تعذّر تحميل بعض الروايات حاليًا.",
                                    Modifier.weight(1f),
                                    color = Design.LavenderPrimary,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                TextButton(onClick = {
                                    memory.loadedKey = null
                                    generation++
                                }) { Text("حاول مجددًا") }
                            }
                        }
                    }
                    if (works.isEmpty() && recent.isEmpty() && resumeEdition == null && busy.isEmpty() &&
                        failed.isEmpty()
                    ) {
                        item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                            Text("لا توجد نتائج حاليًا.", color = Design.LavenderPrimary)
                        }
                    }
                    items(works, key = { it.id }) { work ->
                        val group = searchGroupsByKey[work.id]
                        val info = group?.winner?.chapters?.label()
                        NovelCard(
                            work.primary,
                            info,
                            onAlternatives = if (term.isNotBlank() && work.editions.size > 1) {
                                ({ alternativesKey = work.id })
                            } else {
                                null
                            },
                        ) { navigator.push(NovelDetailsScreen(work.primary)) }
                    }
                    if (pages.values.any { it != null }) {
                        item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                            TextButton(
                                onClick = ::more,
                                enabled = pages.any { (id, page) ->
                                    page != null && id !in busy
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("عرض المزيد", color = Design.GoldPrimary) }
                        }
                    }
                }
            }
        }
    }
}

private data class NovelChapterPresentation(val index: NovelChapterIndex, val groups: List<NovelChapterGroup>)

class NovelDetailsScreen(private val initial: Novel) : Screen() {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val repository = remember(context) { NovelRepository.get(context) }
        val navigator = LocalNavigator.currentOrThrow
        val catalog by repository.catalog.collectAsState()
        val connectedGraph = eu.kanade.presentation.stories.rememberConnectedStoryGraph()
        val connectedRegistry = connectedGraph?.collectAsStateWithLifecycle()?.value
        val work = remember(catalog, initial.id, connectedRegistry) {
            val existing =
                catalog.work(initial.id)
                    ?: UnifiedNovelWork(NovelIdentity.initialWorkId(initial.id), listOf(initial), initial.id)
            eu.kanade.tachiyomi.data.stories.ConnectedNovelEditions.project(
                connectedRegistry,
                initial,
                existing,
                catalog,
            )
        }
        val workIds = remember(work) { work.editions.map { it.id }.toSet() }
        val library by remember(repository, workIds) {
            repository.library.map { entries -> entries.filter { it.novel.id in workIds } }
                .distinctUntilChanged().flowOn(Dispatchers.Default)
        }.collectAsState(initial = emptyList())
        val storageError by repository.storageError.collectAsState()
        val scope = rememberCoroutineScope()
        val downloadAdGate = rememberNovelDownloadAdGate()
        var selectedId by rememberSaveable { mutableStateOf<String?>(initial.id) }
        val novel = work.editions.firstOrNull { it.id == selectedId } ?: work.primary
        FocusSurface(FocusTarget(FocusContext.NOVEL_DETAILS, novel.sourceId, novel.id))
        val saved = library.any { entry -> entry.saved && work.editions.any { it.id == entry.novel.id } }
        val resume = library.filter { entry ->
            entry.position != null && work.editions.any { it.id == entry.novel.id }
        }.maxByOrNull { it.position!!.updatedAt }
        var indexErrors by remember { mutableStateOf(emptyMap<String, String>()) }
        var indexEpoch by remember { mutableIntStateOf(0) }
        var indexes by remember { mutableStateOf(emptyMap<String, NovelChapterIndex>()) }
        var busy by remember { mutableStateOf(emptySet<String>()) }
        var error by remember { mutableStateOf<String?>(null) }
        var readerOpening by remember { mutableStateOf(false) }
        val openReader = rememberSunovelsReaderEntry(onLoading = { readerOpening = it }) { failedNovel, exception ->
            error = novelError(exception)
            repository.indexSnapshot(failedNovel)?.let { indexes = indexes + (failedNovel.id to it) }
        }
        var retry by remember { mutableIntStateOf(0) }
        var descending by rememberSaveable { mutableStateOf(false) }
        var retryEditionId by remember { mutableStateOf<String?>(null) }
        var expandedDescription by rememberSaveable { mutableStateOf(false) }
        var collapsed by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
        // Selection may contain thousands of URLs; keep it out of Activity's size-limited Bundle.
        var selected by remember { mutableStateOf(emptySet<String>()) }
        var selecting by rememberSaveable { mutableStateOf(false) }
        var rangeOpen by rememberSaveable { mutableStateOf(false) }
        val detailScroll = androidx.compose.foundation.lazy.rememberLazyListState()
        var libraryOpen by rememberSaveable { mutableStateOf(false) }
        var editionsOpen by rememberSaveable { mutableStateOf(false) }
        var switching by remember { mutableStateOf<Novel?>(null) }
        var switchChapter by remember { mutableStateOf<NovelChapter?>(null) }
        var revalidatingSun by remember { mutableStateOf<Set<String>>(emptySet()) }
        var availabilityReport by remember { mutableStateOf<String?>(null) }
        var restartSunIndex by remember { mutableStateOf(false) }
        var enqueueing by remember { mutableStateOf(false) }
        var notice by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(initial.id) {
            repository.awaitLocal()
            repository.ingest(listOf(initial))
        }
        LaunchedEffect(work.editions.map { it.id }) {
            work.editions.filter { it.sourceId == SunovelsSource.SOURCE_ID }.forEach { edition ->
                launch {
                    repository.observeSunIndex(edition).collectLatest { current ->
                        indexes = indexes + (edition.id to current)
                    }
                }
            }
        }
        LaunchedEffect(work.editions.map { it.id }, novel.id, retry) {
            val request = ++indexEpoch
            val retryTarget = retryEditionId?.takeIf { it == novel.id }
            val editions = work.editions.filter {
                (NovelSourceAvailability.active(it.sourceId) || it.id == initial.id) &&
                    (retryTarget == null || it.id == retryTarget)
            }.sortedBy { it.id != selectedId }
            busy = editions.map { it.id }.toSet()
            indexErrors = indexErrors - busy
            // Cached indexes and known offline/history rows are available before any HTTP request.
            editions.forEach { edition ->
                repository.indexSnapshot(edition)?.let { indexes = indexes + (edition.id to it) }
            }
            val gate = kotlinx.coroutines.sync.Semaphore(2)
            editions.forEach { edition ->
                launch {
                    try {
                        gate.withPermit {
                            val onPage: suspend (NovelChapterIndex) -> Unit = { partial ->
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                                    if (request == indexEpoch) indexes = indexes + (edition.id to partial)
                                }
                            }
                            val shouldRefresh = if (edition.sourceId == SunovelsSource.SOURCE_ID) restartSunIndex else retry > 0
                            repository.continueIndex(edition, refresh = shouldRefresh, onPage = onPage)
                        }
                    } catch (c: CancellationException) {
                        throw c
                    } catch (e: Exception) {
                        if (request == indexEpoch) {
                            repository.indexSnapshot(edition)?.let { indexes = indexes + (edition.id to it) }
                            indexErrors = indexErrors + (edition.id to novelError(e))
                        }
                    } finally {
                        if (request == indexEpoch) busy = busy - edition.id
                    }
                }
            }
            editions.forEach { edition ->
                repository.detailSnapshot(edition)?.let { repository.ingest(listOf(it)) }
            }
            // Optional work metadata never keeps chapter selection in a loading state.
            editions.forEach { edition ->
                launch {
                    try {
                        repository.detail(edition)
                    } catch (c: CancellationException) {
                        throw c
                    } catch (e: Exception) {
                        novelError(e)
                    }
                }
            }
        }
        val knownChapters by remember(repository, novel.id) {
            combine(repository.downloads.tasks, repository.library) { tasks, entries ->
                (
                    tasks.filter { it.novel.id == novel.id }.map { it.chapter } +
                        entries.filter { it.novel.id == novel.id }.mapNotNull { it.position?.chapter }
                    ).distinctBy { it.id }
            }.distinctUntilChanged().flowOn(Dispatchers.Default)
        }.collectAsState(initial = emptyList())
        val groupBuilder = remember(novel.id) { NovelChapterGroupBuilder() }
        val cachedIndex = indexes[novel.id]
        val prepared by produceState(
            NovelChapterPresentation(NovelChapterIndex(novel.id), emptyList()),
            novel.id,
            cachedIndex,
            knownChapters,
            descending,
        ) {
            value = withContext(Dispatchers.Default) {
                val cached = cachedIndex ?: NovelChapterIndex(novel.id)
                val ids = cached.chapters.mapTo(HashSet()) { it.id }
                val missing = if (cached.complete) emptyList() else knownChapters.filter { it.id !in ids }
                val display = if (missing.isEmpty()) {
                    cached
                } else {
                    cached.copy(chapters = (cached.chapters + missing).sortedBy { it.order })
                }
                NovelChapterPresentation(display, groupBuilder.build(display, descending))
            }
        }
        val presentation = prepared.takeIf { it.index.editionId == novel.id }
        val index = presentation?.index ?: NovelChapterIndex(novel.id)
        val groups = presentation?.groups.orEmpty()
        val states by remember(repository, novel.id) {
            repository.downloads.tasks.map { tasks ->
                tasks.filter { it.novel.id == novel.id }.associate {
                    it.chapter.id to
                        it.state
                }
            }
                .distinctUntilChanged().flowOn(Dispatchers.Default)
        }.collectAsState(initial = emptyMap())
        fun toggleGroup(id: String) {
            // Freeze the visible edition during interaction; verification may change the catalog primary.
            selectedId = novel.id
            val key = novel.id + "|" + id
            collapsed = if (key in collapsed) ArrayList(collapsed - key) else ArrayList(collapsed + key)
        }
        fun openDownloads() {
            selectedId = novel.id
            rangeOpen = true
        }
        fun download(chapters: List<NovelChapter>) {
            if (enqueueing || chapters.isEmpty()) return
            selectedId = novel.id
            val pending = chapters.distinctBy { it.id }.filter {
                it.available && states[it.id] !in setOf(
                    NovelDownloadState.DONE,
                    NovelDownloadState.PENDING,
                    NovelDownloadState.RUNNING,
                    NovelDownloadState.PAUSED,
                    NovelDownloadState.DELETING,
                )
            }
            if (pending.isEmpty()) {
                notice = "الفصول محمّلة أو قيد التنزيل بالفعل"
                return
            }
            // All batch, volume, range and chapter-row actions enter the same shared gate.
            // Dismissing that gate must not leave the queue UI stuck in an enqueueing state.
            downloadAdGate(eu.kanade.tachiyomi.data.ads.DownloadAdRequest.novel(novel.id, pending.map { it.id })) {
                enqueueing = true
                error = null
                notice = null
                scope.launch {
                    try {
                        repository.downloads.enqueue(novel, pending)
                        notice = "أُضيفت الفصول إلى التنزيلات"
                        selected =
                            emptySet()
                        selecting = false
                    } catch (
                        c: CancellationException,
                    ) {
                        throw c
                    } catch (e: Exception) {
                        error = novelError(e)
                    } finally {
                        enqueueing = false
                    }
                }
            }
        }
        fun chooseEdition(target: Novel) {
            if (resume != null && resume.novel.id != target.id) {
                switchChapter = null
                switching = target
            } else {
                selectedId = target.id
                selected = emptySet()
                editionsOpen = false
            }
        }
        NovelShell("الرواية", actions = {
            IconButton(onClick = {
                selectedId = novel.id
                libraryOpen = true
            }) {
                Icon(
                    if (saved) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                    "تنظيم المكتبة",
                    tint = Design.GoldPrimary,
                )
            }
            IconButton(onClick = {
                navigator.push(NovelDownloadsScreen())
            }) { Icon(Icons.Outlined.Download, "تنزيلات الروايات", tint = Design.GoldPrimary) }
        }) {
            LazyColumn(
                Modifier.weight(1f),
                state = detailScroll,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!NovelSourceAvailability.active(novel.sourceId)) {
                    item(key = "retired-source") {
                        Text(
                            "Sunovels مستبعد مؤقتًا من الاكتشاف بسبب روابط فصول ملتبسة ومحتوى مقفل. فصولك المحمّلة وسجل القراءة محفوظان.",
                            color = Design.LavenderPrimary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        NovelCover(novel, Modifier.width(102.dp).height(150.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                novel.title,
                                color = Color.White,
                                style = MaterialTheme.typography.titleLarge.copy(
                                    textDirection = TextDirection.ContentOrRtl,
                                ),
                                fontWeight = FontWeight.Bold,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            novel.author?.let {
                                Text(
                                    it,
                                    color = Color(0xFFC3B1D0),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        textDirection = TextDirection.Content,
                                    ),
                                )
                            }
                            novel.status?.let {
                                Text(
                                    when (it.lowercase()) {
                                        "ongoing" -> "مستمرة"
                                        "completed" -> "مكتملة"
                                        else -> it
                                    },
                                    color = Design.LavenderPrimary,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            if (index.complete) {
                                Text(
                                    numeric(index.chapters.size) + " فصلًا",
                                    color = Design.GoldPrimary,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            } else {
                                Text(
                                    chapterCountLabel(
                                        index.chapters.size,
                                        index.declaredTotal ?: novel.chapterCount,
                                        novel.id in busy,
                                    ),
                                    color = Color(0xFFAE99BE),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
                resume?.let { current ->
                    item {
                        Button(
                            onClick = {
                                openReader(current.novel, current.position!!.chapter)
                            },
                            Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(
                                14.dp,
                            ),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Design.GoldPrimary,
                                contentColor = Design.BackgroundDark,
                            ),
                        ) {
                            Text("متابعة القراءة")
                        }
                        Text(
                            current.position!!.chapter.title,
                            color = Color(0xFFAE99BE),
                            style = MaterialTheme.typography.labelSmall.copy(
                                textDirection = TextDirection.ContentOrRtl,
                            ),
                        )
                        SunovelsChapterDiagnostic(current.novel, current.position!!.chapter, allowResumeMapping = true)
                    }
                }
                val firstAvailable = index.chapters.firstOrNull { it.available }
                if (index.chapters.isNotEmpty()) {
                    item {
                        if (resume ==
                            null
                        ) {
                            Button(
                                onClick = { firstAvailable?.let { openReader(novel, it) } },
                                enabled =
                                firstAvailable != null && !readerOpening,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Design.GoldPrimary,
                                    contentColor = Design.BackgroundDark,
                                ),
                            ) {
                                Text(if (readerOpening) "جاري تحميل الفصل..." else "ابدأ القراءة")
                            }
                        } else if (resume.novel.id !=
                            novel.id
                        ) {
                            TextButton(
                                onClick = {
                                    switchChapter = firstAvailable
                                    switching = novel
                                },
                                enabled =
                                firstAvailable != null,
                            ) { Text("قراءة هذه الطبعة", color = Design.GoldPrimary) }
                        }
                    }
                }
                item {
                    FilledTonalButton(
                        onClick = { openDownloads() },
                        enabled =
                        index.chapters.isNotEmpty() && !enqueueing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("تحميل الفصول")
                    }
                }
                item(key = "connected-stories") {
                    eu.kanade.presentation.stories.ConnectedStoriesSection(
                        eu.kanade.tachiyomi.data.stories.StoryDestination.Prose(novel),
                    )
                }
                item(key = "work-community") {
                    eu.kanade.presentation.community.WorkCommunitySection(novelWorkCommunityContext(novel))
                }
                if (novel.description.isNotBlank()) {
                    item {
                        Text(
                            novel.description,
                            color = Color(0xFFD2C7DB),
                            maxLines = if (expandedDescription) Int.MAX_VALUE else 4,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                textDirection = TextDirection.ContentOrRtl,
                            ),
                        )
                        TextButton(onClick = {
                            expandedDescription = !expandedDescription
                        }, contentPadding = PaddingValues(0.dp)) {
                            Text(
                                if (expandedDescription) "عرض أقل" else "قراءة الوصف كاملًا",
                                color = Design.LavenderPrimary,
                            )
                        }
                    }
                }
                if (novel.genres.isNotEmpty()) {
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(novel.genres.distinctBy(NovelGenres::key), key = NovelGenres::key) { tag ->
                                SuggestionChip(onClick = {
                                    navigator.push(NovelHomeScreen(NovelGenres.key(tag)))
                                }, label = { Text(NovelGenres.label(tag)) }, border = null)
                            }
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("الفصول", Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.Bold)
                        TextButton(onClick = {
                            selectedId = novel.id
                            selecting = !selecting
                            selected = emptySet()
                        }) { Text(if (selecting) "تم" else "اختيار الفصول", color = Design.GoldPrimary) }
                        IconButton(onClick = {
                            descending = !descending
                        }) { Icon(Icons.Outlined.SwapVert, "تغيير ترتيب الفصول", tint = Design.LavenderPrimary) }
                    }
                }
                if (work.editions.size > 1) {
                    item {
                        TextButton(onClick = {
                            selectedId = novel.id
                            editionsOpen = true
                        }, contentPadding = PaddingValues(0.dp)) {
                            Text("اختيار الطبعة", color = Design.LavenderPrimary)
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            openDownloads()
                        }, enabled = index.chapters.isNotEmpty() && !enqueueing) {
                            Text("تحميل الفصول", color = Design.GoldPrimary)
                        }
                        if (selecting) {
                            TextButton(onClick = {
                                scope.launch {
                                    selected =
                                        withContext(Dispatchers.Default) {
                                            index.chapters.filter { it.available }.map { it.id }.toSet()
                                        }
                                }
                            }) { Text("تحديد الكل", color = Design.LavenderPrimary) }
                        }
                    }
                }
                if (novel.id in busy) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(
                                Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Design.LavenderPrimary,
                            )
                            Text(
                                if (index.chapters.isEmpty()) "جار تحميل قائمة الفصول..." else "جار استكمال الفصول...",
                                color = Design.LavenderPrimary,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
                if (novel.id !in busy && !index.complete && index.nextPage != null &&
                    indexErrors[novel.id] == null
                ) {
                    item {
                        TextButton(onClick = {
                            restartSunIndex = false
                            retryEditionId = novel.id
                            retry++
                        }) { Text("تحميل المزيد من الفصول", color = Design.GoldPrimary) }
                    }
                }
                error?.let { message ->
                    item {
                        NovelFailure(message, novel.url) {
                            error = null
                            restartSunIndex = true
                            retryEditionId =
                                novel.id
                            retry++
                        }
                    }
                }
                if (indexErrors[novel.id] != null && novel.id !in busy) {
                    item(key = "index-recovery") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                if (index.failureState == NovelIndexState.ACCESS_RESTRICTED) {
                                    "يتطلب المصدر إذن وصول لإكمال قائمة الفصول"
                                } else {
                                    "تعذر استكمال بعض الفصول"
                                },
                                color = Color(0xFFE5B5AB),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = {
                                restartSunIndex = false
                                retryEditionId = novel.id
                                retry++
                            }) {
                                Text("إعادة المحاولة", color = Design.LavenderPrimary)
                            }
                            if (index.failureState == NovelIndexState.ACCESS_RESTRICTED) {
                                NovelFailure("تحقق من متطلبات الوصول لدى المصدر", novel.url) {
                                    restartSunIndex = false
                                    retryEditionId =
                                        novel.id
                                    retry++
                                }
                            }
                        }
                    }
                }
                if (index.complete && novel.id !in busy && indexErrors[novel.id] == null) {
                    item(key = "index-ready") {
                        Text(
                            "تم تحميل الفصول المتاحة",
                            color = Design.LavenderPrimary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                storageError?.let { message ->
                    item {
                        Text(message, color = Color(0xFFE5B5AB), style = MaterialTheme.typography.bodySmall)
                        if (repository.libraryRestoreFailed) {
                            TextButton(onClick = {
                                scope.launch { repository.retryLibraryRestore() }
                            }) { Text("حاول مجددًا") }
                        }
                    }
                }
                notice?.let { message ->
                    item { Text(message, color = Design.GoldPrimary, style = MaterialTheme.typography.labelSmall) }
                }
                index.notice?.let { message ->
                    item(key = "source-index-notice") {
                        Text(message, color = Color(0xFFE5B5AB), style = MaterialTheme.typography.bodySmall)
                    }
                }
                groups.forEach { group ->
                    if (group.title != null) {
                        item(key = "group-" + novel.id + "|" + group.id) {
                            Surface(
                                Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                color = Color(0xFF241A30),
                            ) {
                                Row(
                                    Modifier.clickable {
                                        toggleGroup(group.id)
                                    }.padding(horizontal = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                        Text(
                                            group.title,
                                            color = Color.White,
                                            fontWeight = FontWeight.SemiBold,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            if (index.complete) {
                                                numeric(group.chapters.size) + " فصلًا"
                                            } else {
                                                chapterCountLabel(
                                                    group.chapters.size,
                                                    group.declaredCount,
                                                    novel.id in busy,
                                                )
                                            },
                                            color = Color(0xFFAE99BE),
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                    IconButton(
                                        onClick = { download(group.chapters) },
                                        enabled =
                                        index.complete && !enqueueing,
                                    ) {
                                        Icon(
                                            Icons.Outlined.Download,
                                            if (group.realVolume) "تحميل المجلد" else "تحميل هذه الفصول",
                                            tint = Design.GoldPrimary,
                                        )
                                    }
                                    IconButton(onClick = { toggleGroup(group.id) }) {
                                        Icon(
                                            if (novel.id + "|" + group.id in
                                                collapsed
                                            ) {
                                                Icons.Outlined.ExpandMore
                                            } else {
                                                Icons.Outlined.ExpandLess
                                            },
                                            if (novel.id + "|" + group.id in
                                                collapsed
                                            ) {
                                                "فتح المجلد"
                                            } else {
                                                "طي المجلد"
                                            },
                                            tint = Design.LavenderPrimary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (novel.id + "|" + group.id !in
                        collapsed
                    ) {
                        items(group.chapters, key = { it.id }, contentType = { "chapter" }) { chapter ->
                            NovelChapterRow(
                                chapter, states[chapter.id], selecting, chapter.id in selected,
                                onSelect = {
                                    selected =
                                        if (chapter.id in selected) selected - chapter.id else selected + chapter.id
                                },
                                onRead = {
                                    if (resume != null &&
                                        resume.novel.id != novel.id
                                    ) {
                                        switchChapter = chapter
                                        switching = novel
                                    } else {
                                        openReader(novel, chapter)
                                    }
                                },
                                onDownload = { download(listOf(chapter)) },
                                onRevalidate = if (SunovelsSource.diagnosticsEnabled &&
                                    novel.sourceId == SunovelsSource.SOURCE_ID &&
                                    SunovelsSource.directEnabled &&
                                    SunovelsAccess.state(chapter) in
                                    setOf(
                                        SunovelsChapterState.INDEXED_UNVERIFIED,
                                        SunovelsChapterState.EXPLICITLY_LOCKED,
                                        SunovelsChapterState.TEMPORARILY_UNAVAILABLE,
                                        SunovelsChapterState.MISSING,
                                    ) &&
                                    chapter.publishedRows.size <= 1 && chapter.id !in revalidatingSun
                                ) {
                                    (
                                        {
                                            val target = novel
                                            revalidatingSun = revalidatingSun + chapter.id
                                            scope.launch {
                                                try {
                                                    repository.revalidateSunChapter(target, chapter)
                                                    notice = "تم التحقق من المحتوى العام لهذا الفصل."
                                                } catch (
                                                    c: CancellationException,
                                                ) {
                                                    throw c
                                                } catch (e: Exception) {
                                                    notice = novelError(e)
                                                } finally {
                                                    if (SunovelsSource.diagnosticsEnabled) {
                                                        availabilityReport =
                                                            repository.sunovelsDiagnostic(target, chapter)
                                                    }
                                                    repository.indexSnapshot(target)?.let {
                                                        indexes =
                                                            indexes + (target.id to it)
                                                    }
                                                    revalidatingSun = revalidatingSun - chapter.id
                                                }
                                            }
                                        }
                                        )
                                } else {
                                    null
                                },
                                onCommunity = {
                                    selectedId = novel.id
                                    navigator.push(
                                        eu.kanade.presentation.community.CommunityCommentsScreen(
                                            novelCommunityContext(novel, chapter),
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }
                if (index.state() == NovelIndexState.VERIFIED_EMPTY && novel.id !in busy && error == null &&
                    indexErrors[novel.id] == null
                ) {
                    item { Text("لا توجد فصول منشورة في الفهرس المتحقق منه حاليًا.", color = Color(0xFFAE99BE)) }
                }
            }
            if (selecting &&
                selected.isNotEmpty()
            ) {
                Button(
                    onClick = {
                        download(index.chapters.filter { it.id in selected })
                    },
                    enabled = !enqueueing,
                    modifier = Modifier.fillMaxWidth().padding(
                        horizontal = 16.dp,
                        vertical = 6.dp,
                    ),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Design.GoldPrimary,
                        contentColor = Design.BackgroundDark,
                    ),
                ) {
                    Text("تحميل المحدد · " + numeric(selected.size))
                }
            }
        }
        availabilityReport?.let { value ->
            AlertDialog(
                onDismissRequest = {
                    availabilityReport = null
                },
                title = { Text("تشخيص الفصل · تحقق من الإتاحة") },
                text = {
                    Text(
                        value,
                        Modifier.heightIn(
                            max = 420.dp,
                        ).verticalScroll(androidx.compose.foundation.rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(
                            android.content.Context.CLIPBOARD_SERVICE,
                        ) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText("Sunovels Availability Diagnostic", value),
                        )
                    }) { Text("Copy Diagnostic Report") }
                },
                dismissButton = { TextButton(onClick = { availabilityReport = null }) { Text("إغلاق") } },
            )
        }
        if (libraryOpen) NovelLibraryShelfSheet(novel, repository, onDismiss = { libraryOpen = false })
        if (rangeOpen) {
            NovelDownloadSelectionSheet(
                indexes[novel.id] ?: NovelChapterIndex(novel.id),
                currentChapterId = resume?.takeIf { it.novel.id == novel.id }?.position?.chapter?.id,
                knownCurrent = resume?.takeIf { it.novel.id == novel.id }?.position?.chapter,
                onLoadThrough = { count ->
                    val edition = novel
                    val loaded = if (count ==
                        null
                    ) {
                        repository.completeIndex(edition)
                    } else {
                        repository.indexThrough(edition, count)
                    }
                    indexes = indexes + (edition.id to loaded)
                    loaded
                },
                onDismiss = { rangeOpen = false },
                onSelect = {
                    rangeOpen = false
                    selectedId = novel.id
                    selecting =
                        true
                },
                onDownload = { chapters ->
                    rangeOpen = false
                    download(chapters)
                },
            )
        }
        if (editionsOpen) {
            ModalBottomSheet(onDismissRequest = { editionsOpen = false }, containerColor = Design.SurfaceDark) {
                Column(
                    Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("طبعات الرواية", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "الروايات والترجمات لأصحاب حقوقها.",
                        color = Color(0xFFAE99BE),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    work.editions.forEachIndexed { number, edition ->
                        val evidence = catalog.evidence[edition.id]
                        Text("الطبعة " + numeric(number + 1), color = Design.GoldPrimary)
                        Text(
                            edition.title,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                textDirection = TextDirection.ContentOrRtl,
                            ),
                        )
                        if (evidence?.accessWorks ==
                            false
                        ) {
                            Text(
                                "تعذّر فتح هذه الطبعة حاليًا.",
                                color = Color(0xFFE5B5AB),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        if (evidence?.complete ==
                            true
                        ) {
                            Text(
                                numeric(evidence.availableCount) + " فصلًا في القائمة",
                                color = Color(0xFFAE99BE),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        if (work.editions.size >
                            1
                        ) {
                            TextButton(onClick = { chooseEdition(edition) }) {
                                Text(
                                    if (novel.id ==
                                        edition.id
                                    ) {
                                        "الطبعة الحالية"
                                    } else {
                                        "اختيار هذه الطبعة"
                                    },
                                    color = Design.LavenderPrimary,
                                )
                            }
                        }
                    }
                }
            }
        }
        switching?.let { target ->
            AlertDialog(
                onDismissRequest = {
                    switching = null
                },
                title = {
                    Text("طبعة أخرى")
                },
                text = { Text("قد يختلف ترتيب الفصول والترجمة. سيبقى موضع قراءتك السابق وتنزيلاتك محفوظين.") },
                confirmButton = {
                    TextButton(onClick = {
                        selectedId = target.id
                        selected = emptySet()
                        editionsOpen = false
                        switching =
                            null
                        switchChapter?.let { openReader(target, it) }
                        switchChapter = null
                    }) { Text("اختيار الطبعة") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        switching =
                            null
                    }) { Text("رجوع") }
                },
            )
        }
    }
}

class NovelLibraryScreen : Screen() {
    @Composable override fun Content() {
        OpenNovelAppSection(downloads = false)
    }
}

internal fun numeric(value: Int) = WesternDigits.isolate(value.toString())
