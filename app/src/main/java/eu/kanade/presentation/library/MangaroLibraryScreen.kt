package eu.kanade.presentation.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.home.MangaroContinueReading
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.history.model.HistoryWithRelations
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.theme.MangaroDesignSystem
import eu.kanade.tachiyomi.ui.library.LibraryItem
import eu.kanade.tachiyomi.ui.library.LibraryViewModel
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.presentation.core.components.material.PullRefresh

@Composable
fun MangaroLibraryScreen(
    state: LibraryViewModel.State,
    sort: LibrarySort,
    onSearch: (String?) -> Unit,
    onSort: (LibrarySort.Type) -> Unit,
    onMangaClick: (Long) -> Unit,
    onManageManga: (Manga) -> Unit,
    onRefresh: () -> Unit,
    refreshing: Boolean,
    unreadFilter: TriState,
    downloadedFilter: TriState,
    downloadedOnly: Boolean,
    onUnreadFilter: (TriState) -> Unit,
    onDownloadedFilter: (TriState) -> Unit,
    onFilters: () -> Unit,
    onUpdates: () -> Unit,
    onDiscover: () -> Unit,
    onContinue: (HistoryWithRelations) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedShelf by rememberSaveable { mutableStateOf<Long?>(null) }
    var sortExpanded by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val shelfChoices = remember(state.shelves, state.libraryData.categories) {
        val managed = state.shelves.map { it.categoryId }.toSet()
        listOf(null to "الكل") + state.shelves.map { it.categoryId to it.shelf.title } +
            state.libraryData.categories.filterNot { it.isSystemCategory || it.id in managed }.map { it.id to it.name }
    }
    val selectedTitle = shelfChoices.firstOrNull { it.first == selectedShelf }?.second ?: "الكل"
    val effectiveShelf = selectedShelf.takeIf { id -> shelfChoices.any { it.first == id } }
    val mangas = remember(state.libraryData.collection, effectiveShelf) {
        state.libraryData.collection.filter { effectiveShelf == null || effectiveShelf in it.libraryManga.categories }
    }
    val visibleIds = remember(mangas) { mangas.mapTo(HashSet()) { it.id } }
    val continueHistory = remember(visibleIds, state.continueReading) {
        state.continueReading.firstOrNull { it.mangaId in visibleIds }
    }
    val sortChoices = remember {
        listOf(LibrarySort.Type.Alphabetical to "العنوان", LibrarySort.Type.LastRead to "آخر قراءة",
            LibrarySort.Type.LastUpdate to "آخر تحديث", LibrarySort.Type.UnreadCount to "غير المقروء",
            LibrarySort.Type.DateAdded to "تاريخ الإضافة")
    }
    LaunchedEffect(state.searchQuery != null) {
        if (state.searchQuery != null) focusRequester.requestFocus()
    }

    Column(modifier.fillMaxSize().background(MangaroDesignSystem.BackgroundDark).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("المكتبة", modifier = Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            IconButton(onClick = onUpdates) {
                Icon(Icons.Outlined.Update, "الفصول الجديدة", tint = Color(0xFFCBBED5), modifier = Modifier.size(22.dp))
            }
            IconButton(onClick = { onSearch(if (state.searchQuery == null) "" else null) }) {
                Icon(if (state.searchQuery == null) Icons.Outlined.Search else Icons.Outlined.Close, "البحث في المكتبة", tint = Color(0xFFCBBED5), modifier = Modifier.size(22.dp))
            }
            Box {
                IconButton(onClick = { sortExpanded = true }) {
                    Icon(Icons.Outlined.Sort, "ترتيب المكتبة", tint = MangaroDesignSystem.GoldPrimary, modifier = Modifier.size(22.dp))
                }
                MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(surface = MangaroDesignSystem.SurfaceDark, onSurface = Color.White)) {
                    DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                        sortChoices.forEach { (type, label) ->
                            DropdownMenuItem(
                                text = { Text(label, color = if (sort.type == type) MangaroDesignSystem.GoldPrimary else Color(0xFFCBBED5)) },
                                onClick = { onSort(type); sortExpanded = false },
                            )
                        }
                    }
                }
            }
        }
        state.searchQuery?.let { query ->
            Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 8.dp), shape = RoundedCornerShape(14.dp), color = MangaroDesignSystem.SurfaceDark, border = BorderStroke(1.dp, Color(0x28A78BFA))) {
                BasicTextField(
                    value = query,
                    onValueChange = onSearch,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White, textDirection = TextDirection.Content),
                    cursorBrush = SolidColor(MangaroDesignSystem.GoldPrimary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { /* Library search is already live and local. */ }),
                    modifier = Modifier.fillMaxWidth().height(50.dp).focusRequester(focusRequester),
                    decorationBox = { inner ->
                        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Outlined.Search, null, tint = Color(0xFF9E95AC), modifier = Modifier.size(19.dp))
                            Box(Modifier.weight(1f)) {
                                if (query.isEmpty()) Text("ابحث في مكتبتك...", color = Color(0xFF9E95AC))
                                inner()
                            }
                        }
                    },
                )
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            shelfChoices.forEach { (id, title) ->
                FilterChip(
                    selected = effectiveShelf == id,
                    onClick = { selectedShelf = id },
                    label = { Text(title, style = MaterialTheme.typography.labelMedium) },
                    shape = RoundedCornerShape(11.dp),
                    border = null,
                    colors = FilterChipDefaults.filterChipColors(containerColor = Color.Transparent, labelColor = Color(0xFFCBBED5), selectedContainerColor = MangaroDesignSystem.SurfaceHigh, selectedLabelColor = MangaroDesignSystem.GoldPrimary),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LibraryQuickFilter(
                label = if (unreadFilter == TriState.ENABLED_NOT) "المقروء" else "غير المقروء",
                state = unreadFilter,
                onChange = onUnreadFilter,
            )
            LibraryQuickFilter(
                label = if (downloadedFilter == TriState.ENABLED_NOT) "غير المنزّل" else "المنزّل",
                state = downloadedFilter,
                onChange = onDownloadedFilter,
                enabled = !downloadedOnly,
            )
            IconButton(onClick = onFilters) {
                Icon(Icons.Outlined.FilterList, "خيارات المكتبة", tint = if (state.hasActiveFilters) MangaroDesignSystem.GoldPrimary else Color(0xFFCBBED5), modifier = Modifier.size(20.dp))
            }
            Text("${mangas.size} عمل", style = MaterialTheme.typography.labelMedium, color = Color(0xFFA99BB9))
        }
        PullRefresh(refreshing = refreshing, enabled = !state.isLoading && !refreshing, onRefresh = onRefresh, modifier = Modifier.weight(1f)) {
            when {
                state.isLoading -> Box(Modifier.fillMaxWidth().padding(top = 28.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = MangaroDesignSystem.GoldPrimary, strokeWidth = 2.dp)
                }
                mangas.isEmpty() -> Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (state.libraryData.totalLibraryCount == 0) "مكتبتك فارغة" else selectedTitle, color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            state.libraryData.totalLibraryCount == 0 -> "اكتشف عملًا جديدًا وأضفه إلى مكتبتك"
                            !state.searchQuery.isNullOrBlank() -> "لا توجد نتائج في مكتبتك"
                            state.hasActiveFilters -> "لا توجد أعمال تطابق الفلاتر"
                            else -> "لا توجد أعمال في هذا الرف بعد"
                        },
                        color = Color(0xFFA99BB9), style = MaterialTheme.typography.bodyMedium,
                    )
                    if (state.libraryData.totalLibraryCount == 0) {
                        TextButton(onClick = onDiscover) { Text("اكتشف أعمالًا جديدة", color = MangaroDesignSystem.GoldPrimary) }
                    } else if (state.hasActiveFilters) {
                        TextButton(onClick = onFilters) { Text("تعديل الفلاتر", color = MangaroDesignSystem.GoldPrimary) }
                    }
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(108.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (state.searchQuery.isNullOrBlank()) {
                        val history = continueHistory
                        if (history != null) {
                            item(key = "continue-reading", span = { GridItemSpan(maxLineSpan) }, contentType = "continue") {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("متابعة القراءة", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    MangaroContinueReading(history, onResumeClick = { onContinue(history) }, onMangaClick = { onMangaClick(history.mangaId) })
                                    Text(selectedTitle, color = Color(0xFFCBBED5), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        }
                    }
                    items(mangas, key = { it.id }, contentType = { "manga" }) { item ->
                        LibraryArtworkItem(item, onClick = { onMangaClick(item.id) }, onLongClick = { onManageManga(item.libraryManga.manga) })
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryQuickFilter(label: String, state: TriState, onChange: (TriState) -> Unit, enabled: Boolean = true) {
    FilterChip(
        selected = state != TriState.DISABLED,
        enabled = enabled,
        onClick = { onChange(if (state == TriState.DISABLED) TriState.ENABLED_IS else TriState.DISABLED) },
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, Color(0x28A78BFA)),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent, labelColor = Color(0xFFCBBED5),
            selectedContainerColor = MangaroDesignSystem.SurfaceHigh, selectedLabelColor = MangaroDesignSystem.GoldPrimary,
        ),
    )
}

@Composable
private fun LibraryArtworkItem(item: LibraryItem, onClick: () -> Unit, onLongClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.975f else 1f, tween(140), label = "libraryPress")
    val manga = item.libraryManga.manga
    Column(
        Modifier.graphicsLayer { scaleX = scale; scaleY = scale }.combinedClickable(interactionSource = interaction, indication = null, onClick = onClick, onLongClick = onLongClick),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box {
            MangaCover.Book(data = manga.asMangaCover(), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), contentDescription = manga.title)
            if (item.unreadCount > 0) {
                Surface(modifier = Modifier.align(Alignment.TopEnd).padding(6.dp), color = MangaroDesignSystem.SurfaceDark.copy(alpha = 0.94f), shape = RoundedCornerShape(7.dp)) {
                    Text("${item.unreadCount}", Modifier.padding(horizontal = 6.dp, vertical = 3.dp), color = MangaroDesignSystem.GoldPrimary, style = MaterialTheme.typography.labelSmall)
                }
            }
            if (item.downloadCount > 0) {
                Surface(modifier = Modifier.align(Alignment.BottomStart).padding(6.dp), color = MangaroDesignSystem.SurfaceDark.copy(alpha = 0.94f), shape = RoundedCornerShape(7.dp)) {
                    Icon(Icons.Outlined.DownloadDone, "فصول متاحة دون اتصال", Modifier.padding(4.dp).size(15.dp), tint = Color(0xFFD7CEE4))
                }
            }
        }
        Text(manga.title, color = Color.White, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content), fontWeight = FontWeight.Medium, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (item.libraryManga.totalChapters > 0) {
            LinearProgressIndicator(
                progress = { item.chapterProgress }, modifier = Modifier.fillMaxWidth().height(3.dp),
                color = MangaroDesignSystem.GoldPrimary.copy(alpha = 0.8f), trackColor = MangaroDesignSystem.SurfaceHigh,
            )
            Text(
                if (item.unreadCount > 0) "${item.unreadCount} فصل غير مقروء" else "كل الفصول مقروءة",
                color = Color(0xFFB3A7C2), style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
