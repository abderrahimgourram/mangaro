package eu.kanade.tachiyomi.data.ads

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** This implementation, including its Arabic labels, is absent from non-Debug source sets. */
internal object AdDebugTools {
    const val enabled = true

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
