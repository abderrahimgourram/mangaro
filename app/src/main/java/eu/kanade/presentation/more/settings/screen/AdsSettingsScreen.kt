package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.presentation.util.Screen
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.data.ads.AdManager
import eu.kanade.tachiyomi.data.ads.adActivity
import tachiyomi.presentation.core.components.material.Scaffold

object AdsSettingsScreen : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val backPress = LocalBackPress.currentOrThrow
        val manager = remember(context) { AdManager.get(context) }
        val state by manager.state.collectAsState()
        LaunchedEffect(manager, state.consentReady, state.initialized) { manager.preloadRewarded() }
        val adFree = manager.adFreeActive()
        val rewardedAvailable = manager.rewardedAvailable()
        Scaffold(
            topBar = { AppBar(title = "الخصوصية والإعلانات", navigateUp = backPress::invoke) },
        ) { padding ->
            Column(
                Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (state.privacyOptionsRequired) {
                    Text("يمكنك مراجعة خيارات الخصوصية والإعلانات في أي وقت.", style = MaterialTheme.typography.bodyMedium)
                    TextButton(
                        enabled = !state.fullscreenShowing,
                        onClick = { context.adActivity()?.let(manager::showPrivacyOptions) },
                    ) { Text("خيارات الخصوصية") }
                }
                Text("جلسة بدون إعلانات", style = MaterialTheme.typography.titleMedium)
                Text(
                    "شاهد إعلانًا باختيارك للحصول على 30 دقيقة دون إعلانات نهاية الفصل أو التنزيلات الكبيرة. لا يؤثر ذلك على تقدمك أو نقاطك.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (adFree) {
                    Text("جلستك بدون إعلانات مفعّلة", color = MaterialTheme.colorScheme.primary)
                } else {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = rewardedAvailable,
                        onClick = { context.adActivity()?.let(manager::showRewarded) },
                    ) { Text("شاهد إعلانًا واحصل على جلسة بدون إعلانات") }
                    if (state.rewardedLoading) {
                        CircularProgressIndicator()
                    } else if (!rewardedAvailable) {
                        Text("الإعلان غير متاح حاليًا. يمكنك المحاولة لاحقًا.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = manager::preloadRewarded, enabled = state.consentReady && !state.fullscreenShowing) { Text("إعادة المحاولة") }
                    }
                }
            }
        }
    }
}
