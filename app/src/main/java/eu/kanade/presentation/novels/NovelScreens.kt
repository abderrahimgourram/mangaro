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
import tachiyomi.core.common.util.lang.WesternDigits

internal fun novelError(error: Exception): String {
    android.util.Log.w("MangaroNovels","Novel operation failed",error)
    return (error as? NovelSourceFailure)?.publicMessage ?: "تعذّر التحميل حاليًا. حاول مجددًا."
}

@Composable
internal fun NovelShell(title: String, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    val navigator=LocalNavigator.currentOrThrow
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
    Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Design.SurfaceDark).clickable(onClick=onOpen).padding(8.dp),
        verticalArrangement=Arrangement.spacedBy(8.dp)) {
        NovelCover(novel,Modifier.fillMaxWidth().aspectRatio(2f/3f))
        Text(novel.title,style=MaterialTheme.typography.bodyMedium.copy(textDirection=TextDirection.ContentOrRtl),
            color=Color.White,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
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
class NovelHomeScreen(private val initialGenre: String? = null) : Screen() {
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
        var slots by remember { mutableStateOf(emptyMap<String, List<Novel>>()) }
        var pages by remember { mutableStateOf(emptyMap<String, Int?>()) }
        var routes by remember { mutableStateOf(emptyMap<String, List<NovelGenre>>()) }
        var busy by remember { mutableStateOf(emptySet<String>()) }
        var failed by remember { mutableStateOf(emptySet<String>()) }
        var epoch by remember { mutableIntStateOf(0) }
        val scope = rememberCoroutineScope()
        var moreJob by remember { mutableStateOf<Job?>(null) }
        LaunchedEffect(input) { kotlinx.coroutines.delay(350); if (input.trim() != term) { term = input.trim(); if (term.isNotBlank()) genre = null } }
        LaunchedEffect(term, genre, generation) {
            val request = ++epoch
            moreJob?.cancel(); slots = emptyMap(); pages = emptyMap(); failed = emptySet()
            busy = repository.sources.map { it.id }.toSet()
            repository.sources.forEach { source -> launch {
                try {
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
            } }
        }
        val editions = remember(slots) {
            // Deterministic interleaving; response speed never decides edition preference or catalogue order.
            val lists = repository.sources.map { slots[it.id].orEmpty() }
            buildList { for (i in 0 until (lists.maxOfOrNull { it.size } ?: 0)) lists.forEach { it.getOrNull(i)?.let(::add) } }
        }
        LaunchedEffect(editions.map { it.id }) { repository.verifyCandidates(editions.map { it.id }.toSet()) }
        val byEdition = remember(catalog) { catalog.works.flatMap { w -> w.editions.map { it.id to w } }.toMap() }
        val works = remember(editions, byEdition) { editions.mapNotNull { byEdition[it.id] }.distinctBy { it.id } }
        val genres = remember(routes, catalog) {
            (routes.values.flatten().map { it.name } + catalog.works.flatMap { it.editions }.flatMap { it.genres })
                .distinctBy(NovelGenres::key).sortedBy(NovelGenres::key)
        }
        val recent = remember(slots, byEdition) {
            (slots["novel.kolnovel"].orEmpty().take(3) + slots["novel.cenele"].orEmpty().take(3))
                .mapNotNull { byEdition[it.id] }.distinctBy { it.id }
        }
        fun more() {
            if (busy.isNotEmpty()) return
            val next = pages.filterValues { it != null }
            if (next.isEmpty()) return
            val request = epoch
            busy = next.keys
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
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { term = input.trim() }),
                trailingIcon = { IconButton(onClick = { term = input.trim() }) { Icon(Icons.Outlined.Search, "بحث") } })
            if (genres.isNotEmpty()) LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { FilterChip(genre == null, onClick = { genre = null }, label = { Text("الكل") }, border = null) }
                items(genres, key = NovelGenres::key) { name -> FilterChip(genre == NovelGenres.key(name), onClick = { input = ""; term = ""; genre = NovelGenres.key(name) }, label = { Text(NovelGenres.label(name)) }, border = null) }
            }
            library.firstOrNull { it.position != null }?.let { current ->
                Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clickable { navigator.push(NovelReaderScreen(current.novel, current.position!!.chapter)) },
                    shape = RoundedCornerShape(16.dp), color = Color(0xFF261B30)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NovelCover(current.novel, Modifier.size(36.dp, 50.dp))
                        Column(Modifier.weight(1f)) {
                            Text("تابع القراءة", color = Design.GoldPrimary, style = MaterialTheme.typography.labelMedium)
                            Text(current.novel.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Outlined.PlayArrow, null, tint = Design.GoldPrimary)
                    }
                }
            }
            if (term.isBlank() && genre == null && recent.isNotEmpty()) {
                Text("آخر التحديثات", Modifier.padding(horizontal = 20.dp, vertical = 4.dp), color = Design.GoldPrimary, style = MaterialTheme.typography.labelLarge)
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(recent, key = { it.id }) { work ->
                        Row(Modifier.width(205.dp).clip(RoundedCornerShape(14.dp)).background(Design.SurfaceDark).clickable { navigator.push(NovelDetailsScreen(work.primary)) }.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NovelCover(work.primary, Modifier.size(42.dp, 60.dp))
                            Text(work.primary.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, color = Color.White, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.ContentOrRtl))
                        }
                    }
                }
            }
            if (failed.isNotEmpty()) Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("تعذّر تحميل بعض الروايات حاليًا.", Modifier.weight(1f), color = Color(0xFFBBA4CE), style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { generation++ }) { Text("حاول مجددًا", color = Design.GoldPrimary) }
            }
            if (genre != null) Text("النتائج المتاحة لهذا التصنيف", Modifier.padding(horizontal = 20.dp), color = Color(0xFFAE99BE), style = MaterialTheme.typography.labelSmall)
            if (busy.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp), color = Design.GoldPrimary)
            if (works.isEmpty() && busy.isEmpty()) Text("لا توجد نتائج حاليًا.", Modifier.padding(24.dp), color = Color(0xFFBBA4CE))
            LazyVerticalGrid(GridCells.Adaptive(136.dp), Modifier.weight(1f), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(works, key = { it.id }) { work -> NovelCard(work.primary) { navigator.push(NovelDetailsScreen(work.primary)) } }
            }
            if (pages.values.any { it != null }) TextButton(onClick = ::more, enabled = busy.isEmpty(), modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("عرض المزيد", color = Design.GoldPrimary) }
        }
    }
}

