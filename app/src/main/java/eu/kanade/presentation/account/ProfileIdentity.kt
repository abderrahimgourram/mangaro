package eu.kanade.presentation.account

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.theme.MangaroDesignSystem
import mihon.domain.account.AccountRole
import mihon.domain.account.MangaroRanks
import mihon.domain.account.ProfileIdentity

internal fun rankAccent(level: Int): Color = when (ProfileIdentity.tier(level)) {
    0 -> Color(0xFFABA2B5)
    1 -> Color(0xFFC0ACD4)
    2 -> Color(0xFFC8A7E0)
    3 -> Color(0xFFD4BA9A)
    4 -> Color(0xFFD8BF84)
    5 -> Color(0xFFE4C789)
    else -> MangaroDesignSystem.GoldPrimary
}

@Composable
internal fun DeveloperBadge(role: AccountRole, compact: Boolean = false) {
    if (role != AccountRole.DEVELOPER) return
    Text(if (compact) "مطور" else "مطور Mangaro", color = MangaroDesignSystem.GoldPrimary,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.background(MangaroDesignSystem.GoldPrimary.copy(alpha = 0.09f), RoundedCornerShape(6.dp))
            .border(0.5.dp, MangaroDesignSystem.GoldPrimary.copy(alpha = 0.24f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp))
}

@Composable
internal fun RankIdentity(level: Int) {
    val accent = rankAccent(level)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(MangaroRanks.titleFor(level), color = accent, style = MaterialTheme.typography.labelSmall)
        Text("Lv.$level", color = accent, style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Ltr),
            modifier = Modifier.background(accent.copy(alpha = if (level >= 5) 0.12f else 0.04f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 3.dp))
    }
}
