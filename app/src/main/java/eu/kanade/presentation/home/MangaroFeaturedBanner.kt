package eu.kanade.presentation.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.theme.MangaroDesignSystem
import eu.kanade.tachiyomi.ui.home.HomeDiscoveryItem

@Composable
fun MangaroFeaturedBanner(
    item: HomeDiscoveryItem,
    canRotate: Boolean,
    onOpenManga: (Long) -> Unit,
    onNextStory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = item,
        modifier = modifier,
        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
        contentKey = { "${it.sourceId}_${it.mangaId}" },
        label = "featuredStory",
    ) { featured ->
        FeaturedStoryCard(featured, canRotate, onOpenManga, onNextStory)
    }
}

@Composable
private fun FeaturedStoryCard(
    item: HomeDiscoveryItem,
    canRotate: Boolean,
    onOpenManga: (Long) -> Unit,
    onNextStory: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { onOpenManga(item.mangaId) },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MangaroDesignSystem.SurfaceDark),
        border = BorderStroke(1.dp, Color(0x22A78BFA)),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
        ) {
            // Layer 1: Heavily Darkened Background Atmospheric Cover Artwork
            AsyncImage(
                model = item.coverData,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )

            // Layer 1.5: Strong Directional Scrim Overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFF0A070F),
                                Color(0xF20A070F),
                                Color(0x880A070F),
                            ),
                        ),
                    ),
            )

            // Layer 2: Foreground Content (RTL Row: Responsive 2:3 Cover on Right, Info & CTA on Left)
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(13.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Right Side (Start in RTL): Responsive Uncropped 2:3 Cover Artwork
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .aspectRatio(2f / 3f)
                            .shadow(4.dp, RoundedCornerShape(13.dp))
                            .clip(RoundedCornerShape(13.dp))
                            .border(
                                BorderStroke(1.dp, Color(0x33A78BFA)),
                                RoundedCornerShape(13.dp),
                            ),
                    ) {
                        MangaCover.Book(
                            data = item.coverData,
                            contentDescription = item.title,
                            modifier = Modifier.fillMaxSize(),
                        )

                        if (item.availableVersions.size > 1) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(4.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xE6100B18))
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

                    // Left Side (End in RTL): Information & Primary CTA
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        // Keep the refresh action without a provider-label row.
                        if (canRotate) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(Color(0x26140E1B))
                                        .clickable { onNextStory() },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Refresh,
                                        contentDescription = "قصة أخرى",
                                        modifier = Modifier.size(15.dp),
                                        tint = Color(0xFFCBBED5),
                                    )
                                }
                            }
                        }

                        // Middle Section: Manga Title
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                lineHeight = 21.sp,
                                textDirection = TextDirection.Content,
                            ),
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )

                        // Bottom Section: Primary Action Button ("عرض التفاصيل")
                        Button(
                            onClick = { onOpenManga(item.mangaId) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MangaroDesignSystem.GoldPrimary.copy(alpha = 0.96f),
                                contentColor = Color(0xFF130D18),
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(38.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Text(
                                    text = "عرض التفاصيل",
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.5.sp,
                                    ),
                                    color = Color.Black,
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Outlined.ChevronLeft,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = Color.Black,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
