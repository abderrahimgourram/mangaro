package eu.kanade.tachiyomi.data.ads

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** This implementation, including its Arabic labels, is absent from non-Debug source sets. */
internal object AdDebugTools {
    const val enabled = true

    private class LocalCompletion : RewardCompletionProvider {
        private var listener: (() -> Unit)? = null
        override fun setVerifiedRewardCompletedListener(listener: () -> Unit) {
            this.listener = listener
        }
        fun completeOnce() { listener?.invoke() }
        fun release() { listener = null }
    }

    @Composable
    fun RewardAction(state: AdFreeRewardState, active: Boolean) {
        val completion = remember(state) { LocalCompletion() }
        DisposableEffect(state, completion) {
            state.connect(completion)
            onDispose { completion.release() }
        }
        Button(
            onClick = completion::completeOnce,
            enabled = !active,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("فتح عرض إعلاني")
        }
        Text("اختبار محلي في نسخة Debug فقط", style = MaterialTheme.typography.bodySmall)
    }

    @Composable
    fun DownloadGate(): ((Int, () -> Unit) -> Unit)? = rememberDebugDownloadAdGate()

    @Composable
    fun DisplayFallback(modifier: Modifier) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("إعلان تجريبي — غير ربحي", style = MaterialTheme.typography.titleSmall)
                Text(
                    "موضع محلي للاختبار فقط. يمكنك المتابعة مباشرة.",
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
