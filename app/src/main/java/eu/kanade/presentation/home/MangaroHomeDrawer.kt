package eu.kanade.presentation.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.History
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.theme.MangaroDesignSystem
import mihon.domain.account.AccountSession
import mihon.domain.account.MangaroProfile
import eu.kanade.presentation.account.AccountDrawerArea
import eu.kanade.tachiyomi.BuildConfig
import kotlinx.coroutines.launch

/** Home-only utility navigation; destinations and download state stay with their existing owners. */
@Composable
fun MangaroHomeDrawer(
    activeDownloadsCount: Int,
    onLibrary: () -> Unit,
    onHistory: () -> Unit = {},
    onDownloads: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    onLicenses: () -> Unit,
    onAccount: () -> Unit,
    accountState: AccountSession = AccountSession.Guest,
    onProfile: ((MangaroProfile) -> Unit)? = null,
    content: @Composable (openDrawer: () -> Unit) -> Unit,
) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val homeViewConfiguration = LocalViewConfiguration.current
    val edgeGesture = remember { DrawerEdgeGesture() }
    // Restrict only Material's drag recognizer. Descendants retain normal touch slop.
    // No custom drawer offset, velocity calculation, or second animation/state is needed.
    val drawerViewConfiguration = remember(homeViewConfiguration, edgeGesture) {
        object : ViewConfiguration by homeViewConfiguration {
            override val touchSlop: Float
                get() = if (edgeGesture.allowed) homeViewConfiguration.touchSlop else Float.MAX_VALUE
        }
    }
    fun select(action: () -> Unit) {
        scope.launch {
            drawer.close()
            action()
        }
    }
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    // Material drawers use the layout start edge, which is the right edge in Arabic.
    CompositionLocalProvider(
        LocalLayoutDirection provides LayoutDirection.Rtl,
        LocalViewConfiguration provides drawerViewConfiguration,
    ) {
        ModalNavigationDrawer(
            drawerState = drawer,
            gesturesEnabled = true,
            modifier = Modifier.pointerInput(drawer) {
                val edgeWidth = 24.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    // Latch the DOWN location for the entire gesture, even as the finger leaves the edge.
                    edgeGesture.allowed = drawer.isOpen || drawer.isAnimationRunning ||
                        down.position.x >= size.width - edgeWidth
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                    } while (event.changes.any { it.pressed })
                }
            },
            scrimColor = Color.Black.copy(alpha = 0.65f),
            drawerContent = {
                CompositionLocalProvider(LocalViewConfiguration provides homeViewConfiguration) {
                    ModalDrawerSheet(
                        modifier = Modifier.widthIn(max = 320.dp),
                        drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
                        drawerContainerColor = MangaroDesignSystem.BackgroundDark,
                        drawerContentColor = Color(0xFFE8DFED),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            AccountDrawerArea(
                                session = accountState,
                                onLogin = { select(onAccount) },
                                onProfile = onProfile?.let { profile -> { user -> select { profile(user) } } },
                            )
                            Text("مكتبتك ومساحتك", style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF9F90AC), modifier = Modifier.padding(start = 12.dp, top = 2.dp))
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                                .background(Brush.horizontalGradient(listOf(Color(0xFF21182C), MangaroDesignSystem.SurfaceDark)))
                                .border(1.dp, Color(0x1A89709F), RoundedCornerShape(20.dp))
                                .padding(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                DrawerAction("المكتبة", Icons.Outlined.BookmarkBorder) { select(onLibrary) }
                                DrawerAction("السجل", Icons.Outlined.History) { select(onHistory) }
                                DrawerAction("التنزيلات", Icons.Outlined.Download, activeDownloadsCount) { select(onDownloads) }
                                DrawerAction("الإعدادات", Icons.Outlined.Settings) { select(onSettings) }
                            }
                            HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 2.dp), color = Color(0x266D557B))
                            Text("عن Mangaro", style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF9F90AC), modifier = Modifier.padding(horizontal = 12.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                DrawerAction("حول التطبيق", Icons.Outlined.Info, secondary = true) { select(onAbout) }
                                DrawerAction("التراخيص مفتوحة المصدر", Icons.Outlined.Description, secondary = true) { select(onLicenses) }
                            }
                            Text("الإصدار ${BuildConfig.VERSION_NAME}", modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelSmall, color = Color(0xFF9F90AC))
                        }
                    }
                }
            },
        ) {
            CompositionLocalProvider(LocalViewConfiguration provides homeViewConfiguration) {
                Box(Modifier.fillMaxSize()) {
                    content { scope.launch { drawer.open() } }
                    if (drawer.isClosed && !drawer.isAnimationRunning) {
                        // This transparent hit target gives edge starts priority over carousels.
                        // It consumes no movement: Material's parent recognizer drives the sheet.
                        Box(
                            Modifier.align(Alignment.CenterStart).fillMaxHeight().width(24.dp)
                                .systemGestureExclusion()
                                .pointerInput(Unit) {
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false)
                                        do {
                                            val event = awaitPointerEvent()
                                        } while (event.changes.any { it.pressed })
                                    }
                                },
                        )
                    }
                }
            }
        }
    }
}

/** Pointer-session flag read by the native drawer recognizer without recomposition. */
private class DrawerEdgeGesture {
    var allowed = false
}

@Composable
private fun DrawerAction(label: String, icon: ImageVector, count: Int = 0, secondary: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.985f else 1f, tween(120), label = "drawerPressScale")
    val tint by animateColorAsState(if (pressed) Color(0xFF392A48) else Color.Transparent, tween(120), label = "drawerPressTint")
    Row(
        modifier = Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(14.dp)).background(tint)
            .clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), onClick = onClick)
            .heightIn(min = if (secondary) 48.dp else 56.dp).padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(if (secondary) 28.dp else 34.dp).clip(RoundedCornerShape(10.dp))
            .background(if (secondary) Color.Transparent else MangaroDesignSystem.LavenderPrimary.copy(alpha = 0.09f)),
            contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(if (secondary) 18.dp else 20.dp),
                tint = if (secondary) Color(0xFF9F90AC) else MangaroDesignSystem.LavenderPrimary)
        }
        Text(label, modifier = Modifier.weight(1f), style = if (secondary) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            color = if (secondary) Color(0xFFB7A9C4) else Color(0xFFE8DFED))
        if (count > 0) {
            Surface(shape = RoundedCornerShape(8.dp), color = MangaroDesignSystem.LavenderPrimary.copy(alpha = 0.1f)) {
                Text(count.toString(), modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall, color = Color(0xFFCEC0DB))
            }
        } else if (!secondary) {
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(15.dp), tint = Color(0xFF786786))
        }
    }
}
