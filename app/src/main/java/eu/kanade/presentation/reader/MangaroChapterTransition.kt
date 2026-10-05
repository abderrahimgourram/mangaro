package eu.kanade.presentation.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.viewer.calculateChapterGap
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.pluralStringResource

private val BoundaryGold = Color(0xFFD6B56D)
private val BoundaryText = Color(0xFFF0ECF5)
private val BoundaryMuted = Color(0xFFB6AFC3)

/** Presentation only: the existing Reader owns chapter loading, gestures and completion policy. */
@Composable
fun MangaroChapterTransition(
    transition: ChapterTransition,
    currChapterDownloaded: Boolean,
    goingToChapterDownloaded: Boolean,
    nativeContent: @Composable () -> Unit,
) {
    val previous = transition is ChapterTransition.Prev
    val completed = if (previous) transition.to else transition.from
    val following = if (previous) transition.from else transition.to
    val gap = if (previous) calculateChapterGap(transition.from, transition.to) else calculateChapterGap(transition.to, transition.from)

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Surface(
            modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF121016),
        ) {
            Column(
                modifier = Modifier
                    .background(Brush.verticalGradient(listOf(Color(0xFF231B30), Color(0xFF121016))))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.MenuBook, null, Modifier.size(22.dp), tint = BoundaryGold)
                    Text(
                        if (completed == null) "بداية القراءة" else "نهاية الفصل",
                        style = MaterialTheme.typography.titleMedium,
                        color = BoundaryGold,
                    )
                }
                if (completed != null) {
                    BoundaryChapter(
                        if (previous) "الفصل السابق" else "الفصل المنتهي",
                        completed.chapter.name,
                        if (previous) goingToChapterDownloaded else currChapterDownloaded,
                    )
                }
                HorizontalDivider(color = BoundaryGold.copy(alpha = 0.18f))

                // Emits no layout node until a genuine SDK NativeAd is available. No placeholder,
                // loading indicator, fixed height or navigation dependency belongs in this slot.
                nativeContent()

                if (gap > 0) {
                    Text(
                        pluralStringResource(MR.plurals.missing_chapters_warning, gap, gap),
                        style = MaterialTheme.typography.bodySmall,
                        color = BoundaryMuted,
                    )
                }
                if (following != null) {
                    BoundaryChapter(
                        if (previous) "الفصل الحالي" else "الفصل التالي",
                        following.chapter.name,
                        if (previous) currChapterDownloaded else goingToChapterDownloaded,
                    )
                } else {
                    Text("وصلت إلى آخر فصل متاح", style = MaterialTheme.typography.bodyMedium, color = BoundaryText)
                }
            }
        }
    }
}

@Composable
private fun BoundaryChapter(label: String, title: String, downloaded: Boolean) {
    Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFF201B28)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = BoundaryMuted)
                if (downloaded) Icon(Icons.Outlined.CheckCircle, "متاح دون اتصال", Modifier.size(14.dp), tint = BoundaryGold)
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                color = BoundaryText,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
