package eu.kanade.tachiyomi.data.ads

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.UUID
import kotlinx.coroutines.launch

internal fun Context.adActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.adActivity()
    else -> null
}

private data class PendingDownload(val count: Int, val operation: String, val proceed: OnceAction)
private enum class DownloadAdStage { CONFIRM, HIDDEN, BLOCKED, RETRYING }

/** Confirmation and bounded retry wrap the existing queue. No files or library data are changed here. */
@Composable
fun rememberDownloadAdGate(): (Int, () -> Unit) -> Unit {
    val context = LocalContext.current
    val manager = remember(context) { AdManager.get(context) }
    val scope = rememberCoroutineScope()
    val adState by manager.state.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<PendingDownload?>(null) }
    var stage by remember { mutableStateOf(DownloadAdStage.CONFIRM) }
    var warning by remember { mutableStateOf(false) }
    LaunchedEffect(manager) { manager.preloadInterstitial() }

    fun attempt(request: PendingDownload, retry: Boolean) {
        warning = false
        stage = if (retry) DownloadAdStage.RETRYING else DownloadAdStage.HIDDEN
        val blocked = {
            stage = DownloadAdStage.BLOCKED
            warning = manager.claimBlockerNotice()
        }
        val proceed = {
            pending = null
            request.proceed.run()
        }
        if (retry) scope.launch {
            manager.retryDownload(context.adActivity(), request.count, request.operation, blocked, proceed)
        } else scope.launch {
            manager.downloadWhenReady(context.adActivity(), request.count, request.operation, blocked, proceed)
        }
    }
    pending?.let { request ->
        if (warning) {
            AdBlockerNotice(onRetry = { attempt(request, true) }, onContinue = { warning = false })
        } else if (stage != DownloadAdStage.HIDDEN && !adState.fullscreenShowing) {
            val retrying = stage == DownloadAdStage.RETRYING
            val confirmed = stage != DownloadAdStage.CONFIRM
            AlertDialog(
                onDismissRequest = { if (!retrying) pending = null },
                title = { Text(if (confirmed) "تعذر تجهيز الإعلان" else "تنزيل الفصول") },
                text = {
                    if (retrying) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("جاري تجهيز الإعلان...")
                    } else Text(if (confirmed) "عطّل مانع الإعلانات ثم حاول مجددًا." else "هل تريد تنزيل ${request.count} فصلًا؟")
                },
                confirmButton = {
                    TextButton(enabled = !retrying, onClick = { attempt(request, confirmed) }) {
                        Text(if (confirmed) "حاول مجددًا" else "تنزيل")
                    }
                },
                dismissButton = { TextButton(enabled = !retrying, onClick = { pending = null }) { Text("إلغاء") } },
            )
        }
    }
    return { count, proceed ->
        if (pending == null) {
            if (count > AdPolicy.DOWNLOAD_THRESHOLD) {
                // Each new confirmed operation can prepare a fresh ad; identities stay independent.
                manager.preloadInterstitial()
                stage = DownloadAdStage.CONFIRM
                pending = PendingDownload(count, UUID.randomUUID().toString(), OnceAction(proceed))
            } else proceed()
        }
    }
}
