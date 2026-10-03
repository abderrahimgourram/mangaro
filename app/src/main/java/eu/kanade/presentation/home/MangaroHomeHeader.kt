package eu.kanade.presentation.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.theme.MangaroDesignSystem

@Composable
fun MangaroHomeHeader(
    activeDownloadsCount: Int,
    onSearchClick: () -> Unit,
    onDownloadsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MangaroDesignSystem.BackgroundDark),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 18.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Clean Arabic Screen Title (RTL Right/Start)
            Text(
                text = "الرئيسية",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 21.sp,
                    textDirection = TextDirection.Content,
                ),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // Action Group: Search, Downloads (if active), Settings (RTL Left/End)
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeaderActionButton(
                    icon = Icons.Outlined.Search,
                    contentDescription = "بحث",
                    onClick = onSearchClick,
                )

                if (activeDownloadsCount > 0) {
                    HeaderActionButton(
                        icon = Icons.Outlined.Download,
                        contentDescription = "التنزيلات",
                        onClick = onDownloadsClick,
                        badgeCount = activeDownloadsCount,
                    )
                }

                HeaderActionButton(
                    icon = Icons.Outlined.Settings,
                    contentDescription = "الإعدادات",
                    onClick = onSettingsClick,
                )
            }
        }

    }
}

@Composable
private fun HeaderActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    badgeCount: Int = 0,
) {
    val actionIconColor = Color(0xFFD5CADE)

    Box(
        modifier = Modifier.size(44.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color(0x141F172A)),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(
                onClick = onClick,
                modifier = Modifier.size(44.dp),
            ) {
                if (badgeCount > 0) {
                    BadgedBox(
                        badge = {
                            Badge(
                                containerColor = MangaroDesignSystem.GoldPrimary,
                                contentColor = Color.Black,
                            ) {
                                Text(
                                    text = badgeCount.toString(),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                )
                            }
                        },
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = contentDescription,
                            tint = actionIconColor,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = contentDescription,
                        tint = actionIconColor,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }
    }
}
