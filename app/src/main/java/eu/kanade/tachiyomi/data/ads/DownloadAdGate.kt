package eu.kanade.tachiyomi.data.ads

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.UUID

internal fun Context.adActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.adActivity()
    else -> null
}

private data class PendingWebAdDownload(val count: Int, val id: String, val proceed: OnceAction)

/** Offers one actual web display opportunity for each 50 selected chapters; failure continues. */
@Composable
fun rememberDownloadAdGate(): (Int, () -> Unit) -> Unit {
    val context = LocalContext.current
    val repository = remember(context) { WebAdConfigRepository.get(context) }
    val config by repository.config.collectAsState()
    val consentAllowed by AdPrivacyConsent.adsAllowed.collectAsState()
    val rewardState = remember(context) { AdFreeRewardState.get(context) }
    var pending by remember { mutableStateOf<PendingWebAdDownload?>(null) }
    var confirmed by remember { mutableStateOf(false) }
    var opportunity by remember { mutableIntStateOf(1) }

    fun finishOrContinue(request: PendingWebAdDownload) {
        if (pending?.id != request.id) return
        if (opportunity < request.count / 50 && config.enabled && consentAllowed && !rewardState.isActive()) {
            opportunity++
        } else {
            pending = null
            confirmed = false
            request.proceed.run()
        }
    }
    fun failOpen(request: PendingWebAdDownload) {
        if (pending?.id == request.id) {
            pending = null
            confirmed = false
            request.proceed.run()
        }
    }

    pending?.let { request ->
        if (!confirmed) {
            AlertDialog(
                modifier = Modifier.widthIn(max = 460.dp),
                onDismissRequest = { if (pending?.id == request.id) pending = null },
                title = { Text("تنزيل الفصول") },
                text = { Text("هل تريد تنزيل ${request.count} فصلًا؟") },
                confirmButton = {
                    TextButton(onClick = {
                        if (config.enabled && consentAllowed && request.count >= 50 && !rewardState.isActive()) confirmed = true
                        else {
                            pending = null
                            request.proceed.run()
                        }
                    }) { Text("متابعة") }
                },
                dismissButton = {
                    TextButton(onClick = { pending = null }) { Text("إلغاء") }
                },
            )
        } else {
            key(request.id, opportunity) {
                AlertDialog(
                    modifier = Modifier.widthIn(max = 460.dp),
                    onDismissRequest = { finishOrContinue(request) },
                    title = { Text("إعلان") },
                    text = {
                        Column(
                            Modifier.fillMaxWidth().padding(top = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AdDisplayWebView(
                                url = config.displayAdUrl,
                                modifier = Modifier.fillMaxWidth(),
                                onFailedToLoad = { failOpen(request) },
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { finishOrContinue(request) }) {
                            Text("متابعة التنزيل")
                        }
                    },
                )
            }
        }
    }

    return { count, proceed ->
        if (pending == null) {
            if (count < 50) {
                proceed()
            } else {
                pending = PendingWebAdDownload(count, UUID.randomUUID().toString(), OnceAction(proceed))
                confirmed = false
                opportunity = 1
            }
        }
    }
}
