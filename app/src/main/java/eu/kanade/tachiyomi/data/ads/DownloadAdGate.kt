package eu.kanade.tachiyomi.data.ads

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.UUID

/** One VIDEO opportunity for an eligible user batch; workers never call this gate. */
@Composable
fun rememberDownloadAdGate(readerActive: Boolean = false): (Int, () -> Unit) -> Unit {
    val context = LocalContext.current
    val ads = remember(context) { InMobiAds.get(context) }
    val activity = remember(context) {
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
    }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        if (!readerActive) ads.prepareDownloadSurface(activity)
    }
    return remember(ads, activity, readerActive) {
        var pending = false
        { count, proceed ->
            if (!pending) {
                pending = true
                val continueOnce = OnceAction { try { proceed() } finally { pending = false } }
                if (readerActive) { ads.log("ELIGIBILITY_BLOCKED", "video", "reader_active"); continueOnce.run() }
                else ads.download(activity, count, UUID.randomUUID().toString(), continueOnce::run)
            }
        }
    }
}
