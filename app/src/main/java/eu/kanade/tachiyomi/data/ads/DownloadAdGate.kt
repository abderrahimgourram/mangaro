package eu.kanade.tachiyomi.data.ads

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.util.UUID

internal fun Context.adActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.adActivity()
    else -> null
}

private data class PendingDownload(val count: Int, val operation: String, val proceed: OnceAction)

/** Ordinary confirmation precedes any eligible ad. The existing queue still owns the download. */
@Composable
fun rememberDownloadAdGate(): (Int, () -> Unit) -> Unit {
    val context = LocalContext.current
    val manager = remember(context) { AdManager.get(context) }
    var pending by remember { mutableStateOf<PendingDownload?>(null) }
    var starting by remember { mutableStateOf(false) }
    LaunchedEffect(manager) { manager.preloadInterstitial() }
    pending?.let { request ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("تنزيل الفصول") },
            text = { Text("هل تريد تنزيل ${request.count} فصلًا؟") },
            confirmButton = {
                TextButton(onClick = {
                    if (!starting) {
                        starting = true
                        pending = null
                        manager.download(context.adActivity(), request.count, request.operation) {
                            request.proceed.run()
                            starting = false
                        }
                    }
                }) { Text("تنزيل") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("إلغاء") } },
        )
    }
    return { count, proceed ->
        if (!starting && pending == null) {
            if (count > AdPolicy.DOWNLOAD_THRESHOLD) {
                pending = PendingDownload(count, UUID.randomUUID().toString(), OnceAction(proceed))
            } else {
                proceed()
            }
        }
    }
}
