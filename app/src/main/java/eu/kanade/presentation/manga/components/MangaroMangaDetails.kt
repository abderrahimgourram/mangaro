package eu.kanade.presentation.manga.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import eu.kanade.presentation.theme.MangaroDesignSystem
import eu.kanade.tachiyomi.source.model.SManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.time.Clock

@Composable
fun MangaroMangaHero(
    manga: Manga,
    sourceName: String,
    appBarPadding: Dp,
    isReading: Boolean,
    canRead: Boolean,
    onCoverClick: () -> Unit,
    onContinueReading: () -> Unit,
    onLibraryClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val imageRequest = ImageRequest.Builder(LocalContext.current)
        .data(manga)
        .crossfade(true)
        .build()
    val backdropScrim = remember {
        Brush.verticalGradient(
            listOf(
                Color(0x660A070F),
                Color(0xD90A070F),
                MangaroDesignSystem.BackgroundDark,
            ),
        )
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(MangaroDesignSystem.BackgroundDark),
    ) {
        AsyncImage(
            model = imageRequest,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(12.dp)
                .alpha(0.34f)
                .drawWithContent {
                    drawContent()
                    drawRect(backdropScrim)
                },
        )

        val narrow = maxWidth < 350.dp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = appBarPadding + 14.dp, end = 16.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(if (narrow) 12.dp else 16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                MangaCover.Book(
                    data = imageRequest,
                    contentDescription = manga.title,
                    onClick = onCoverClick,
                    modifier = Modifier
                        .width(if (narrow) 104.dp else 120.dp)
                        .clip(RoundedCornerShape(16.dp)),
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(
                        text = manga.title,
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = if (narrow) 20.sp else 22.sp,
                            lineHeight = if (narrow) 26.sp else 28.sp,
                            textDirection = TextDirection.Content,
                        ),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    manga.author?.takeIf { it.isNotBlank() }?.let {
                        MetadataLine(it)
                    }
                    manga.artist?.takeIf { it.isNotBlank() && it != manga.author }?.let {
                        MetadataLine(it)
                    }
                    statusLabel(manga.status)?.let {
                        MetadataLine(it, MangaroDesignSystem.GoldPrimary.copy(alpha = 0.88f))
                    }
                    Text(
                        text = sourceName,
                        color = Color(0xFFB5A9C2),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = onContinueReading,
                    enabled = canRead,
                    modifier = Modifier
                        .weight(1.15f)
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MangaroDesignSystem.GoldPrimary,
                        contentColor = Color(0xFF150F1B),
                    ),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isReading) "متابعة" else "ابدأ", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onLibraryClick,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (manga.favorite) MangaroDesignSystem.LavenderPrimary.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.16f),
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0x99171020),
                        contentColor = if (manga.favorite) MangaroDesignSystem.LavenderPrimary else Color(0xFFE8E0EE),
                    ),
                ) {
                    AnimatedContent(
                        targetState = manga.favorite,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "libraryState",
                    ) { favorite ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (favorite) "إزالة من المكتبة" else "إضافة للمكتبة", maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetadataLine(text: String, color: Color = Color(0xFFD0C5D9)) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.bodySmall.copy(
            lineHeight = 17.sp,
            textDirection = TextDirection.Content,
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun statusLabel(status: Long): String? = when (status) {
    SManga.ONGOING.toLong() -> stringResource(MR.strings.ongoing)
    SManga.COMPLETED.toLong() -> stringResource(MR.strings.completed)
    SManga.LICENSED.toLong() -> stringResource(MR.strings.licensed)
    SManga.PUBLISHING_FINISHED.toLong() -> stringResource(MR.strings.publishing_finished)
    SManga.CANCELLED.toLong() -> stringResource(MR.strings.cancelled)
    SManga.ON_HIATUS.toLong() -> stringResource(MR.strings.on_hiatus)
    else -> null
}

@Composable
fun MangaroMangaDescription(
    description: String?,
    tags: List<String>?,
    onTagClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (description.isNullOrBlank() && tags.isNullOrEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    var canExpand by remember(description) { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "القصة",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        )
        description?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                color = Color(0xFFC8BDCF),
                style = MaterialTheme.typography.bodyMedium.copy(
                    lineHeight = 23.sp,
                    textDirection = TextDirection.Content,
                ),
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { result ->
                    if (!expanded && result.hasVisualOverflow) canExpand = true
                },
            )
            if (canExpand) {
                Text(
                    text = if (expanded) "عرض أقل" else "عرض المزيد",
                    color = MangaroDesignSystem.GoldPrimary,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { expanded = !expanded }
                        .padding(vertical = 4.dp),
                )
            }
        }
        if ((expanded || description.isNullOrBlank()) && !tags.isNullOrEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                tags.forEach { tag ->
                    Text(
                        text = tag,
                        color = Color(0xFFD4C7DF),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(Color(0xFF21172C))
                            .clickable { onTagClick(tag) }
                            .padding(horizontal = 9.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }
}

fun mangaUpdateFreshness(newestChapterTimestamp: Long, nowTimestamp: Long = Clock.System.now().toEpochMilliseconds()): String? {
    if (newestChapterTimestamp <= 0L) return null
    val days = ((nowTimestamp - newestChapterTimestamp).coerceAtLeast(0L) / 86_400_000L).toInt()
    return when (days) {
        0 -> "تم التحديث اليوم"
        1 -> "آخر تحديث منذ يوم"
        in 2..6 -> "آخر تحديث منذ $days أيام"
        in 7..13 -> "آخر تحديث منذ أسبوع"
        in 14..20 -> "آخر تحديث منذ أسبوعين"
        in 21..29 -> "آخر تحديث منذ ${days / 7} أسابيع"
        in 30..59 -> "آخر تحديث منذ شهر"
        else -> "آخر تحديث منذ ${days / 30} أشهر"
    }
}
