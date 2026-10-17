package eu.kanade.presentation.home

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.theme.MangaroDesignSystem
import eu.kanade.tachiyomi.ui.home.HOME_DISCOVERY_PREVIEW_LIMIT
import eu.kanade.tachiyomi.ui.home.HomeDiscoveryItem
import eu.kanade.tachiyomi.ui.home.GroupDiscoveryItems

@Composable
fun MangaroPopularShelf(
    items: List<HomeDiscoveryItem>,
    onMangaClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onChooseEdition: (Long) -> Unit = onMangaClick,
) {
    DiscoveryShelf(
        category = "popular",
        items = items,
        modifier = modifier,
    ) { item, width ->
        PopularMangaItem(item, width, onMangaClick, onChooseEdition)
    }
}

@Composable
fun MangaroNewShelf(
    items: List<HomeDiscoveryItem>,
    onMangaClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onChooseEdition: (Long) -> Unit = onMangaClick,
) {
    DiscoveryShelf(
        category = "new",
        items = items,
        modifier = modifier,
    ) { item, width ->
        FreshMangaItem(item, width, showNewMark = true, onMangaClick, onChooseEdition)
    }
}

@Composable
fun MangaroLatestShelf(
    items: List<HomeDiscoveryItem>,
    onMangaClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onChooseEdition: (Long) -> Unit = onMangaClick,
) {
    DiscoveryShelf(
        category = "latest",
        items = items,
        modifier = modifier,
    ) { item, width ->
        FreshMangaItem(item, width, showNewMark = false, onMangaClick, onChooseEdition)
    }
}

@Composable
fun MangaroStandardShelf(
    category: String,
    items: List<HomeDiscoveryItem>,
    onMangaClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onChooseEdition: (Long) -> Unit = onMangaClick,
) {
    DiscoveryShelf(category, items, modifier) { item, width ->
        PopularMangaItem(item, width, onMangaClick, onChooseEdition)
    }
}

@Composable
private fun DiscoveryShelf(
    category: String,
    items: List<HomeDiscoveryItem>,
    modifier: Modifier,
    content: @Composable (HomeDiscoveryItem, Dp) -> Unit,
) {
    val previewItems = remember(items, category) {
        // HomeViewModel already projects verified groups off the UI thread.
        // Drawing a shelf must not repeat identity resolution for the whole section.
        items.take(HOME_DISCOVERY_PREVIEW_LIMIT)
    }
    val firstMangaId = previewItems.firstOrNull()?.mangaId ?: 0L
    val listState = rememberLazyListState()
    var hasUserScrolled by rememberSaveable(category) { mutableStateOf(false) }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) hasUserScrolled = true
    }
    LaunchedEffect(items.isEmpty()) {
        if (items.isEmpty()) hasUserScrolled = false
    }
    LaunchedEffect(firstMangaId, hasUserScrolled) {
        if (!hasUserScrolled && previewItems.isNotEmpty()) listState.scrollToItem(0, 0)
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val cardWidth = when {
            maxWidth < 340.dp -> 96.dp
            maxWidth < 430.dp -> 108.dp
            else -> 116.dp
        }
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 3.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            items(
                items = previewItems,
                key = { item -> "${category}_${GroupDiscoveryItems.presentationKey(item)}" },
            ) { item -> content(item, cardWidth) }
        }
    }
}

@Composable
private fun PopularMangaItem(
    item: HomeDiscoveryItem,
    width: Dp,
    onMangaClick: (Long) -> Unit,
    onChooseEdition: (Long) -> Unit,
) {
    ArtworkFirstItem(item, width, onMangaClick, onChooseEdition)
}

@Composable
private fun FreshMangaItem(
    item: HomeDiscoveryItem,
    width: Dp,
    showNewMark: Boolean,
    onMangaClick: (Long) -> Unit,
    onChooseEdition: (Long) -> Unit,
) {
    ArtworkFirstItem(
        item = item,
        width = width,
        onMangaClick = onMangaClick,
        onChooseEdition = onChooseEdition,
        artworkOverlay = if (showNewMark) {
            {
                Text(
                    text = "جديد",
                    color = Color(0xFF17101F),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(7.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MangaroDesignSystem.GoldPrimary.copy(alpha = 0.88f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        } else null,
    )
}

@Composable
private fun ArtworkFirstItem(
    item: HomeDiscoveryItem,
    width: Dp,
    onMangaClick: (Long) -> Unit,
    onChooseEdition: (Long) -> Unit,
    artworkOverlay: (@Composable BoxScope.() -> Unit)? = null,
) {
    eu.kanade.presentation.components.MangaroWorkCard(
        title = item.title,
        onClick = { onMangaClick(eu.kanade.tachiyomi.ui.home.CatalogEditionOpening.selected(item).mangaId) },
        onLongClick = if (item.availableVersions.size > 1) ({ onChooseEdition(item.mangaId) }) else null,
        modifier = Modifier.width(width),
        cover = { coverModifier ->
            Box(coverModifier.clip(MangaroDesignSystem.ShapeCover).background(MangaroDesignSystem.SurfaceHigh)) {
                MangaCover.Book(data = rememberHomeCoverRequest(item.coverData, width, width / MangaCover.Book.ratio), contentDescription = "", modifier = Modifier.fillMaxSize())
                artworkOverlay?.invoke(this)
            }
        },
    )
}
