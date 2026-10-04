package eu.kanade.presentation.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.theme.MangaroDesignSystem
import eu.kanade.tachiyomi.ui.home.WeeklyPicksState
import java.util.Locale

@Composable
internal fun MangaroWeeklyPicks(state: WeeklyPicksState, onOpen: (Long) -> Unit, onRetry: () -> Unit) {
    val scroll = rememberLazyListState()
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("اختيارات المجتمع", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                if (state.failed || (state.cards.isEmpty() && !state.loading)) {
                    TextButton(onClick = onRetry) { Text("إعادة المحاولة") }
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // 20dp leading inset + 12dp gap leaves a deliberate 24dp next-card peek.
                val cardWidth = (maxWidth - 56.dp).coerceAtMost(380.dp)
                val cardHeight = cardWidth / 1.5f
                val shape = RoundedCornerShape(18.dp)
                LazyRow(
                    state = scroll,
                    flingBehavior = rememberSnapFlingBehavior(scroll, snapPosition = SnapPosition.Start),
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(state.cards, key = { _, card -> card.key }) { index, card ->
                        Row(
                            Modifier.width(cardWidth).height(cardHeight).clip(shape)
                                .background(MangaroDesignSystem.SurfaceDark)
                                .border(1.dp, Color(0xFF30263D), shape)
                                .clickable(role = Role.Button) { onOpen(card.manga.mangaId) },
                        ) {
                            // Keep the original portrait ratio; artwork is never stretched.
                            MangaCover.Book(
                                card.manga.coverData,
                                Modifier.width(cardHeight * (2f / 3f)).fillMaxHeight(),
                                shape = RoundedCornerShape(0.dp),
                            )
                            Column(
                                Modifier.weight(1f).fillMaxHeight().padding(14.dp),
                                verticalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Surface(color = Color(0xFF352B1D), shape = RoundedCornerShape(8.dp)) {
                                    Text(
                                        "#${index + 1}", Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                        color = MangaroDesignSystem.GoldPrimary, fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Ltr),
                                    )
                                }
                                Text(
                                    card.manga.title,
                                    style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                                    color = Color(0xFFF2EDF7), fontWeight = FontWeight.Bold,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                                val rating = card.rating
                                Text(
                                    if (rating != null) {
                                        "\u2066★ ${String.format(Locale.ROOT, "%.1f", rating.average)}\u2069 · \u2066${rating.count}\u2069 تقييم"
                                    } else {
                                        "اختيار الأسبوع"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MangaroDesignSystem.GoldPrimary,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    if (state.cards.isEmpty() && state.loading) items(2) {
                        Row(Modifier.width(cardWidth).height(cardHeight).clip(shape).background(MangaroDesignSystem.SurfaceDark)) {
                            Box(Modifier.width(cardHeight * (2f / 3f)).fillMaxHeight().background(Color(0xFF241C34)))
                            Column(Modifier.weight(1f).padding(14.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                Box(Modifier.size(30.dp, 22.dp).background(Color(0xFF33283E), RoundedCornerShape(6.dp)))
                                Box(Modifier.fillMaxWidth().height(36.dp).background(Color(0xFF33283E), RoundedCornerShape(4.dp)))
                            }
                        }
                    }
                }
            }
            if (state.cards.isEmpty() && !state.loading) {
                Text("المختارات غير متاحة الآن، حاول عند توفر الاتصال", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
