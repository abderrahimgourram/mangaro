package eu.kanade.presentation.account

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.theme.MangaroDesignSystem
import mihon.domain.account.MangaroLevelProgress
import mihon.domain.account.MangaroProfile
import mihon.domain.account.MangaroRanks
import mihon.domain.account.ProfileIdentity
import tachiyomi.core.common.util.lang.WesternDigits

@Composable
internal fun ProfileProgressCard(profile: MangaroProfile) {
    val level = ProfileIdentity.displayedLevel(profile.level, profile.role)
    val accent = rankAccent(level)
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = MangaroDesignSystem.SurfaceDark,
        border = BorderStroke(.5.dp, accent.copy(alpha = .14f))) {
        Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            RankArtwork(level, Modifier.size(64.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(MangaroRanks.titleFor(level), style = MaterialTheme.typography.titleSmall,
                    color = accent, fontWeight = FontWeight.SemiBold)
                if (level == MangaroRanks.MAX_LEVEL) {
                    Text("المستوى الأقصى · ${WesternDigits.isolate(level.toString())}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                } else {
                    val earned = MangaroLevelProgress.earned(profile)
                    val required = MangaroLevelProgress.required(profile.level)
                    LinearProgressIndicator(progress = { (earned.toFloat() / required).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                        color = accent, trackColor = MangaroDesignSystem.SurfaceHigh)
                    Text("$earned / $required XP", color = accent,
                        style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Ltr))
                }
                Text("إجمالي الخبرة: ${WesternDigits.isolate(profile.xp.toString())} XP",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
