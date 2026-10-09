package eu.kanade.presentation.novels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.theme.MangaroDesignSystem as Design
import eu.kanade.tachiyomi.novels.NovelRepository
import eu.kanade.tachiyomi.ui.home.HomeScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Shared content selector; payload stores and stable edition identities remain unchanged. */
@Composable
fun MangaroContentTabs(novels: Boolean, onSelect: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Design.BackgroundDark).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(false to "مانهوا", true to "روايات").forEach { (value, title) ->
            FilterChip(selected = novels == value, onClick = { onSelect(value) }, label = { Text(title) }, border = null,
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Design.SurfaceDark,
                    selectedLabelColor = Design.GoldPrimary))
        }
    }
}

/** Old saved destinations route to the same Library/Downloads tabs, without a second manager. */
@Composable
internal fun OpenNovelAppSection(downloads: Boolean) {
    val navigator = LocalNavigator.currentOrThrow
    LaunchedEffect(downloads) {
        navigator.popUntilRoot()
        HomeScreen.openTab(if (downloads) HomeScreen.Tab.NovelDownloads else HomeScreen.Tab.Library(novels = true))
    }
}

@Composable
fun HomeNovelShelf() {
    val context = LocalContext.current
    val repository = remember(context) { NovelRepository.get(context) }
    val catalog by repository.catalog.collectAsState()
    val navigator = LocalNavigator.currentOrThrow
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var loading by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(lifecycle, retry) {
        // Optional discovery starts after a rendered frame and stops when Home is no longer visible.
        withFrameNanos { }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            repository.awaitLocal()
            loading = true
            try {
                val lanes = Semaphore(2)
                coroutineScope {
                    repository.sources.forEach { source -> launch {
                        lanes.withPermit {
                            try { repository.discover(source, "", 1, null) }
                            catch (c: CancellationException) { throw c }
                            catch (e: Exception) { android.util.Log.w("MangaroNovels", "Home shelf unavailable: ${source.id}", e) }
                        }
                    } }
                }
            } finally { loading = false }
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("روايات", Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { navigator.push(NovelHomeScreen()) }) { Text("استكشف", color = Design.GoldPrimary) }
        }
        if (catalog.works.isNotEmpty()) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(catalog.works.take(12), key = { it.id }) { work ->
                    Column(Modifier.width(96.dp).clickable { navigator.push(NovelDetailsScreen(work.primary)) },
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        NovelCover(work.primary, Modifier.fillMaxWidth().height(136.dp))
                        Text(work.primary.title, color = Color(0xFFD7CBE1), maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.ContentOrRtl))
                    }
                }
            }
        } else if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = Design.LavenderPrimary)
        } else {
            TextButton(onClick = { retry++ }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("حاول تحميل الروايات مجددًا") }
        }
    }
}