class NovelDetailsScreen(private val initial: Novel) : Screen() {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable override fun Content() {
        val context = LocalContext.current
        val repository = remember(context) { NovelRepository.get(context) }
        val navigator = LocalNavigator.currentOrThrow
        val catalog by repository.catalog.collectAsState()
        val library by repository.library.collectAsState()
        val queue by repository.downloads.tasks.collectAsState()
        val work = catalog.work(initial.id) ?: UnifiedNovelWork(NovelIdentity.initialWorkId(initial.id), listOf(initial), initial.id)
        val scope = rememberCoroutineScope()
        var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
        val novel = work.editions.firstOrNull { it.id == selectedId } ?: work.primary
        val saved = library.any { entry -> entry.saved && work.editions.any { it.id == entry.novel.id } }
        val resume = library.filter { entry -> entry.position != null && work.editions.any { it.id == entry.novel.id } }.maxByOrNull { it.position!!.updatedAt }
        var indexes by remember { mutableStateOf(emptyMap<String, NovelChapterIndex>()) }
        var busy by remember { mutableStateOf(emptySet<String>()) }
        var error by remember { mutableStateOf<String?>(null) }
        var retry by remember { mutableIntStateOf(0) }
        var descending by rememberSaveable { mutableStateOf(false) }
        var expandedDescription by rememberSaveable { mutableStateOf(false) }
        var collapsed by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
        // Selection may contain thousands of URLs; keep it out of Activity's size-limited Bundle.
        var selected by remember { mutableStateOf(arrayListOf<String>()) }
        var selecting by rememberSaveable { mutableStateOf(false) }
        var rangeOpen by rememberSaveable { mutableStateOf(false) }
        var editionsOpen by rememberSaveable { mutableStateOf(false) }
        var switching by remember { mutableStateOf<Novel?>(null) }
        var switchChapter by remember { mutableStateOf<NovelChapter?>(null) }
        var enqueueing by remember { mutableStateOf(false) }
        var notice by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(initial.id) { repository.awaitLocal(); repository.ingest(listOf(initial)) }
        LaunchedEffect(work.editions.map { it.id }, retry) {
            busy = work.editions.map { it.id }.toSet(); error = null
            val gate = kotlinx.coroutines.sync.Semaphore(2)
            work.editions.forEach { edition -> launch {
                gate.acquire()
                try {
                    var details = edition
                    try { details = repository.detail(edition) } catch (c: CancellationException) { throw c } catch (e: Exception) { novelError(e) }
                    repository.indexSnapshot(details)?.let { indexes = indexes + (edition.id to it) }
                    val index = repository.completeIndex(details, refresh = retry > 0) { partial -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) { indexes = indexes + (edition.id to partial) } }
                    if (work.editions.size > 1) repository.verifyEditionAccess(details, index)
                } catch (c: CancellationException) { throw c }
                catch (e: Exception) { error = novelError(e) }
                finally { gate.release(); busy = busy - edition.id }
            } }
        }
        val index = indexes[novel.id] ?: NovelChapterIndex(novel.id)
        val states = remember(queue, novel.id) { queue.filter { it.novel.id == novel.id }.associate { it.chapter.id to it.state } }
        val groups = remember(index, descending) { chapterGroups(index, descending) }
        fun download(chapters: List<NovelChapter>) {
            if (enqueueing || chapters.isEmpty()) return
            enqueueing = true
            scope.launch {
                try { repository.downloads.enqueue(novel, chapters); notice = "أُضيفت الفصول إلى التنزيلات"; selected = arrayListOf(); selecting = false }
                catch (c: CancellationException) { throw c } catch (e: Exception) { error = novelError(e) }
                finally { enqueueing = false }
            }
        }
        fun chooseEdition(target: Novel) {
            if (resume != null && resume.novel.id != target.id) { switchChapter = null; switching = target }
            else { selectedId = target.id; selected = arrayListOf(); collapsed = arrayListOf(); editionsOpen = false }
        }
        NovelShell("الرواية", actions = {
            IconButton(onClick = { scope.launch { repository.setSaved(novel, !saved) } }) { Icon(if (saved) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder, "مكتبة الروايات", tint = Design.GoldPrimary) }
            IconButton(onClick = { navigator.push(NovelDownloadsScreen()) }) { Icon(Icons.Outlined.Download, "تنزيلات الروايات", tint = Design.GoldPrimary) }
            IconButton(onClick = { editionsOpen = true }) { Icon(Icons.Outlined.Info, "معلومات الرواية والطبعات", tint = Design.LavenderPrimary) }
        }) {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        NovelCover(novel, Modifier.width(102.dp).height(150.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(novel.title, color = Color.White, style = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.ContentOrRtl), fontWeight = FontWeight.Bold)
                            novel.author?.let { Text(it, color = Color(0xFFC3B1D0), style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content)) }
                            novel.status?.let { Text(when (it.lowercase()) { "ongoing" -> "مستمرة"; "completed" -> "مكتملة"; else -> it }, color = Design.LavenderPrimary, style = MaterialTheme.typography.labelSmall) }
                            if (index.complete) Text(numeric(index.chapters.size) + " فصلًا", color = Design.GoldPrimary, style = MaterialTheme.typography.labelLarge)
                            else Text("جارٍ استكمال الفصول · " + numeric(index.chapters.size), color = Color(0xFFAE99BE), style = MaterialTheme.typography.labelSmall)
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
                if (novel.description.isNotBlank()) item {
                    Text(novel.description, color = Color(0xFFD2C7DB), maxLines = if (expandedDescription) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.ContentOrRtl))
                    TextButton(onClick = { expandedDescription = !expandedDescription }, contentPadding = PaddingValues(0.dp)) { Text(if (expandedDescription) "عرض أقل" else "قراءة الوصف كاملًا", color = Design.LavenderPrimary) }
                }
                if (novel.genres.isNotEmpty()) item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { novel.genres.distinctBy(NovelGenres::key).forEach { tag -> SuggestionChip(onClick = { navigator.push(NovelHomeScreen(NovelGenres.key(tag))) }, label = { Text(NovelGenres.label(tag)) }, border = null) } }
                }
                item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("الفصول", Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { selectedId = novel.id; selecting = !selecting; selected = arrayListOf() }) { Text(if (selecting) "تم" else "اختيار الفصول", color = Design.GoldPrimary) }
                    IconButton(onClick = { descending = !descending }) { Icon(Icons.Outlined.SwapVert, "تغيير ترتيب الفصول", tint = Design.LavenderPrimary) }
                } }
                item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { rangeOpen = true }, enabled = index.complete && !enqueueing) { Text("تحميل الفصول", color = Design.GoldPrimary) }
                    if (selecting) TextButton(onClick = { selected = ArrayList(index.chapters.filter { it.available }.map { it.id }) }) { Text("تحديد الكل", color = Design.LavenderPrimary) }
                } }
                if (novel.id in busy) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Design.GoldPrimary) }
                error?.let { message -> item { NovelFailure(message, novel.url) { retry++ } } }
                notice?.let { message -> item { Text(message, color = Design.GoldPrimary, style = MaterialTheme.typography.labelSmall) } }
                groups.forEach { group ->
                    if (group.title != null) item(key = "group-" + group.id) {
                        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = Color(0xFF241A30)) {
                            Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).clickable { collapsed = if (group.id in collapsed) ArrayList(collapsed - group.id) else ArrayList(collapsed + group.id) }.padding(vertical = 12.dp)) {
                                    Text(group.title, color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                    Text(numeric(group.chapters.size) + " فصلًا" + if (!index.complete) " · جارٍ الاستكمال" else "", color = Color(0xFFAE99BE), style = MaterialTheme.typography.labelSmall)
                                }
                                IconButton(onClick = { download(group.chapters) }, enabled = index.complete && !enqueueing) { Icon(Icons.Outlined.Download, if (group.realVolume) "تحميل المجلد" else "تحميل هذه الفصول", tint = Design.GoldPrimary) }
                                Icon(if (group.id in collapsed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess, null, tint = Design.LavenderPrimary)
                            }
                        }
                    }
                    if (group.id !in collapsed) items(group.chapters, key = { it.id }) { chapter ->
                        NovelChapterRow(chapter, states[chapter.id], selecting, chapter.id in selected,
                            onSelect = { selected = if (chapter.id in selected) ArrayList(selected - chapter.id) else ArrayList(selected + chapter.id) },
                            onRead = { if (resume != null && resume.novel.id != novel.id) { switchChapter = chapter; switching = novel } else navigator.push(NovelReaderScreen(novel, chapter)) },
                            onDownload = { download(listOf(chapter)) })
                    }
                }
                if (index.chapters.isEmpty() && novel.id !in busy && error == null) item { Text("لا توجد فصول متاحة حاليًا.", color = Color(0xFFAE99BE)) }
            }
            if (selecting && selected.isNotEmpty()) Button(onClick = { download(index.chapters.filter { it.id in selected }) }, enabled = !enqueueing, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), colors = ButtonDefaults.buttonColors(containerColor = Design.GoldPrimary, contentColor = Design.BackgroundDark)) { Text("تحميل " + numeric(selected.size) + " فصلًا") }
        }
        if (rangeOpen) NovelDownloadSelectionSheet(index, onDismiss = { rangeOpen = false }, onDownload = { chapters -> rangeOpen = false; download(chapters) })
        if (editionsOpen) ModalBottomSheet(onDismissRequest = { editionsOpen = false }, containerColor = Design.SurfaceDark) {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("معلومات الرواية", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text("الروايات والترجمات لأصحاب حقوقها.", color = Color(0xFFAE99BE), style = MaterialTheme.typography.bodySmall)
                work.editions.forEach { edition ->
                    val evidence = catalog.evidence[edition.id]
                    Text(repository.source(edition.sourceId).name, color = Design.GoldPrimary)
                    Text(edition.title, color = Color.White, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.ContentOrRtl))
                    if (evidence?.accessWorks == false) Text("تعذّر فتح هذه الطبعة حاليًا.", color = Color(0xFFE5B5AB), style = MaterialTheme.typography.labelSmall)
                    if (evidence?.complete == true) Text(numeric(evidence.availableCount) + " فصلًا في القائمة", color = Color(0xFFAE99BE), style = MaterialTheme.typography.labelSmall)
                    if (work.editions.size > 1) TextButton(onClick = { chooseEdition(edition) }) { Text(if (novel.id == edition.id) "الطبعة الحالية" else "اختيار هذه الطبعة", color = Design.LavenderPrimary) }
                    val browser = LocalUriHandler.current
                    TextButton(onClick = { runCatching { browser.openUri(edition.url) } }) { Text("زيارة الموقع الأصلي", color = Design.LavenderPrimary) }
                }
            }
        }
        switching?.let { target -> AlertDialog(onDismissRequest = { switching = null }, title = { Text("طبعة أخرى") }, text = { Text("قد يختلف ترتيب الفصول والترجمة. سيبقى موضع قراءتك السابق وتنزيلاتك محفوظين.") },
            confirmButton = { TextButton(onClick = { selectedId = target.id; selected = arrayListOf(); collapsed = arrayListOf(); editionsOpen = false; switching = null
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
                item { Text("الروايات والأغلفة والترجمات لأصحاب حقوقها. تتوفر معلومات كل طبعة ورابطها الأصلي من صفحة الرواية.", color = Color(0xFFCEC0D8)) }
                items(repository.sources, key = { it.id }) { source -> TextButton(onClick = { runCatching { browser.openUri(source.baseUrl) } }) { Text(source.name, color = Design.LavenderPrimary) } }
                item { Text("الخط العربي: Noto Naskh Arabic — SIL Open Font License 1.1", color = Color(0xFFAE99BE), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

internal fun numeric(value: Int) = WesternDigits.isolate(value.toString())
