package eu.kanade.tachiyomi.data.ads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.util.UUID

private class PendingWebAdDownload(
    val requiredAds: Int,
    val displayAdUrl: String,
    val proceed: OnceAction,
) {
    val id: String = UUID.randomUUID().toString()
    var step by mutableIntStateOf(1)
}

/** One real display page per 50 selected chapters; only the final action starts the download. */
@Composable
fun rememberDownloadAdGate(): (Int, () -> Unit) -> Unit {
    val context = LocalContext.current
    val repository = remember(context) { WebAdConfigRepository.get(context) }
    val config by repository.config.collectAsState()
    val rewardState = remember(context) { AdFreeRewardState.get(context) }
    val activeUntil by rewardState.activeUntil.collectAsState()
    var pending by remember { mutableStateOf<PendingWebAdDownload?>(null) }

    fun advanceOrDownload(request: PendingWebAdDownload, expectedStep: Int) {
        // A callback from the old page cannot advance the next page or start it twice.
        if (pending !== request || request.step != expectedStep) return
        if (expectedStep < request.requiredAds) {
            request.step = expectedStep + 1
        } else {
            pending = null
            request.proceed.run()
        }
    }

    pending?.let { request ->
        val step = request.step
        key(request.id, step) {
            var unavailable by remember { mutableStateOf(!config.enabled || rewardState.isActive()) }
            LaunchedEffect(config.enabled, activeUntil) {
                if (!config.enabled || rewardState.isActive()) unavailable = true
            }
            AlertDialog(
                modifier = Modifier.widthIn(max = 460.dp),
                // Dismissal cancels the pre-download flow; it never starts a download early.
                onDismissRequest = {
                    if (pending === request && request.step == step) pending = null
                },
                title = { Text("إعلان", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                text = {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                "\u2066$step / ${request.requiredAds}\u2069",
                                modifier = Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Ltr),
                                textAlign = TextAlign.Center,
                            )
                        }
                        if (!unavailable) {
                            AdDisplayWebView(
                                url = request.displayAdUrl,
                                modifier = Modifier.fillMaxWidth(),
                                onFailedToLoad = {
                                    if (pending === request && request.step == step) unavailable = true
                                },
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { advanceOrDownload(request, step) }) {
                        Text(if (step == request.requiredAds) "تحميل" else "التالي")
                    }
                },
            )
        }
    }

    return { count, proceed ->
        if (pending == null) {
            val requiredAds = count / 50
            if (requiredAds <= 0 || rewardState.isActive()) {
                proceed()
            } else {
                pending = PendingWebAdDownload(requiredAds, config.displayAdUrl, OnceAction(proceed))
            }
        }
    }
}
