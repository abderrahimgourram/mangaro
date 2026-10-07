package eu.kanade.tachiyomi.data.ads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.theme.MangaroDesignSystem

/** Local UI simulation only. No WebView, SmartLink, advertiser request or reward event. */
private class DebugDownloadRequest(val requiredAds: Int, val proceed: OnceAction) {
    var completed by mutableIntStateOf(0)
        private set
    val remaining: Int get() = requiredAds - completed

    fun completeStep(expectedCompleted: Int) {
        // A stale/doubled button event from the previous frame cannot skip the next step.
        if (completed == expectedCompleted && remaining > 0) completed++
    }
}

@Composable
internal fun rememberDebugDownloadAdGate(): (Int, () -> Unit) -> Unit {
    val context = LocalContext.current
    val reward = remember(context) { AdFreeRewardState.get(context) }
    val activeUntil by reward.activeUntil.collectAsState()
    var pending by remember { mutableStateOf<DebugDownloadRequest?>(null) }

    fun startDownload(request: DebugDownloadRequest) {
        if (pending === request && (request.remaining == 0 || reward.isActive())) {
            pending = null
            request.proceed.run()
        }
    }

    LaunchedEffect(activeUntil, pending) {
        pending?.let { if (reward.isActive()) startDownload(it) }
    }

    pending?.let { request ->
        val completed = request.completed
        val remaining = request.remaining
        val gold = Color(0xFFD6B56D)
        val counter = when {
            remaining == 0 -> "باقي 0"
            remaining in 3..10 -> "باقي \u2066$remaining\u2069 إعلانات"
            else -> "باقي \u2066$remaining\u2069 إعلان"
        }
        val action = when {
            remaining == 0 -> "بدء التنزيل"
            completed == 0 -> "عرض الإعلان"
            remaining == 1 -> "عرض الإعلان الأخير"
            else -> "عرض الإعلان التالي"
        }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            AlertDialog(
                onDismissRequest = { if (pending === request) pending = null },
                containerColor = MangaroDesignSystem.SurfaceDark,
                titleContentColor = Color(0xFFEFEAF4),
                textContentColor = Color(0xFFBFB2CC),
                shape = MangaroDesignSystem.ShapeBanner,
                title = { Text("إعلانات قبل التنزيل") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(counter, style = MaterialTheme.typography.titleLarge, color = gold)
                        LinearProgressIndicator(
                            progress = { completed.toFloat() / request.requiredAds },
                            color = gold,
                            trackColor = MangaroDesignSystem.SurfaceHigh,
                            drawStopIndicator = {},
                        )
                        Text("DEBUG · خطوات محلية تجريبية فقط", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    Button(
                        shape = MangaroDesignSystem.ShapeButton,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF49325F)),
                        onClick = {
                            if (pending === request) {
                                if (remaining == 0) startDownload(request)
                                else request.completeStep(completed)
                            }
                        },
                    ) { Text(action) }
                },
                dismissButton = {
                    TextButton(onClick = { if (pending === request) pending = null }) { Text("إلغاء") }
                },
            )
        }
    }

    return { chapterCount, proceed ->
        if (pending == null) {
            val requiredAds = chapterCount.coerceAtLeast(0) / 50
            if (requiredAds == 0 || reward.isActive()) proceed()
            else pending = DebugDownloadRequest(requiredAds, OnceAction(proceed))
        }
    }
}
