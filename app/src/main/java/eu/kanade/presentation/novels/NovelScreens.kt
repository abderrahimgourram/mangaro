package eu.kanade.presentation.novels

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.presentation.theme.MangaroDesignSystem as Design
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.novels.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import tachiyomi.core.common.util.lang.WesternDigits

internal fun novelError(error: Exception): String {
    android.util.Log.w("MangaroNovels","Novel operation failed",error)
    return (error as? NovelSourceFailure)?.publicMessage ?: "تعذّر التحميل حاليًا. حاول مجددًا."
}

@Composable
internal fun NovelShell(title: String, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    val navigator=LocalNavigator.currentOrThrow
    NovelForegroundRefresh(NovelRepository.get(LocalContext.current))
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(Modifier.fillMaxSize().background(Design.BackgroundDark).safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(end=12.dp),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick={navigator.pop()}) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"رجوع") }
                Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,
                    maxLines=1,overflow=TextOverflow.Ellipsis,color=Color.White)
                actions()
            }
            val storageError by NovelRepository.get(LocalContext.current).storageError.collectAsState()
            storageError?.let { Text(it,Modifier.padding(horizontal=20.dp,vertical=8.dp),color=Color(0xFFE5B5AB),style=MaterialTheme.typography.labelMedium) }
            content()
        }
    }
}

@Composable
internal fun NovelCover(novel: Novel, modifier: Modifier) {
    val loader=NovelRepository.get(LocalContext.current).covers
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(Design.SurfaceHigh),contentAlignment=Alignment.Center) {
        Icon(Icons.Outlined.MenuBook,null,Modifier.size(40.dp),tint=Design.GoldPrimary.copy(alpha=.5f))
        novel.cover?.let { AsyncImage(it,null,imageLoader=loader,modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Crop) }
    }
}

@Composable
private fun NovelCard(novel: Novel, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Design.SurfaceDark).clickable(onClick=onOpen).padding(8.dp),
        verticalArrangement=Arrangement.spacedBy(8.dp)) {
        NovelCover(novel,Modifier.fillMaxWidth().aspectRatio(.72f))
        Text(novel.title,style=MaterialTheme.typography.bodyMedium.copy(textDirection=TextDirection.ContentOrRtl),
            color=Color.White,fontWeight=FontWeight.SemiBold,maxLines=2,minLines=2,overflow=TextOverflow.Ellipsis)
    }
}

@Composable
internal fun NovelFailure(message: String, url: String, retry: () -> Unit) {
    val browser=LocalUriHandler.current
    Column(Modifier.fillMaxWidth().padding(20.dp),horizontalAlignment=Alignment.CenterHorizontally,
        verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(message,color=Color(0xFFD0BEDD),style=MaterialTheme.typography.bodyMedium)
        TextButton(onClick=retry) { Text("حاول مجددًا",color=Design.GoldPrimary) }
        TextButton(onClick={runCatching { browser.openUri(url) }}) { Text("فتح الرواية في الموقع",color=Design.LavenderPrimary) }
    }
}

/** One catalogue, streamed source-by-source; provider identity stays in maintenance/edition information. */
private class NovelBrowseMemory {
    val failed = mutableStateOf(emptySet<String>())
    var query: Pair<String, String?>? = null
    val slots = mutableStateOf(emptyMap<String, List<Novel>>())
    val pages = mutableStateOf(emptyMap<String, Int?>())
    val routes = mutableStateOf(emptyMap<String, List<NovelGenre>>())
    var loadedKey: Pair<String, String?>? = null
}

