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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.draw.alpha
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
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchRelevance
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
    onToggleResults: () -> Unit,
    getManga: @Composable (Manga) -> State<Manga>,
    onClickManga: (Manga) -> Unit,
) {
    val query = state.searchQuery.orEmpty()
    val resultsListState = rememberLazyListState()
    val acceptedQuery = remember(state.activeQuery) { SearchRelevance.normalize(state.activeQuery.orEmpty()) }
    val displayedQuery = remember(state.resultQuery) { SearchRelevance.normalize(state.resultQuery.orEmpty()) }
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
    val results = remember(state.rankedResults, state.healthRevision) {
        val winners = eu.kanade.tachiyomi.ui.home.PreferredMangaVariants.preferred(state.rankedResults.map { it.manga })
        state.rankedResults.filter {
            it.manga.id in winners && mihon.domain.source.health.SourceHealthMonitor.shared.discoverable(it.source.id)
        }.map { it.source to it.manga }
    }
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

    LaunchedEffect(focusRequest) { focusRequester.requestFocus() }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(
            Modifier.fillMaxSize()
                .background(MangaroDesignSystem.BackgroundDark)
                .statusBarsPadding()
                .imePadding(),
        ) {
            Text(
                "البحث",
                Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
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
                            IconButton(onClick = { submit() }, modifier = Modifier.size(40.dp)) {
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
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    Icon(Icons.Outlined.Close, "مسح البحث", tint = Color(0xFFCBBED5), modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    },
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                FilterChip(
                    selected = state.onlyShowHasResults,
                    onClick = onToggleResults,
                    label = { Text("مصادر لديها نتائج", style = MaterialTheme.typography.labelMedium) },
                    leadingIcon = { Icon(Icons.Outlined.FilterList, null, modifier = Modifier.size(16.dp)) },
                    shape = RoundedCornerShape(10.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color.Transparent,
                        labelColor = Color(0xFFCBBED5),
                        iconColor = Color(0xFF9E95AC),
                        selectedContainerColor = MangaroDesignSystem.SurfaceHigh,
                        selectedLabelColor = MangaroDesignSystem.GoldPrimary,
                        selectedLeadingIconColor = MangaroDesignSystem.GoldPrimary,
                    ),
                    border = null,
                )
                if (hasSearch && currentResults && results.isNotEmpty()) {
                    Text("${results.size} نتيجة", color = Color(0xFF9E95AC), style = MaterialTheme.typography.labelMedium)
                }
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
            if (!hasSearch || results.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        Icons.Outlined.Search,
                        contentDescription = null,
                        tint = Color(0xFF80738F),
                        modifier = Modifier.size(26.dp),
                    )
                    Text(
                        when {
                            !hasSearch -> "ابحث عن عنوان"
                            loading -> "جارٍ البحث عن القصص..."
                            failed -> "تعذّر إكمال البحث. حاول مجدداً لاحقاً"
                            else -> "لا توجد نتائج لهذا العنوان"
                        },
                        color = Color(0xFFCBBED5),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                    if (!hasSearch) {
                        Text(
                            "تظهر النتائج أثناء الكتابة",
                            color = Color(0xFF9E95AC),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = resultsListState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (failed && !state.onlyShowHasResults) {
                        item(key = "partialSearchNotice") {
                            Text("بعض النتائج غير متاحة حالياً", color = Color(0xFF9E95AC), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    items(results, key = { (source, manga) -> "${source.id}:${manga.id}" }) { (source, initial) ->
                        val manga by getManga(initial)
                        SearchMangaRow(manga, source.name, onClick = { onClickManga(manga) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchMangaRow(manga: Manga, sourceName: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Surface(
        modifier = Modifier.fillMaxWidth().alpha(if (pressed) 0.92f else 1f)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        color = MangaroDesignSystem.SurfaceDark,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(0x1DA78BFA)),
    ) {
        Row(
            Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MangaCover.Book(
                data = manga.asMangaCover(),
                modifier = Modifier.width(80.dp),
                shape = RoundedCornerShape(10.dp),
                contentDescription = manga.title,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    manga.title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(sourceName, color = Color(0xFFA99BB9), style = MaterialTheme.typography.labelMedium)
                manga.author?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = Color(0xFF9E95AC), style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = Color(0xFF80738F), modifier = Modifier.size(16.dp))
        }
    }
}
