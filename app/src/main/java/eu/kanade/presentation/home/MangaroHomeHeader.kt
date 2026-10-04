package eu.kanade.presentation.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Badge
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.theme.MangaroDesignSystem

@Composable
fun MangaroHomeHeader(
    onSearchClick: () -> Unit,
    onMenuClick: () -> Unit,
    onNotificationsClick: () -> Unit = {},
    hasUnreadNotifications: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth()
            .background(MangaroDesignSystem.BackgroundDark)
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onMenuClick) {
            Icon(Icons.Outlined.Menu, "فتح القائمة", Modifier.size(21.dp), tint = Color(0xFFD5CADE))
        }
        Text(
            text = "الرئيسية",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
            maxLines = 1,
        )
        IconButton(onClick = onNotificationsClick) {
            BadgedBox(badge = { if (hasUnreadNotifications) Badge(containerColor = MangaroDesignSystem.GoldPrimary) }) {
                Icon(Icons.Outlined.NotificationsNone, if (hasUnreadNotifications) "إشعارات غير مقروءة" else "الإشعارات", Modifier.size(21.dp), tint = Color(0xFFD5CADE))
            }
        }
        IconButton(onClick = onSearchClick) {
            Icon(Icons.Outlined.Search, "بحث", Modifier.size(21.dp), tint = Color(0xFFD5CADE))
        }
    }
}