class NovelHomeScreen(private val initialGenre: String? = null) : Screen() {
    @Transient private var browseMemory: NovelBrowseMemory? = null
    @Composable override fun Content() {
        val context = LocalContext.current
        val repository = remember(context) { NovelRepository.get(context) }
        val navigator = LocalNavigator.currentOrThrow
        val catalog by repository.catalog.collectAsState()
        val library by repository.library.collectAsState()
        var input by rememberSaveable { mutableStateOf("") }
        var term by rememberSaveable { mutableStateOf("") }
        var genre by rememberSaveable { mutableStateOf(initialGenre) }
        var generation by remember { mutableIntStateOf(0) }
        val memory = remember { browseMemory ?: NovelBrowseMemory().also { browseMemory = it } }
        var slots by memory.slots
        var pages by memory.pages
        var routes by memory.routes
        val gridState = rememberLazyGridState()
        var busy by remember { mutableStateOf(emptySet<String>()) }
        var failed by memory.failed
        var epoch by remember { mutableIntStateOf(0) }
        val scope = rememberCoroutineScope()
        var moreJob by remember { mutableStateOf<Job?>(null) }
        LaunchedEffect(input) { kotlinx.coroutines.delay(350); if (input.trim() != term) { term = input.trim(); if (term.isNotBlank()) genre = null } }
        LaunchedEffect(term, genre, generation) {
            if (memory.loadedKey == (term to genre)) return@LaunchedEffect
            val request = ++epoch
            moreJob?.cancel()
            if (memory.query != (term to genre)) { slots = emptyMap(); pages = emptyMap() }
            memory.query = term to genre
            failed = emptySet()
            busy = repository.sources.map { it.id }.toSet()
            kotlinx.coroutines.coroutineScope { repository.sources.forEach { source -> launch {
                try {
                    if (genre == null && slots[source.id].isNullOrEmpty()) {
                        repository.discoverySnapshot(source, term, 1, null)?.let { cached ->
                            repository.ingest(cached.novels)
                            if (request == epoch) {
                                slots = slots + (source.id to cached.novels); pages = pages + (source.id to cached.nextPage)
                                if (cached.genres.isNotEmpty()) routes = routes + (source.id to cached.genres)
                            }
                        }
                    }
                    if (genre != null && routes[source.id].isNullOrEmpty()) {
                        val initialPage = repository.discover(source, "", 1, null)
                        if (request == epoch) routes = routes + (source.id to initialPage.genres)
                    }
                    val route = routes[source.id].orEmpty().firstOrNull { NovelGenres.key(it.name) == genre }?.value
                    val result = if (genre != null && route == null) {
                        val available = catalog.works.flatMap { it.editions }.filter { it.sourceId == source.id && it.genres.any { g -> NovelGenres.key(g) == genre } && (term.isBlank() || NovelIdentity.normalize(it.title).contains(NovelIdentity.normalize(term))) }
                        NovelPage(available)
                    } else repository.discover(source, term, 1, route)
                    if (request == epoch) {
                        slots = slots + (source.id to result.novels); pages = pages + (source.id to result.nextPage)
                        if (result.genres.isNotEmpty()) routes = routes + (source.id to result.genres)
                    }
                } catch (c: CancellationException) { throw c }
                catch (e: Exception) { novelError(e); if (request == epoch) failed = failed + source.id }
                finally { if (request == epoch) busy = busy - source.id }
            } } }
            if (request == epoch) memory.loadedKey = term to genre
        }
        val editions = remember(slots) {
            // Deterministic interleaving; response speed never decides edition preference or catalogue order.
            val lists = repository.sources.map { slots[it.id].orEmpty() }
            buildList { for (i in 0 until (lists.maxOfOrNull { it.size } ?: 0)) lists.forEach { it.getOrNull(i)?.let(::add) } }
        }
        LaunchedEffect(editions.map { it.id }) { repository.verifyCandidates(editions.map { it.id }.toSet()) }
        val byEdition = remember(catalog) { catalog.works.flatMap { w -> w.editions.map { it.id to w } }.toMap() }
        val works = remember(editions, byEdition, term, genre) {
            if (editions.isEmpty() && term.isBlank() && genre == null) catalog.works.take(64)
            else editions.mapNotNull { byEdition[it.id] }.distinctBy { it.id }
        }
        val genres = remember(routes, catalog) {
            (routes.values.flatten().map { it.name } + catalog.works.flatMap { it.editions }.flatMap { it.genres })
                .distinctBy(NovelGenres::key).sortedBy(NovelGenres::key)
        }
        val recent = remember(slots, byEdition) {
            (slots["novel.kolnovel"].orEmpty().take(3) + slots["novel.cenele"].orEmpty().take(3))
                .mapNotNull { byEdition[it.id] }.distinctBy { it.id }
        }
        fun more() {
            if (moreJob?.isActive == true) return
            val next = pages.filter { (id, page) -> page != null && id !in busy }
            if (next.isEmpty()) return
            val request = epoch
            busy = busy + next.keys
            moreJob = scope.launch {
                next.forEach { (id, page) -> launch {
                    try {
                        val source = repository.source(id)
                        val route = routes[id].orEmpty().firstOrNull { NovelGenres.key(it.name) == genre }?.value
                        val result = repository.discover(source, term, page!!, route)
                        if (request == epoch) { slots = slots + (id to (slots[id].orEmpty() + result.novels).distinctBy { it.id }); pages = pages + (id to result.nextPage) }
                    } catch (c: CancellationException) { throw c }
                    catch (e: Exception) { novelError(e); if (request == epoch) failed = failed + id }
                    finally { if (request == epoch) busy = busy - id }
                } }
            }
        }
        NovelShell("الروايات", actions = {
            IconButton(onClick = { navigator.push(NovelDownloadsScreen()) }) { Icon(Icons.Outlined.Download, "تنزيلات الروايات", tint = Design.GoldPrimary) }
            IconButton(onClick = { navigator.push(NovelLibraryScreen()) }) { Icon(Icons.Outlined.Bookmarks, "مكتبة الروايات", tint = Design.GoldPrimary) }
            IconButton(onClick = { navigator.push(NovelCreditsScreen()) }) { Icon(Icons.Outlined.Info, "حول الروايات", tint = Design.LavenderPrimary) }
        }) {
            OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                placeholder = { Text("ابحث عن روايتك القادمة") }, singleLine = true, shape = RoundedCornerShape(16.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { term = input.trim(); if (term.isNotBlank()) genre = null }),
                trailingIcon = { IconButton(onClick = { term = input.trim(); if (term.isNotBlank()) genre = null }) { Icon(Icons.Outlined.Search, "بحث") } })
            if (genres.isNotEmpty()) LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { FilterChip(genre == null, onClick = { genre = null }, label = { Text("الكل") }, border = null) }
                items(genres, key = NovelGenres::key) { name -> FilterChip(genre == NovelGenres.key(name), onClick = { input = ""; term = ""; genre = NovelGenres.key(name) }, label = { Text(NovelGenres.label(name)) }, border = null) }
            }
            BoxWithConstraints(Modifier.weight(1f)) {
                LazyVerticalGrid(columns = if (maxWidth < 600.dp) GridCells.Fixed(2) else GridCells.Adaptive(160.dp),
                    state = gridState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    library.firstOrNull { it.position != null }?.let { current -> item(key = "continue", span = { GridItemSpan(maxLineSpan) }) {
                        Surface(Modifier.fillMaxWidth().clickable { navigator.push(NovelReaderScreen(current.novel, current.position!!.chapter)) },
                            shape = RoundedCornerShape(14.dp), color = Design.SurfaceDark) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                NovelCover(current.novel, Modifier.size(34.dp, 48.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("متابعة القراءة", color = Design.GoldPrimary, style = MaterialTheme.typography.labelMedium)
                                    Text(current.novel.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Icon(Icons.Outlined.PlayArrow, null, tint = Design.GoldPrimary)
                            }
                        }
                    } }
                    if (term.isBlank() && genre == null && recent.isNotEmpty()) item(key = "updates", span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("آخر التحديثات", color = Design.GoldPrimary, style = MaterialTheme.typography.titleSmall)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(recent, key = { it.id }) { work ->
                                    Row(Modifier.width(190.dp).clip(RoundedCornerShape(14.dp)).background(Design.SurfaceDark)
                                        .clickable { navigator.push(NovelDetailsScreen(work.primary)) }.padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        NovelCover(work.primary, Modifier.size(34.dp, 48.dp))
                                        Text(work.primary.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                            color = Color.White, style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.ContentOrRtl))
                                    }
                                }
                            }
                        }
                    }
                    item(key = "catalog-title", span = { GridItemSpan(maxLineSpan) }) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (genre == null) "اكتشف الروايات" else NovelGenres.label(genre!!), Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.SemiBold)
                            if (busy.isNotEmpty()) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Design.LavenderPrimary)
                        }
                    }
                    if (genre != null) item(key = "genre-scope", span = { GridItemSpan(maxLineSpan) }) {
                        Text("النتائج المتاحة لهذا التصنيف", color = Design.LavenderPrimary, style = MaterialTheme.typography.labelSmall)
                    }
                    if (failed.isNotEmpty()) item(key = "partial-error", span = { GridItemSpan(maxLineSpan) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("تعذّر تحميل بعض الروايات حاليًا.", Modifier.weight(1f), color = Design.LavenderPrimary, style = MaterialTheme.typography.labelSmall)
                            TextButton(onClick = { memory.loadedKey = null; generation++ }) { Text("حاول مجددًا") }
                        }
                    }
                    if (works.isEmpty() && busy.isEmpty() && failed.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                        Text("لا توجد نتائج حاليًا.", color = Design.LavenderPrimary)
                    }
                    items(works, key = { it.id }) { work -> NovelCard(work.primary) { navigator.push(NovelDetailsScreen(work.primary)) } }
                    if (pages.values.any { it != null }) item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                        TextButton(onClick = ::more, enabled = pages.any { (id, page) -> page != null && id !in busy }, modifier = Modifier.fillMaxWidth()) { Text("عرض المزيد", color = Design.GoldPrimary) }
                    }
                }
            }
        }
    }
}

