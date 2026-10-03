package eu.kanade.presentation.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.theme.MangaroDesignSystem
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.launch

/** Home-only utility navigation; destinations and download state stay with their existing owners. */
@Composable
fun MangaroHomeDrawer(
    activeDownloadsCount: Int,
    onLibrary: () -> Unit,
    onDownloads: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    onLicenses: () -> Unit,
    content: @Composable (openDrawer: () -> Unit) -> Unit,
) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    fun select(action: () -> Unit) {
        scope.launch {
            drawer.close()
            action()
        }
    }
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    // Material drawers use the layout start edge, which is the right edge in Arabic.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalNavigationDrawer(
            drawerState = drawer,
            gesturesEnabled = drawer.isOpen,
            scrimColor = Color.Black.copy(alpha = 0.65f),
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier.widthIn(max = 304.dp),
                    drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
                    drawerContainerColor = MangaroDesignSystem.SurfaceDark,
                    drawerContentColor = Color(0xFFE8DFED),
                ) {
                    Column(
                        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Image(painterResource(R.drawable.ic_splash_logo), null, Modifier.size(60.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("MANGARO", style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold, color = MangaroDesignSystem.GoldPrimary)
                                Text("قارئ المانجا الخاص بك", style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFB7A9C4))
                            }
                        }
                        DrawerAction("المكتبة", Icons.Outlined.BookmarkBorder) { select(onLibrary) }
                        DrawerAction("التنزيلات", Icons.Outlined.Download, activeDownloadsCount) { select(onDownloads) }
                        DrawerAction("الإعدادات", Icons.Outlined.Settings) { select(onSettings) }
                        HorizontalDivider(Modifier.padding(vertical = 14.dp), color = Color(0x266D557B))
                        Text("عن Mangaro", style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFFB7A9C4), modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                        DrawerAction("حول التطبيق", Icons.Outlined.Info) { select(onAbout) }
                        DrawerAction("التراخيص مفتوحة المصدر", Icons.Outlined.Description) { select(onLicenses) }
                    }
                    Text("الإصدار ${BuildConfig.VERSION_NAME}", modifier = Modifier.padding(24.dp),
                        style = MaterialTheme.typography.labelSmall, color = Color(0xFF9F90AC))
                }
            },
        ) {
            content { scope.launch { drawer.open() } }
        }
    }
}

@Composable
private fun DrawerAction(label: String, icon: ImageVector, count: Int = 0, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MangaroDesignSystem.SurfaceHigh.copy(alpha = 0.45f)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(20.dp), tint = MangaroDesignSystem.LavenderPrimary)
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (count > 0) {
                Surface(shape = RoundedCornerShape(8.dp), color = MangaroDesignSystem.GoldPrimary.copy(alpha = 0.12f)) {
                    Text(count.toString(), modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall, color = MangaroDesignSystem.GoldPrimary)
                }
            }
        }
    }
}
