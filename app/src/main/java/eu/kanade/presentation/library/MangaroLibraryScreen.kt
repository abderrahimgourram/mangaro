package eu.kanade.presentation.library

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    val sortChoices = remember {
        listOf(LibrarySort.Type.Alphabetical to "العنوان", LibrarySort.Type.LastRead to "آخر قراءة",
            LibrarySort.Type.LastUpdate to "آخر تحديث", LibrarySort.Type.UnreadCount to "غير المقروء")
    }
    LaunchedEffect(state.searchQuery != null) {
        if (state.searchQuery != null) focusRequester.requestFocus()
    }

    Column(modifier.fillMaxSize().background(MangaroDesignSystem.BackgroundDark).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("المكتبة", modifier = Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
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
        PullRefresh(refreshing = false, enabled = true, onRefresh = onRefresh, modifier = Modifier.weight(1f)) {
            when {
                state.isLoading -> Box(Modifier.fillMaxWidth().padding(top = 28.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = MangaroDesignSystem.GoldPrimary, strokeWidth = 2.dp)
                }
                mangas.isEmpty() -> Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(selectedTitle, color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text(if (!state.searchQuery.isNullOrBlank()) "لا توجد نتائج في مكتبتك" else "لا توجد أعمال هنا بعد", color = Color(0xFFA99BB9), style = MaterialTheme.typography.bodyMedium)
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(120.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(mangas, key = { it.id }) { item ->
                        LibraryArtworkItem(item, onClick = { onMangaClick(item.id) }, onLongClick = { onManageManga(item.libraryManga.manga) })
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryArtworkItem(item: LibraryItem, onClick: () -> Unit, onLongClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val manga = item.libraryManga.manga
    Column(
        Modifier.alpha(if (pressed) 0.9f else 1f).combinedClickable(interactionSource = interaction, indication = null, onClick = onClick, onLongClick = onLongClick),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box {
            MangaCover.Book(data = manga.asMangaCover(), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), contentDescription = manga.title)
            if (item.unreadCount > 0) {
                Surface(modifier = Modifier.align(Alignment.TopEnd).padding(6.dp), color = MangaroDesignSystem.SurfaceDark.copy(alpha = 0.94f), shape = RoundedCornerShape(7.dp)) {
                    Text("${item.unreadCount}", Modifier.padding(horizontal = 6.dp, vertical = 3.dp), color = MangaroDesignSystem.GoldPrimary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Text(manga.title, color = Color.White, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content), fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