private data class NovelChapterPresentation(val index: NovelChapterIndex, val groups: List<NovelChapterGroup>)

class NovelDetailsScreen(private val initial: Novel) : Screen() {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable override fun Content() {
        val context = LocalContext.current
        val repository = remember(context) { NovelRepository.get(context) }
        val navigator = LocalNavigator.currentOrThrow
        val catalog by repository.catalog.collectAsState()
        val library by repository.library.collectAsState()
        val storageError by repository.storageError.collectAsState()
        val work = catalog.work(initial.id) ?: UnifiedNovelWork(NovelIdentity.initialWorkId(initial.id), listOf(initial), initial.id)
        val scope = rememberCoroutineScope()
        var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
        val novel = work.editions.firstOrNull { it.id == selectedId } ?: work.primary
        val saved = library.any { entry -> entry.saved && work.editions.any { it.id == entry.novel.id } }
        val resume = library.filter { entry -> entry.position != null && work.editions.any { it.id == entry.novel.id } }.maxByOrNull { it.position!!.updatedAt }
        var indexErrors by remember { mutableStateOf(emptyMap<String, String>()) }
        var indexEpoch by remember { mutableIntStateOf(0) }
        var indexes by remember { mutableStateOf(emptyMap<String, NovelChapterIndex>()) }
        var busy by remember { mutableStateOf(emptySet<String>()) }
        var error by remember { mutableStateOf<String?>(null) }
        var retry by remember { mutableIntStateOf(0) }
        var descending by rememberSaveable { mutableStateOf(false) }
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
        var enqueueing by remember { mutableStateOf(false) }
        var notice by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(initial.id) { repository.awaitLocal(); repository.ingest(listOf(initial)) }
        LaunchedEffect(work.editions.map { it.id }, retry) {
            val request = ++indexEpoch
            val editions = work.editions
            busy = editions.map { it.id }.toSet(); indexErrors = emptyMap()
            // Cached indexes and known offline/history rows are available before any HTTP request.
            editions.forEach { edition ->
                repository.indexSnapshot(edition)?.let { indexes = indexes + (edition.id to it) }
            }
            val gate = kotlinx.coroutines.sync.Semaphore(2)
            editions.forEach { edition -> launch {
                try {
                    gate.withPermit {
                        repository.completeIndex(edition, refresh = retry > 0) { partial ->
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                                if (request == indexEpoch) indexes = indexes + (edition.id to partial)
                            }
                        }
                    }
                } catch (c: CancellationException) { throw c }
                catch (e: Exception) {
                    if (request == indexEpoch) {
                        repository.indexSnapshot(edition)?.let { indexes = indexes + (edition.id to it) }
                        indexErrors = indexErrors + (edition.id to novelError(e))
                    }
                }
                finally { if (request == indexEpoch) busy = busy - edition.id }
            } }
            // Optional work metadata never keeps chapter selection in a loading state.
            editions.forEach { edition -> launch {
                try {
                    repository.detail(edition)
                } catch (c: CancellationException) { throw c }
                catch (e: Exception) { novelError(e) }
            } }
        }
        val knownChapters by remember(repository, novel.id) {
            combine(repository.downloads.tasks, repository.library) { tasks, entries ->
                (tasks.filter { it.novel.id == novel.id }.map { it.chapter } +
                    entries.filter { it.novel.id == novel.id }.mapNotNull { it.position?.chapter }).distinctBy { it.id }
            }.distinctUntilChanged().flowOn(Dispatchers.Default)
        }.collectAsState(initial = emptyList())
        val groupBuilder = remember(novel.id) { NovelChapterGroupBuilder() }
        val cachedIndex = indexes[novel.id]
        val prepared by produceState(NovelChapterPresentation(NovelChapterIndex(novel.id), emptyList()), novel.id, cachedIndex, knownChapters, descending) {
            value = withContext(Dispatchers.Default) {
                val cached = cachedIndex ?: NovelChapterIndex(novel.id)
                val ids = cached.chapters.mapTo(HashSet()) { it.id }
                val missing = if (cached.complete) emptyList() else knownChapters.filter { it.id !in ids }
                val display = if (missing.isEmpty()) cached else cached.copy(
                    chapters = (cached.chapters + missing).sortedBy { it.order })
                NovelChapterPresentation(display, groupBuilder.build(display, descending))
            }
        }
        val presentation = prepared.takeIf { it.index.editionId == novel.id }
        val index = presentation?.index ?: NovelChapterIndex(novel.id)
        val groups = presentation?.groups.orEmpty()
        val states by remember(repository, novel.id) {
            repository.downloads.tasks.map { tasks -> tasks.filter { it.novel.id == novel.id }.associate { it.chapter.id to it.state } }
                .distinctUntilChanged().flowOn(Dispatchers.Default)
        }.collectAsState(initial = emptyMap())
        fun toggleGroup(id: String) {
            // Freeze the visible edition during interaction; verification may change the catalog primary.
            selectedId = novel.id
            val key = novel.id + "|" + id
            collapsed = if (key in collapsed) ArrayList(collapsed - key) else ArrayList(collapsed + key)
        }
        fun openDownloads() { selectedId = novel.id; rangeOpen = true }
        fun download(chapters: List<NovelChapter>) {
            if (enqueueing || chapters.isEmpty()) return
            selectedId = novel.id
            enqueueing = true; error = null; notice = null
            scope.launch {
                try { repository.downloads.enqueue(novel, chapters); notice = "أُضيفت الفصول إلى التنزيلات"; selected = emptySet(); selecting = false }
                catch (c: CancellationException) { throw c } catch (e: Exception) { error = novelError(e) }
                finally { enqueueing = false }
            }
        }
        fun chooseEdition(target: Novel) {
            if (resume != null && resume.novel.id != target.id) { switchChapter = null; switching = target }
            else { selectedId = target.id; selected = emptySet(); editionsOpen = false }
        }
        NovelShell("الرواية", actions = {
            IconButton(onClick = { selectedId = novel.id; libraryOpen = true }) { Icon(if (saved) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder, "تنظيم المكتبة", tint = Design.GoldPrimary) }
            IconButton(onClick = { navigator.push(NovelDownloadsScreen()) }) { Icon(Icons.Outlined.Download, "تنزيلات الروايات", tint = Design.GoldPrimary) }
        }) {
            LazyColumn(Modifier.weight(1f), state = detailScroll, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        NovelCover(novel, Modifier.width(102.dp).height(150.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(novel.title, color = Color.White, style = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.ContentOrRtl), fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            novel.author?.let { Text(it, color = Color(0xFFC3B1D0), style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content)) }
                            novel.status?.let { Text(when (it.lowercase()) { "ongoing" -> "مستمرة"; "completed" -> "مكتملة"; else -> it }, color = Design.LavenderPrimary, style = MaterialTheme.typography.labelSmall) }
                            if (index.complete) Text(numeric(index.chapters.size) + " فصلًا", color = Design.GoldPrimary, style = MaterialTheme.typography.labelLarge)
                            else Text(chapterCountLabel(index.chapters.size, novel.chapterCount, novel.id in busy), color = Color(0xFFAE99BE), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                resume?.let { current -> item {
                    Button(onClick = { navigator.push(NovelReaderScreen(current.novel, current.position!!.chapter)) }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Design.GoldPrimary, contentColor = Design.BackgroundDark)) { Text("متابعة القراءة") }
                    Text(current.position!!.chapter.title, color = Color(0xFFAE99BE), style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.ContentOrRtl))
                } }
                if (index.chapters.isNotEmpty()) item {
                    if (resume == null) Button(onClick = { navigator.push(NovelReaderScreen(novel, index.chapters.first())) }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Design.GoldPrimary, contentColor = Design.BackgroundDark)) { Text("ابدأ القراءة") }
                    else if (resume.novel.id != novel.id) TextButton(onClick = { switchChapter = index.chapters.first(); switching = novel }) { Text("قراءة هذه الطبعة", color = Design.GoldPrimary) }
                }
                item {
                    FilledTonalButton(onClick = { openDownloads() }, enabled = index.chapters.isNotEmpty() && !enqueueing, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("تحميل الفصول")
                    }
                }
                item {
                    TextButton(onClick = {
                        selectedId = novel.id
                        navigator.push(eu.kanade.presentation.community.CommunityCommentsScreen(novelWorkCommunityContext(novel)))
                    }, contentPadding = PaddingValues(0.dp)) {
                        Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("تعليقات الرواية")
                    }
                }
                if (novel.description.isNotBlank()) item {
                    Text(novel.description, color = Color(0xFFD2C7DB), maxLines = if (expandedDescription) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.ContentOrRtl))
                    TextButton(onClick = { expandedDescription = !expandedDescription }, contentPadding = PaddingValues(0.dp)) { Text(if (expandedDescription) "عرض أقل" else "قراءة الوصف كاملًا", color = Design.LavenderPrimary) }
                }
                if (novel.genres.isNotEmpty()) item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(novel.genres.distinctBy(NovelGenres::key), key = NovelGenres::key) { tag ->
                            SuggestionChip(onClick = { navigator.push(NovelHomeScreen(NovelGenres.key(tag))) }, label = { Text(NovelGenres.label(tag)) }, border = null)
                        }
                    }
                }
                item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("الفصول", Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { selectedId = novel.id; selecting = !selecting; selected = emptySet() }) { Text(if (selecting) "تم" else "اختيار الفصول", color = Design.GoldPrimary) }
                    IconButton(onClick = { descending = !descending }) { Icon(Icons.Outlined.SwapVert, "تغيير ترتيب الفصول", tint = Design.LavenderPrimary) }
                } }
                if (work.editions.size > 1) item {
                    TextButton(onClick = { selectedId = novel.id; editionsOpen = true }, contentPadding = PaddingValues(0.dp)) { Text("اختيار الطبعة", color = Design.LavenderPrimary) }
                }
                item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { openDownloads() }, enabled = index.chapters.isNotEmpty() && !enqueueing) { Text("تحميل الفصول", color = Design.GoldPrimary) }
                    if (selecting) TextButton(onClick = { scope.launch { selected = withContext(Dispatchers.Default) { index.chapters.filter { it.available }.map { it.id }.toSet() } } }) { Text("تحديد الكل", color = Design.LavenderPrimary) }
                } }
                if (novel.id in busy) item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Design.LavenderPrimary)
                        Text("جارٍ تحميل الفصول…", color = Design.LavenderPrimary, style = MaterialTheme.typography.labelSmall)
                    }
                }
                (error ?: indexErrors[novel.id])?.let { message -> item { NovelFailure(message, novel.url) { error = null; retry++ } } }
                storageError?.let { message -> item {
                    Text(message, color = Color(0xFFE5B5AB), style = MaterialTheme.typography.bodySmall)
                    if (repository.libraryRestoreFailed) TextButton(onClick = { scope.launch { repository.retryLibraryRestore() } }) { Text("حاول مجددًا") }
                } }
                notice?.let { message -> item { Text(message, color = Design.GoldPrimary, style = MaterialTheme.typography.labelSmall) } }
                groups.forEach { group ->
                    if (group.title != null) item(key = "group-" + novel.id + "|" + group.id) {
                        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = Color(0xFF241A30)) {
                            Row(Modifier.clickable { toggleGroup(group.id) }.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                    Text(group.title, color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(if (index.complete) numeric(group.chapters.size) + " فصلًا" else chapterCountLabel(group.chapters.size, group.declaredCount, novel.id in busy), color = Color(0xFFAE99BE), style = MaterialTheme.typography.labelSmall)
                                }
                                IconButton(onClick = { download(group.chapters) }, enabled = index.complete && !enqueueing) { Icon(Icons.Outlined.Download, if (group.realVolume) "تحميل المجلد" else "تحميل هذه الفصول", tint = Design.GoldPrimary) }
                                IconButton(onClick = { toggleGroup(group.id) }) {
                                    Icon(if (novel.id + "|" + group.id in collapsed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                                        if (novel.id + "|" + group.id in collapsed) "فتح المجلد" else "طي المجلد", tint = Design.LavenderPrimary)
                                }
                            }
                        }
                    }
                    if (novel.id + "|" + group.id !in collapsed) items(group.chapters, key = { it.id }, contentType = { "chapter" }) { chapter ->
                        NovelChapterRow(chapter, states[chapter.id], selecting, chapter.id in selected,
                            onSelect = { selected = if (chapter.id in selected) selected - chapter.id else selected + chapter.id },
                            onRead = { if (resume != null && resume.novel.id != novel.id) { switchChapter = chapter; switching = novel } else navigator.push(NovelReaderScreen(novel, chapter)) },
                            onDownload = { download(listOf(chapter)) },
                            onCommunity = { selectedId = novel.id; navigator.push(eu.kanade.presentation.community.CommunityCommentsScreen(novelCommunityContext(novel, chapter))) })
                    }
                }
                if (index.chapters.isEmpty() && novel.id !in busy && error == null && indexErrors[novel.id] == null) item { Text("لا توجد فصول متاحة حاليًا.", color = Color(0xFFAE99BE)) }
            }
            if (selecting && selected.isNotEmpty()) Button(onClick = { download(index.chapters.filter { it.id in selected }) }, enabled = !enqueueing, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), colors = ButtonDefaults.buttonColors(containerColor = Design.GoldPrimary, contentColor = Design.BackgroundDark)) { Text("تحميل المحدد · " + numeric(selected.size)) }
        }
        if (libraryOpen) NovelLibraryShelfSheet(novel, repository, onDismiss = { libraryOpen = false })
        if (rangeOpen) NovelDownloadSelectionSheet(indexes[novel.id] ?: NovelChapterIndex(novel.id),
            currentChapterId = resume?.takeIf { it.novel.id == novel.id }?.position?.chapter?.id,
            knownCurrent = resume?.takeIf { it.novel.id == novel.id }?.position?.chapter,
            onLoadThrough = { count ->
                val edition = novel
                val loaded = if (count == null) repository.completeIndex(edition) else repository.indexThrough(edition, count)
                indexes = indexes + (edition.id to loaded)
                loaded
            }, onDismiss = { rangeOpen = false }, onSelect = { rangeOpen = false; selectedId = novel.id; selecting = true }, onDownload = { chapters -> rangeOpen = false; download(chapters) })
        if (editionsOpen) ModalBottomSheet(onDismissRequest = { editionsOpen = false }, containerColor = Design.SurfaceDark) {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("طبعات الرواية", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text("الروايات والترجمات لأصحاب حقوقها.", color = Color(0xFFAE99BE), style = MaterialTheme.typography.bodySmall)
                work.editions.forEachIndexed { number, edition ->
                    val evidence = catalog.evidence[edition.id]
                    Text("الطبعة " + numeric(number + 1), color = Design.GoldPrimary)
                    Text(edition.title, color = Color.White, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.ContentOrRtl))
                    if (evidence?.accessWorks == false) Text("تعذّر فتح هذه الطبعة حاليًا.", color = Color(0xFFE5B5AB), style = MaterialTheme.typography.labelSmall)
                    if (evidence?.complete == true) Text(numeric(evidence.availableCount) + " فصلًا في القائمة", color = Color(0xFFAE99BE), style = MaterialTheme.typography.labelSmall)
                    if (work.editions.size > 1) TextButton(onClick = { chooseEdition(edition) }) { Text(if (novel.id == edition.id) "الطبعة الحالية" else "اختيار هذه الطبعة", color = Design.LavenderPrimary) }
                }
            }
        }
        switching?.let { target -> AlertDialog(onDismissRequest = { switching = null }, title = { Text("طبعة أخرى") }, text = { Text("قد يختلف ترتيب الفصول والترجمة. سيبقى موضع قراءتك السابق وتنزيلاتك محفوظين.") },
            confirmButton = { TextButton(onClick = { selectedId = target.id; selected = emptySet(); editionsOpen = false; switching = null
                switchChapter?.let { navigator.push(NovelReaderScreen(target, it)) }; switchChapter = null }) { Text("اختيار الطبعة") } }, dismissButton = { TextButton(onClick = { switching = null }) { Text("رجوع") } }) }
    }
}

class NovelLibraryScreen : Screen() {
    @Composable override fun Content() {
        OpenNovelAppSection(downloads = false)
    }
}

class NovelCreditsScreen : Screen() {
    @Composable override fun Content() {
        val repository = NovelRepository.get(LocalContext.current)
        val browser = LocalUriHandler.current
        NovelShell("حول الروايات") {
            LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item { Text("حكايات من مكتبات الروايات العربية", color = Design.GoldPrimary, style = MaterialTheme.typography.titleMedium) }
                item { Text("الروايات والأغلفة والترجمات لأصحاب حقوقها. تجد هنا معلومات المصادر وروابطها الأصلية.", color = Color(0xFFCEC0D8)) }
                items(repository.sources, key = { it.id }) { source -> TextButton(onClick = { runCatching { browser.openUri(source.baseUrl) } }) { Text(source.name, color = Design.LavenderPrimary) } }
                item { Text("الخط العربي: Noto Naskh Arabic — SIL Open Font License 1.1", color = Color(0xFFAE99BE), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

internal fun numeric(value: Int) = WesternDigits.isolate(value.toString())
