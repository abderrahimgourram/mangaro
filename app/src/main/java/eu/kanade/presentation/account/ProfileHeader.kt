package eu.kanade.presentation.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.theme.MangaroDesignSystem
import mihon.domain.account.AccountRole
import tachiyomi.core.common.util.lang.WesternDigits

/** Shared presentation only: ownership, image loading, and actions remain with each profile. */
@Composable
internal fun ProfileHeader(
    displayName: String?,
    username: String?,
    bio: String?,
    level: Int,
    role: AccountRole,
    cover: @Composable () -> Unit,
    avatar: @Composable () -> Unit,
    action: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MangaroDesignSystem.SurfaceDark,
        shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF35283F)),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(184.dp)) {
                Box(Modifier.fillMaxWidth().height(144.dp)) {
                    cover()
                    RankCoverAccent(level, Modifier.fillMaxSize())
                }
                Box(Modifier.align(Alignment.BottomStart).padding(start = 16.dp)
                    .shadow(8.dp, RoundedCornerShape(28.dp))
                    .background(MangaroDesignSystem.SurfaceDark, RoundedCornerShape(28.dp)).padding(4.dp)) {
                    TierAvatarFrame(level, Modifier.size(88.dp)) { avatar() }
                }
                Box(Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 2.dp)) { action() }
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    ProfileDisplayName(displayName, username, color = Color(0xFFF5EFF9),
                        style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    username?.let { UsernameHandle(it, color = Color(0xFFB7A9C4), style = MaterialTheme.typography.bodySmall) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                    itemVerticalAlignment = Alignment.CenterVertically) {
                    RankIdentity(level, prominent = true)
                    DeveloperBadge(role)
                }
                bio?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = Color(0xFFD0C3DA), style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content))
                }
                content()
            }
        }
    }
}

/** The public profile supplies only its existing privacy-filtered statistics. */
@Composable
internal fun ProfileStatsStrip(items: List<Pair<String, Long?>>) {
    Surface(Modifier.fillMaxWidth(), color = MangaroDesignSystem.SurfaceDark, shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF302538))) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            items.forEachIndexed { index, (label, value) ->
                if (index > 0) Box(Modifier.width(1.dp).height(28.dp).background(Color(0xFF382B43)))
                Column(Modifier.weight(1f).padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(value?.let { WesternDigits.isolate(it.toString()) } ?: "—", color = Color(0xFFF5EFF9),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(label, color = Color(0xFFAFA0BD), style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center)
                }
            }
        }
    }
}
