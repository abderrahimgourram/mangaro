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

@Composable
fun MangaroPopularShelf(
    items: List<HomeDiscoveryItem>,
    onMangaClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    DiscoveryShelf(
        category = "popular",
        items = items,
        modifier = modifier,
    ) { item, width ->
        PopularMangaItem(item, width, onMangaClick)
    }
}

@Composable
fun MangaroNewShelf(
    items: List<HomeDiscoveryItem>,
    onMangaClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    DiscoveryShelf(
        category = "new",
        items = items,
        modifier = modifier,
    ) { item, width ->
        FreshMangaItem(item, width, showNewMark = true, onMangaClick)
    }
}

@Composable
fun MangaroLatestShelf(
    items: List<HomeDiscoveryItem>,
    onMangaClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    DiscoveryShelf(
        category = "latest",
        items = items,
        modifier = modifier,
    ) { item, width ->
        FreshMangaItem(item, width, showNewMark = false, onMangaClick)
    }
}

@Composable
fun MangaroStandardShelf(
    category: String,
    items: List<HomeDiscoveryItem>,
    onMangaClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    DiscoveryShelf(category, items, modifier) { item, width ->
        PopularMangaItem(item, width, onMangaClick)
    }
}

@Composable
private fun DiscoveryShelf(
    category: String,
    items: List<HomeDiscoveryItem>,
    modifier: Modifier,
    content: @Composable (HomeDiscoveryItem, Dp) -> Unit,
) {
    val previewItems = items.take(HOME_DISCOVERY_PREVIEW_LIMIT)
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
                key = { item -> "${category}_${item.sourceId}_${item.mangaId}" },
            ) { item -> content(item, cardWidth) }
        }
    }
}

@Composable
private fun PopularMangaItem(
    item: HomeDiscoveryItem,
    width: Dp,
    onMangaClick: (Long) -> Unit,
) {
    ArtworkFirstItem(item, width, onMangaClick) {
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                textDirection = TextDirection.Content,
            ),
            color = Color(0xFFF3EFF7),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .padding(top = 7.dp),
        )
    }
}

@Composable
private fun FreshMangaItem(
    item: HomeDiscoveryItem,
    width: Dp,
    showNewMark: Boolean,
    onMangaClick: (Long) -> Unit,
) {
    ArtworkFirstItem(
        item = item,
        width = width,
        onMangaClick = onMangaClick,
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
    ) {
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 11.75.sp,
                lineHeight = 15.5.sp,
                textDirection = TextDirection.Content,
            ),
            color = Color(0xFFE9E2EF),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .padding(top = 7.dp),
        )
    }
}

@Composable
private fun ArtworkFirstItem(
    item: HomeDiscoveryItem,
    width: Dp,
    onMangaClick: (Long) -> Unit,
    artworkOverlay: (@Composable BoxScope.() -> Unit)? = null,
    caption: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(110),
        label = "homeArtworkPress",
    )
    Column(
        modifier = Modifier
            .width(width)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource, indication = null) { onMangaClick(item.mangaId) }
            .semantics { contentDescription = item.title },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(13.dp))
                .background(MangaroDesignSystem.SurfaceDark),
        ) {
            MangaCover.Book(
                data = item.coverData,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
            )
            artworkOverlay?.invoke(this)
        }
        Box(modifier = Modifier.graphicsLayer { alpha = if (pressed) 0.88f else 1f }) { caption() }
    }
}
