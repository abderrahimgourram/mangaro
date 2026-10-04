package eu.kanade.presentation.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clip
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.TextButton
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.BookmarkBorder
import mihon.domain.source.discovery.model.DiscoveryCategory
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.theme.MangaroDesignSystem
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchItemResult
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchViewModel
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover

/** Isolated primary-search presentation; search and source health remain in SearchViewModel. */
@Composable
fun MangaroSearchScreen(
    state: SearchViewModel.State,
    focusRequest: Int,
    onChangeQuery: (String?) -> Unit,
    onSearch: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onRecent: (String) -> Unit,
    onRemoveRecent: (String) -> Unit,
    onClearRecents: () -> Unit,
    onDiscover: (DiscoveryCategory) -> Unit,
    getManga: @Composable (Manga) -> State<Manga>,
    onClickManga: (Manga) -> Unit,
    onNavigateUp: (() -> Unit)? = null,
) {
    val query = state.searchQuery.orEmpty()
    val resultsListState = rememberLazyGridState()
    val acceptedQuery = state.activeQuery.orEmpty().trim()
    val displayedQuery = state.resultQuery.orEmpty().trim()
    var lastAcceptedQuery by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingScrollReset by rememberSaveable { mutableStateOf(false) }

    // Arm once when the debounced/cached query is accepted, then reset when its
    // rows arrive. Previous-query rows can remain visible during the request.
    // Source completions and ranking changes do not change either query key.
    LaunchedEffect(acceptedQuery, displayedQuery) {
        if (lastAcceptedQuery != acceptedQuery) {
            lastAcceptedQuery = acceptedQuery
            pendingScrollReset = acceptedQuery.isNotEmpty()
        }
        if (pendingScrollReset && displayedQuery == acceptedQuery) {
            resultsListState.scrollToItem(index = 0, scrollOffset = 0)
            pendingScrollReset = false
        }
    }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    // Keep real source-scoped variants; equal titles are not the same manga.
    val results = state.rankedResults
    val hasSearch = query.isNotBlank()
    val loading = hasSearch && state.isSearching
    val currentResults = state.resultQuery == query.trim()
    val failed = hasSearch && state.activeQuery == query.trim() && state.items.values.any { it is SearchItemResult.Error }
    val fieldBorder by animateColorAsState(
        if (focused) MangaroDesignSystem.GoldPrimary.copy(alpha = 0.32f) else Color(0x30A78BFA),
        animationSpec = tween(150),
        label = "searchFieldFocus",
    )
    val submit = {
        onSearch()
        keyboard?.hide()
        focusManager.clearFocus()
    }

    var handledFocusRequest by rememberSaveable { mutableStateOf(focusRequest) }
    LaunchedEffect(focusRequest) {
        if (query.isBlank() || handledFocusRequest != focusRequest) focusRequester.requestFocus()
        handledFocusRequest = focusRequest
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(
            Modifier.fillMaxSize()
                .background(MangaroDesignSystem.BackgroundDark)
                .statusBarsPadding()
                .imePadding(),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                onNavigateUp?.let { navigate -> IconButton(onClick = navigate) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "رجوع", tint = Color(0xFFCBBED5)) } }
                Text("البحث", Modifier.padding(horizontal = 8.dp, vertical = 10.dp), color = Color.White,
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                color = MangaroDesignSystem.SurfaceDark,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, fieldBorder),
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = onChangeQuery,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = Color.White,
                        textDirection = TextDirection.Content,
                        textAlign = TextAlign.Start,
                    ),
                    cursorBrush = SolidColor(MangaroDesignSystem.GoldPrimary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }),
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                        .focusRequester(focusRequester)
                        .onFocusChanged { focused = it.isFocused },
                    decorationBox = { innerTextField ->
                        Row(
                            Modifier.fillMaxSize().padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { submit() }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Outlined.Search, "بحث", tint = MangaroDesignSystem.GoldPrimary, modifier = Modifier.size(21.dp))
                            }
                            Box(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                                if (query.isEmpty()) {
                                    Text("ابحث عن مانجا...", color = Color(0xFF9E95AC), style = MaterialTheme.typography.bodyLarge)
                                }
                                innerTextField()
                            }
                            if (query.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        onChangeQuery("")
                                        focusRequester.requestFocus()
                                    },
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Icon(Icons.Outlined.Close, "مسح البحث", tint = Color(0xFFCBBED5), modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    },
                )
            }
            if (hasSearch && currentResults && results.isNotEmpty()) {
                Text(
                    "${results.size} نتيجة",
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    color = Color(0xFF9E95AC),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Box(Modifier.fillMaxWidth().height(2.dp)) {
                if (loading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxSize(),
                        color = MangaroDesignSystem.GoldPrimary,
                        trackColor = MangaroDesignSystem.SurfaceHigh,
                    )
                }
            }
            when {
                !hasSearch -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("اكتشف قصتك القادمة", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = { keyboard?.hide(); focusManager.clearFocus(); onDiscover(DiscoveryCategory.POPULAR) }, label = { Text("الأعمال الشائعة") })
                        AssistChip(onClick = { keyboard?.hide(); focusManager.clearFocus(); onDiscover(DiscoveryCategory.LATEST) }, label = { Text("آخر التحديثات") })
                    }
                    if (state.recentQueries.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("عمليات البحث الأخيرة", Modifier.weight(1f), color = Color(0xFFCBBED5), style = MaterialTheme.typography.titleSmall)
                            TextButton(onClick = onClearRecents) { Text("مسح الكل", color = Color(0xFF9E95AC)) }
                        }
                        state.recentQueries.forEach { recent ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(MangaroDesignSystem.SurfaceDark), verticalAlignment = Alignment.CenterVertically) {
                                Row(Modifier.weight(1f).clickable { onRecent(recent); keyboard?.hide(); focusManager.clearFocus() }
                                    .heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Icon(Icons.Outlined.History, null, tint = Color(0xFF9E95AC), modifier = Modifier.size(18.dp))
                                    Text(recent, color = Color(0xFFE8DFF0), style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                IconButton(onClick = { onRemoveRecent(recent) }) { Icon(Icons.Outlined.Close, "إزالة البحث", tint = Color(0xFF9E95AC), modifier = Modifier.size(16.dp)) }
                            }
                        }
                    } else Text("ابحث بالعربية أو الإنجليزية عن العمل الذي تحبّه", color = Color(0xFF9E95AC), style = MaterialTheme.typography.bodyMedium)
                }
                loading && results.isEmpty() -> LazyVerticalGrid(columns = GridCells.Adaptive(108.dp), modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(6) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(MangaroDesignSystem.SurfaceDark))
                        Box(Modifier.fillMaxWidth(0.8f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(MangaroDesignSystem.SurfaceHigh))
                    } }
                }
                results.isEmpty() -> Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Search, null, tint = Color(0xFF80738F), modifier = Modifier.size(28.dp))
                    Text(if (failed) "تعذّر البحث، تحقق من الاتصال" else "لم نجد نتائج مطابقة", color = Color(0xFFCBBED5), style = MaterialTheme.typography.titleMedium)
                    if (failed) TextButton(onClick = onRetry) { Text("إعادة المحاولة") }
                    else Text("جرّب عنوانًا أقصر أو تحقق من الكتابة", color = Color(0xFF9E95AC), style = MaterialTheme.typography.bodySmall)
                }
                else -> LazyVerticalGrid(columns = GridCells.Adaptive(108.dp), state = resultsListState,
                    modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (failed) item(key = "searchNotice", span = { GridItemSpan(maxLineSpan) }) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (currentResults) "بعض النتائج غير متاحة حاليًا" else "تعذّر تحديث النتائج، نحتفظ بالبحث السابق", Modifier.weight(1f), color = Color(0xFF9E95AC), style = MaterialTheme.typography.bodySmall)
                            TextButton(enabled = !loading, onClick = onRetry) { Text("إعادة المحاولة") }
                        }
                    }
                    items(results, key = { "${it.manga.source}:${it.manga.url}" }) { initial ->
                        val manga by getManga(initial.manga)
                        SearchMangaCard(manga, onClick = { keyboard?.hide(); focusManager.clearFocus(); onClickManga(manga) })
                    }
                    item(key = "searchPaging", span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                            when {
                                state.isLoadingMore -> CircularProgressIndicator(Modifier.size(22.dp), color = MangaroDesignSystem.GoldPrimary, strokeWidth = 2.dp)
                                state.hasMore && currentResults && !loading -> TextButton(onClick = onLoadMore) {
                                    Text(if (state.paginationFailed) "تعذّر تحميل المزيد — حاول مجددًا" else "عرض المزيد")
                                }
                                currentResults && !loading -> Text(if (failed) "هذه النتائج المتاحة حاليًا" else "وصلت إلى نهاية النتائج", color = Color(0xFF9E95AC), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

        }
    }
}

@Composable
private fun SearchMangaCard(manga: Manga, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(140), label = "searchCardPress")
    Column(Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(RoundedCornerShape(12.dp)).clickable(interactionSource = interaction, indication = null, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box {
            MangaCover.Book(data = manga.asMangaCover(), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), contentDescription = manga.title)
            if (manga.favorite) Surface(Modifier.align(Alignment.TopEnd).padding(6.dp), color = MangaroDesignSystem.BackgroundDark.copy(alpha = 0.88f), shape = RoundedCornerShape(8.dp)) {
                Icon(Icons.Outlined.BookmarkBorder, "في المكتبة", Modifier.padding(5.dp).size(16.dp), tint = MangaroDesignSystem.GoldPrimary)
            }
        }
        Text(manga.title, color = Color.White, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
            fontWeight = FontWeight.SemiBold, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        manga.author?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = Color(0xFF9E95AC), style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Content), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