@Composable
fun NovelLibraryContent(history: Boolean = false) {
    val context = LocalContext.current
    val repository = remember(context) { NovelRepository.get(context) }
    NovelForegroundRefresh(repository)
    val library by (if (history) repository.unifiedHistory else repository.unifiedLibrary).collectAsState()
    val tasks by repository.downloads.tasks.collectAsState()
    val restored by repository.restored.collectAsState()
    val storageError by repository.storageError.collectAsState()
    val navigator = LocalNavigator.currentOrThrow
    val scope = rememberCoroutineScope()
    val sortPreference = remember { Injekt.get<PreferenceStore>().getString("novels_library_sort", "recent") }
    var sort by rememberSaveable { mutableStateOf(sortPreference.get()) }
    var filter by rememberSaveable { mutableStateOf("all") }
    var query by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val offline = remember(tasks) { tasks.filter { it.state == eu.kanade.tachiyomi.novels.NovelDownloadState.DONE }.map { it.novel.id }.toSet() }
    val visible = remember(library, filter, query, sort, offline) {
        val selected = library.filter { entry ->
            entry.work.primary.title.contains(query.trim(), ignoreCase = true) && when (filter) {
                "favorite" -> entry.entries.any { it.favorite }
                "offline" -> entry.entries.any { it.novel.id in offline }
                "reading", "completed", "planned" -> entry.entries.any { it.readingStatus == filter }
                else -> true
            }
        }
        when (sort) {
            "title" -> selected.sortedBy { it.work.primary.title }
            "added" -> selected.sortedByDescending { it.entries.maxOf { item -> item.addedAt } }
            else -> selected.sortedByDescending { it.latest?.position?.updatedAt ?: 0 }
        }
    }
    Column(Modifier.fillMaxSize().background(Design.BackgroundDark)) {
        OutlinedTextField(query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text("ابحث في مكتبتك") }, singleLine = true, shape = RoundedCornerShape(16.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(listOf("all" to "الكل", "favorite" to "المفضلة", "reading" to "أقرأ الآن", "completed" to "مكتملة", "planned" to "للقراءة لاحقًا", "offline" to "دون إنترنت")) { (value, title) ->
                FilterChip(filter == value, onClick = { filter = value }, label = { Text(title) }, border = null)
            }
        }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("recent" to "آخر قراءة", "added" to "الأحدث", "title" to "العنوان").forEach { (value, title) ->
                TextButton(onClick = { sort = value; sortPreference.set(value) }) { Text(title, color = if (sort == value) Design.GoldPrimary else Design.LavenderPrimary) }
            }
        }
        (error ?: storageError)?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = Color(0xFFE5B5AB)) }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!restored) item { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
            else if (visible.isEmpty() && storageError == null) item {
                Text(when {
                    query.isNotBlank() -> "لا توجد روايات تطابق بحثك."
                    filter == "favorite" -> "لم تضف روايات إلى المفضلة بعد."
                    filter != "all" -> "لا توجد روايات في هذا القسم بعد."
                    history -> "رواياتك المقروءة ستظهر هنا."
                    else -> "أضف الروايات التي تحبها لتعود إليها بسهولة."
                }, color = Design.LavenderPrimary)
            }
            items(visible, key = { it.work.id }) { entry ->
                val novel = entry.work.primary
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Design.SurfaceDark)
                    .clickable { navigator.push(NovelDetailsScreen(novel)) }.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    NovelCover(novel, Modifier.width(58.dp).height(82.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(novel.title, color = Color.White, fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.ContentOrRtl))
                        entry.latest?.let { last ->
                            Text(last.position!!.chapter.title, color = Design.LavenderPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.ContentOrRtl))
                            TextButton(onClick = { navigator.push(NovelReaderScreen(last.novel, last.position.chapter)) }, contentPadding = PaddingValues(0.dp)) { Text("متابعة القراءة", color = Design.GoldPrimary) }
                        }
                        var statusOpen by remember { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { statusOpen = true }, contentPadding = PaddingValues(0.dp)) { Text(when (entry.entries.first().readingStatus) { "completed" -> "مكتملة"; "planned" -> "للقراءة لاحقًا"; else -> "أقرأ الآن" }) }
                            DropdownMenu(statusOpen, onDismissRequest = { statusOpen = false }) {
                                listOf("reading" to "أقرأ الآن", "completed" to "مكتملة", "planned" to "للقراءة لاحقًا").forEach { (value, title) ->
                                    DropdownMenuItem(text = { Text(title) }, onClick = { statusOpen = false; scope.launch {
                                        try { repository.updateLibrary(novel, status = value) } catch (c: CancellationException) { throw c } catch (e: Exception) { error = novelError(e) }
                                    } })
                                }
                            }
                        }
                    }
                    Column {
                        IconButton(onClick = { scope.launch {
                            try { repository.updateLibrary(novel, favorite = !entry.entries.any { it.favorite }) } catch (c: CancellationException) { throw c } catch (e: Exception) { error = novelError(e) }
                        } }) { Icon(if (entry.entries.any { it.favorite }) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder, "المفضلة", tint = Design.GoldPrimary) }
                        if (!history) IconButton(onClick = { scope.launch { repository.setSaved(novel, false) } }) { Icon(Icons.Outlined.BookmarkRemove, "إزالة من المكتبة", tint = Design.LavenderPrimary) }
                    }
                }
            }
        }
    }
}
