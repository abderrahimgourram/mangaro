package eu.kanade.presentation.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.theme.MangaroDesignSystem
import eu.kanade.tachiyomi.ui.home.HomeDiscoveryItem

@Composable
fun MangaroMangaCard(
    item: HomeDiscoveryItem,
    onMangaClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    cardWidth: Dp = 108.dp,
) {
    val accessibilityLabel = item.title
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1.0f,
        animationSpec = tween(durationMillis = 120),
        label = "cardPressScale",
    )

    val cardShape = RoundedCornerShape(14.dp)
    val borderColor = if (isPressed) {
        MangaroDesignSystem.GoldPrimary.copy(alpha = 0.6f)
    } else {
        Color(0x30A78BFA)
    }

    Column(
        modifier = modifier
            .width(cardWidth)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(cardShape)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1B1325),
                        Color(0xFF130D1A),
                    ),
                ),
            )
            .border(
                border = BorderStroke(1.dp, borderColor),
                shape = cardShape,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { onMangaClick(item.mangaId) },
            )
            .semantics { contentDescription = accessibilityLabel },
    ) {
        // Cover Poster Container (Dominant 2:3 aspect ratio)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .background(MangaroDesignSystem.SurfaceDark),
        ) {
            MangaCover.Book(
                data = item.coverData,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
            )

            // Bottom gradient overlay on artwork for seamless transition to title surface
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color(0x881B1325),
                                Color(0xFF1B1325),
                            ),
                        ),
                    ),
            )

            // Multi-source Badge
            if (item.availableVersions.size > 1) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xEB0E0A14))
                        .border(
                            BorderStroke(0.5.dp, MangaroDesignSystem.GoldPrimary.copy(alpha = 0.6f)),
                            RoundedCornerShape(6.dp),
                        )
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "${item.availableVersions.size} مصادر",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = MangaroDesignSystem.GoldPrimary,
                    )
                }
            }
        }

        // Title Container - integrated into the same card shell
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .padding(horizontal = 7.dp, vertical = 4.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp,
                    textDirection = TextDirection.Content,
                ),
                color = Color(0xFFF3EFF7),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
