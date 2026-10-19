package eu.kanade.presentation.account

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.kanade.presentation.theme.MangaroDesignSystem
import eu.kanade.tachiyomi.data.account.ProfileReadingSnapshot
import eu.kanade.tachiyomi.data.account.ProfileReadingStatisticsRepository
import tachiyomi.core.common.util.lang.LocalAccountScope
import tachiyomi.data.Database
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.NumberFormat
import java.util.Locale

internal fun formatProfileCount(value: Long?, locale: Locale): String =
    value?.let { NumberFormat.getIntegerInstance(locale).format(it) } ?: "—"

@Composable
internal fun rememberProfileReadingStatistics(owner: String): ProfileReadingSnapshot {
    val repository = remember { ProfileReadingStatisticsRepository(Injekt.get<Database>()) }
    val workspace by LocalAccountScope.changes.collectAsStateWithLifecycle()
    val snapshot by remember(repository, owner) { repository.observe(owner) }
        .collectAsStateWithLifecycle(initialValue = ProfileReadingSnapshot())
    return snapshot.visibleFor(owner, workspace)
}

/** No counting animation, heavy aggregation, or network calls during rendering. Owner-only. */
@Composable
internal fun ProfileReadingStatistics(snapshot: ProfileReadingSnapshot) {
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val locale = configuration.locales[0]
    val total = remember(snapshot.totals.total, locale) { formatProfileCount(snapshot.totals.total, locale) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MangaroDesignSystem.SurfaceDark,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(.5.dp, MangaroDesignSystem.GoldPrimary.copy(alpha = .16f)),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("رحلتك في القراءة", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(total, modifier = Modifier.fillMaxWidth(), color = MangaroDesignSystem.GoldPrimary,
                    style = MaterialTheme.typography.displaySmall.copy(textDirection = TextDirection.Ltr),
                    autoSize = TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = 48.sp),
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 1)
                Text("فصلًا معلّمًا كمقروء", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val largeText = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f ||
                listOfNotNull(snapshot.totals.comics, snapshot.totals.novels).any { it >= 1_000_000_000L }
            if (largeText) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ReadingCategory("المانجا والمانهوا", snapshot.totals.comics, locale, Icons.Outlined.CollectionsBookmark, Modifier.fillMaxWidth())
                    ReadingCategory("الروايات", snapshot.totals.novels, locale, Icons.AutoMirrored.Outlined.MenuBook, Modifier.fillMaxWidth())
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ReadingCategory("المانجا والمانهوا", snapshot.totals.comics, locale, Icons.Outlined.CollectionsBookmark, Modifier.weight(1f))
                    ReadingCategory("الروايات", snapshot.totals.novels, locale, Icons.AutoMirrored.Outlined.MenuBook, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ReadingCategory(label: String, value: Long?, locale: Locale, icon: ImageVector, modifier: Modifier) {
    val formatted = remember(value, locale) { formatProfileCount(value, locale) }
    Surface(modifier, color = MangaroDesignSystem.SurfaceHigh.copy(alpha = .45f), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatted, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.Ltr),
                autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 24.sp),
                fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
