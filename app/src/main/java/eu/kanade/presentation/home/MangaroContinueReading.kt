package eu.kanade.presentation.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.theme.MangaroDesignSystem
import eu.kanade.presentation.util.formatChapterDisplay
import tachiyomi.domain.history.model.HistoryWithRelations

@Composable
fun MangaroContinueReading(
    history: HistoryWithRelations,
    onResumeClick: () -> Unit,
    onMangaClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val formattedChapter = formatChapterDisplay(history.chapterNumber)
    val accessibilityLabel = "متابعة قراءة ${history.title} $formattedChapter"
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) 0.988f else 1f,
        animationSpec = androidx.compose.animation.core.tween(110),
        label = "resumeCardPress",
    )

    val (hasRealProgress, progressFraction, percentageInt) = remember(history.read, history.lastPageRead, history.totalPages) {
        when {
            history.read -> Triple(true, 1.0f, 100)
            history.totalPages > 0L -> {
                val pagesReached = history.lastPageRead + 1L
                val fraction = (pagesReached.toFloat() / history.totalPages.toFloat()).coerceIn(0f, 1f)
                val percentage = (fraction * 100).toInt()
                Triple(true, fraction, percentage)
            }
            else -> Triple(false, 0.0f, 0)
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource, indication = null) { onResumeClick() }
            .semantics { contentDescription = accessibilityLabel },
        colors = CardDefaults.cardColors(containerColor = MangaroDesignSystem.SurfaceDark),
        border = BorderStroke(1.dp, Color(0x20A78BFA)),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFF21162D), Color(0xFF15101C)),
                        ),
                    )
                    .padding(11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Right Side in RTL: Poster Cover Anchor
                Box(
                    modifier = Modifier
                        .width(72.dp)
                        .aspectRatio(2f / 3f)
                        .shadow(4.dp, RoundedCornerShape(10.dp))
                        .clip(RoundedCornerShape(10.dp))
                        .border(
                            BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                            RoundedCornerShape(10.dp),
                        )
                        .clickable { onMangaClick() },
                ) {
                    MangaCover.Book(
                        data = history.coverData,
                        contentDescription = history.title,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Left Side in RTL: Clean Vertical Information Stack
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    // Manga Title
                    Text(
                        text = history.title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            lineHeight = 20.sp,
                            textDirection = TextDirection.Content,
                        ),
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Start,
                    )

                    // Chapter Subtitle
                    Text(
                        text = formattedChapter,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.5.sp,
                        ),
                        color = Color(0xFFCBBED5),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    // Real Reading Progress Section
                    if (hasRealProgress) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "التقدم",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Normal,
                                    ),
                                    color = Color(0xFF9E95AC),
                                )
                                Text(
                                    text = "$percentageInt%",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                    ),
                                    color = MangaroDesignSystem.GoldPrimary,
                                )
                            }

                            LinearProgressIndicator(
                                progress = { progressFraction },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.5.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = MangaroDesignSystem.GoldPrimary,
                                trackColor = Color(0xFF2A2636),
                            )
                        }
                    }

                    // Compact "متابعة" CTA
                    Button(
                        onClick = onResumeClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MangaroDesignSystem.GoldPrimary,
                            contentColor = Color.Black,
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.height(30.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = Color.Black,
                            )
                            Text(
                                text = "متابعة",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.5.sp,
                                ),
                                color = Color.Black,
                            )
                        }
                    }
                }
            }
        }
    }
}
