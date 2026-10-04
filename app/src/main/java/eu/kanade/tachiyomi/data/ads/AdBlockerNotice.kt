package eu.kanade.tachiyomi.data.ads

import android.os.SystemClock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.delay

/** Only deliberate Ads/download actions use this notice; it never interrupts Reader pages. */
@Composable
internal fun AdBlockerNotice(onRetry: () -> Unit, onContinue: () -> Unit) {
    val openedAt = rememberSaveable { SystemClock.elapsedRealtime() }
    val canContinue by produceState(false, openedAt) {
        delay((AdBlockerEvidence.SKIP_DELAY - (SystemClock.elapsedRealtime() - openedAt)).coerceAtLeast(0))
        value = true
    }
    AlertDialog(
        onDismissRequest = { if (canContinue) onContinue() },
        title = { Text("يبدو أن الإعلانات محجوبة") },
        text = { Text("قد يمنع مانع الإعلانات أو DNS الخاص ظهور الإعلانات. عطّله مؤقتًا إذا أردت استخدام الميزات التي تعتمد على الإعلان.") },
        confirmButton = { TextButton(onClick = onRetry) { Text("حاول مجددًا") } },
        dismissButton = { TextButton(enabled = canContinue, onClick = onContinue) { Text("متابعة") } },
    )
}
