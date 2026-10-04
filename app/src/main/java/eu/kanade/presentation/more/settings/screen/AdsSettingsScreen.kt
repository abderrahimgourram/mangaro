package eu.kanade.presentation.more.settings.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.theme.MangaroDesignSystem
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.ads.AdManager
import eu.kanade.tachiyomi.data.ads.AdBlockerNotice
import eu.kanade.tachiyomi.data.ads.adActivity
import eu.kanade.tachiyomi.util.system.activeNetworkState
import eu.kanade.tachiyomi.util.system.networkStateFlow
import tachiyomi.presentation.core.components.material.Scaffold
import kotlinx.coroutines.delay
import android.os.SystemClock

private enum class RewardButtonState(val label: String) {
    READY("شاهد الإعلان"),
    LOADING("جاري تجهيز الإعلان..."),
    SHOWING("جاري تجهيز الإعلان..."),
    UNAVAILABLE("الإعلان غير متاح الآن"),
    ACTIVE("جلسة بدون إعلانات مفعّلة"),
}

object AdsSettingsScreen : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val uriHandler = LocalUriHandler.current
        val backPress = LocalBackPress.currentOrThrow
        val manager = remember(context) { AdManager.get(context) }
        val state by manager.state.collectAsStateWithLifecycle()
        val networkFlow = remember(context) { context.applicationContext.networkStateFlow() }
        val network by networkFlow.collectAsStateWithLifecycle(initialValue = context.activeNetworkState())
        LaunchedEffect(manager, state.consentReady, state.initialized) { manager.preloadRewarded() }
        val buttonState = when {
            manager.adFreeActive() -> RewardButtonState.ACTIVE
            state.fullscreenShowing -> RewardButtonState.SHOWING
            !network.isOnline -> RewardButtonState.UNAVAILABLE
            state.rewardedLoading -> RewardButtonState.LOADING
            manager.rewardedAvailable() -> RewardButtonState.READY
            else -> RewardButtonState.UNAVAILABLE
        }
        // Presentation only; the manager remains authoritative for reward expiry.
        val rewardClock = remember(state.adFreeUntil) { System.currentTimeMillis() to SystemClock.elapsedRealtime() }
        val remainingMinutes by produceState(0L, state.adFreeUntil) {
            while (true) {
                val now = rewardClock.first + SystemClock.elapsedRealtime() - rewardClock.second
                val remaining = (state.adFreeUntil - now).coerceAtLeast(0)
                value = (remaining + 59_999L) / 60_000L
                if (remaining == 0L) break
                delay(minOf(60_000L, remaining))
            }
        }
        var blockerNotice by remember { mutableStateOf(false) }
        LaunchedEffect(state.blockingSuspected, network.isOnline) {
            if (network.isOnline && state.blockingSuspected && manager.claimBlockerNotice()) blockerNotice = true
            if (!network.isOnline) blockerNotice = false
        }
        if (blockerNotice) AdBlockerNotice(
            onRetry = { blockerNotice = false; manager.preloadRewarded(explicitRetry = true) },
            onContinue = { blockerNotice = false },
        )
        val gold = Color(0xFFD6B56D)
        val foreground = Color(0xFFEFEAF4)
        val secondary = Color(0xFFBFB2CC)
        Scaffold(
            containerColor = MangaroDesignSystem.BackgroundDark,
            topBar = { AppBar(title = "الإعلانات", navigateUp = backPress::invoke) },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 480.dp).fillMaxWidth()
                        .verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MangaroDesignSystem.ShapeBanner,
                        color = MangaroDesignSystem.SurfaceDark,
                        contentColor = foreground,
                        border = MangaroDesignSystem.StrokeSubtle,
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Surface(
                                shape = MangaroDesignSystem.ShapeButton,
                                color = MangaroDesignSystem.SurfaceHigh,
                                contentColor = gold,
                                border = BorderStroke(1.dp, gold.copy(alpha = 0.2f)),
                            ) {
                                Icon(Icons.Outlined.PlayCircleOutline, null, Modifier.padding(10.dp).size(24.dp))
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("جلسة بدون إعلانات", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "شاهد إعلانًا واحدًا واستمتع بـ 30 دقيقة بدون إعلانات.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = secondary,
                                )
                            }
                            if (buttonState == RewardButtonState.ACTIVE) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(Icons.Outlined.CheckCircle, null, Modifier.size(20.dp), tint = gold)
                                    Text(buttonState.label, style = MaterialTheme.typography.bodyMedium, color = gold)
                                }
                                if (remainingMinutes > 0) {
                                    Text("باقي $remainingMinutes دقيقة", style = MaterialTheme.typography.bodySmall, color = secondary)
                                }
                            } else {
                                Button(
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                                    shape = MangaroDesignSystem.ShapeButton,
                                    enabled = buttonState == RewardButtonState.READY,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF49325F),
                                        contentColor = foreground,
                                        disabledContainerColor = MangaroDesignSystem.SurfaceHigh,
                                        disabledContentColor = secondary,
                                    ),
                                    onClick = { context.adActivity()?.let(manager::showRewarded) },
                                ) {
                                    AnimatedContent(
                                        targetState = buttonState,
                                        transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                                        label = "rewardButton",
                                    ) { status ->
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            if (status == RewardButtonState.LOADING) {
                                                CircularProgressIndicator(Modifier.size(18.dp), color = secondary, strokeWidth = 2.dp)
                                            }
                                            Text(status.label, style = MaterialTheme.typography.labelLarge)
                                        }
                                    }
                                }
                                if (buttonState == RewardButtonState.UNAVAILABLE) {
                                    TextButton(
                                        modifier = Modifier.align(Alignment.CenterHorizontally),
                                        onClick = { manager.preloadRewarded(explicitRetry = true) },
                                        enabled = network.isOnline && state.consentReady && state.initialized && !state.fullscreenShowing,
                                    ) { Text("إعادة المحاولة", color = secondary) }
                                }
                            }
                        }
                    }
                    if (state.privacyOptionsRequired) {
                        TextButton(
                            enabled = !state.fullscreenShowing,
                            onClick = { context.adActivity()?.let(manager::showPrivacyOptions) },
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Outlined.Security, null, Modifier.size(18.dp))
                                Text("إدارة تفضيلات الإعلانات", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        listOf(
                            "سياسة الخصوصية" to "https://mangaro-web.vercel.app/privacy",
                            "شروط الاستخدام" to "https://mangaro-web.vercel.app/terms",
                            "المصادر المفتوحة" to "https://mangaro-web.vercel.app/open-source",
                            "من نحن" to "https://mangaro-web.vercel.app/about",
                        ).forEach { (label, url) ->
                            TextButton(onClick = { uriHandler.openUri(url) }) {
                                Text(label, style = MaterialTheme.typography.bodySmall, color = secondary)
                            }
                        }
                    }
                }
            }
        }
    }
}
