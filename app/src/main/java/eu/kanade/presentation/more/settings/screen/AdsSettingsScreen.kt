package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.theme.MangaroDesignSystem
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.ads.AdPrivacyConsent
import eu.kanade.tachiyomi.data.ads.AdDebugTools
import eu.kanade.tachiyomi.data.ads.AdFreeRewardState
import eu.kanade.tachiyomi.data.ads.SmartLinkAdLauncher
import eu.kanade.tachiyomi.data.ads.WebAdConfigRepository
import eu.kanade.tachiyomi.data.ads.adActivity
import tachiyomi.presentation.core.components.material.Scaffold
import kotlinx.coroutines.delay

object AdsSettingsScreen : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val backPress = LocalBackPress.current ?: { navigator.pop(); Unit }
        val repository = remember(context) { WebAdConfigRepository.get(context) }
        val config by repository.config.collectAsState()
        val consentAllowed by AdPrivacyConsent.adsAllowed.collectAsState()
        val privacyOptionsRequired by AdPrivacyConsent.privacyOptionsRequired.collectAsState()
        val rewardState = remember(context) { AdFreeRewardState.get(context) }
        val verifiedCompletions by rewardState.verifiedCompletions.collectAsState()
        val activeUntil by rewardState.activeUntil.collectAsState()
        var now by remember(activeUntil) { mutableLongStateOf(System.currentTimeMillis()) }
        val remainingSeconds = ((activeUntil - now).coerceAtLeast(0L) + 999L) / 1000L
        val rewardActive = remainingSeconds > 0L
        LaunchedEffect(activeUntil) {
            // Presentation ticks read the shared persisted deadline; they never extend it.
            while (activeUntil > System.currentTimeMillis()) {
                now = System.currentTimeMillis()
                delay((activeUntil - now).coerceIn(1L, 1000L))
            }
            now = System.currentTimeMillis()
            rewardState.isActive(now)
        }
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
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MangaroDesignSystem.ShapeBanner,
                        color = MangaroDesignSystem.SurfaceDark,
                        contentColor = foreground,
                        border = MangaroDesignSystem.StrokeSubtle,
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Surface(
                                shape = MangaroDesignSystem.ShapeButton,
                                color = MangaroDesignSystem.SurfaceHigh,
                                contentColor = gold,
                                border = MangaroDesignSystem.StrokeSubtle,
                            ) {
                                Icon(Icons.Outlined.PlayCircleOutline, null, Modifier.padding(10.dp))
                            }
                            Text("إعلانات Mangaro", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "قد تظهر إعلانات في نهاية بعض الفصول أو عند تنزيل دفعة كبيرة. تبقى القراءة والتنزيلات متاحة إذا لم يظهر إعلان.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = secondary,
                            )
                            // Debug's single offer button is purely local; it never opens SmartLink.
                            if (!AdDebugTools.enabled && config.enabled && consentAllowed && config.smartLink.isNotBlank()) {
                                Button(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = MangaroDesignSystem.ShapeButton,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF49325F)),
                                    onClick = { SmartLinkAdLauncher.open(context, config.smartLink) },
                                ) {
                                    Text("فتح عرض إعلاني")
                                }
                            }
                        }
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MangaroDesignSystem.ShapeBanner,
                        color = MangaroDesignSystem.SurfaceDark,
                        contentColor = foreground,
                        border = MangaroDesignSystem.StrokeSubtle,
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("جلسة بدون إعلانات", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (rewardActive) "جلسة بدون إعلانات مفعّلة" else "أكمل 5 عروض مؤهلة للحصول على 30 دقيقة بدون إعلانات",
                                style = MaterialTheme.typography.bodyMedium,
                                color = secondary,
                            )
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Text(
                                    "\u2066${verifiedCompletions.coerceIn(0, 5)} / 5\u2069",
                                    style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Ltr),
                                    color = gold,
                                )
                            }
                            if (rewardActive) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text("متبقي", style = MaterialTheme.typography.bodyMedium, color = secondary)
                                    Text(
                                        "\u2066${(remainingSeconds / 60).toString().padStart(2, '0')}:${(remainingSeconds % 60).toString().padStart(2, '0')}\u2069",
                                        style = MaterialTheme.typography.headlineSmall.copy(textDirection = TextDirection.Ltr),
                                        color = gold,
                                    )
                                }
                            }
                            LinearProgressIndicator(
                                progress = { if (rewardActive) 1f else verifiedCompletions / 5f },
                                modifier = Modifier.fillMaxWidth(),
                                color = gold,
                                trackColor = MangaroDesignSystem.SurfaceHigh,
                                drawStopIndicator = {},
                            )
                            if (AdDebugTools.enabled) {
                                AdDebugTools.RewardAction(rewardState, rewardActive)
                            } else {
                                OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (rewardActive) "الجلسة مفعّلة" else "العروض المؤهلة غير متاحة الآن")
                                }
                            }
                        }
                    }
                    if (privacyOptionsRequired) {
                        TextButton(
                            onClick = { context.adActivity()?.let(AdPrivacyConsent::showPrivacyOptions) },
                        ) {
                            Icon(Icons.Outlined.Security, null, Modifier.padding(end = 8.dp))
                            Text("إدارة تفضيلات الإعلانات", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
