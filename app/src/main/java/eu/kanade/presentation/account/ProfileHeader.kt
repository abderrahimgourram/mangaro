package eu.kanade.presentation.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.theme.MangaroDesignSystem
import mihon.domain.account.AccountRole

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
    val visualLevel = mihon.domain.account.ProfileIdentity.displayedLevel(level, role)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MangaroDesignSystem.SurfaceDark,
        shape = RoundedCornerShape(28.dp),
        border = androidx.compose.foundation.BorderStroke(.5.dp, Color(0xFF44314F).copy(alpha = .6f)),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(180.dp)) {
                Box(Modifier.fillMaxWidth().height(136.dp)) {
                    cover()
                    RankCoverAccent(visualLevel, Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                        listOf(Color.Transparent, MangaroDesignSystem.SurfaceDark.copy(alpha = .5f)),
                    )))
                }
                Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                    Box(Modifier.shadow(10.dp, RoundedCornerShape(28.dp))
                        .background(MangaroDesignSystem.SurfaceDark, RoundedCornerShape(28.dp)).padding(4.dp)) {
                        TierAvatarFrame(visualLevel, Modifier.size(92.dp)) { avatar() }
                    }
                    Box(Modifier.weight(1f).padding(bottom = 4.dp), contentAlignment = Alignment.CenterEnd) { action() }
                }
            }
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    ProfileDisplayName(displayName, username, color = Color(0xFFF5EFF9),
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp),
                        itemVerticalAlignment = Alignment.CenterVertically) {
                        username?.let { UsernameHandle(it, color = Color(0xFFAE9CBE), style = MaterialTheme.typography.bodySmall) }
                        DeveloperBadge(role, compact = true)
                    }
                }
                Box(Modifier.fillMaxWidth().background(Color(0xFF211829).copy(alpha = .65f),
                    RoundedCornerShape(18.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
                    RankIdentity(visualLevel, prominent = true)
                }
                bio?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = Color(0xFFCABCD5), style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content))
                }
                content()
            }
        }
    }
}

/** The public profile supplies only its existing privacy-filtered statistics. */
@Composable
internal fun ProfileStatsStrip(items: List<Pair<String, Long?>>) {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    Surface(Modifier.fillMaxWidth(), color = Color(0xFF1B1523), shape = RoundedCornerShape(22.dp)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            items.forEachIndexed { index, (label, value) ->
                if (index > 0) Box(Modifier.width(1.dp).height(26.dp).background(Color(0xFFAE91CA).copy(alpha = .12f)))
                Column(Modifier.weight(1f).padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val formatted = androidx.compose.runtime.remember(value, locale) { formatProfileCount(value, locale) }
                    Text(formatted, color = Color(0xFFEBDAC0), maxLines = 1,
                        autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 22.sp),
                        style = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.Ltr),
                        fontWeight = FontWeight.SemiBold)
                    Text(label, color = Color(0xFFAFA0BD), style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center)
                }
            }
        }
    }
}
