package eu.kanade.tachiyomi.data.ads

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Places the real responsive Adsterra Native Banner only in the existing chapter boundary. */
@Composable
fun NativeBoundaryAd(session: String, chapterId: Long, visible: Boolean) {
    val context = LocalContext.current
    val config = remember(context) { WebAdConfigRepository.get(context) }
    val policy = remember(context) { ReaderWebAdPolicy.get(context) }
    val webConfig by config.config.collectAsStateWithLifecycle()
    val consentAllowed by AdPrivacyConsent.adsAllowed.collectAsStateWithLifecycle()
    var eligible by remember(session, chapterId) { mutableStateOf(false) }

    LaunchedEffect(session, chapterId, visible, webConfig.enabled, consentAllowed) {
        if (visible && webConfig.enabled && consentAllowed) {
            eligible = policy.claimBoundaryOpportunity(session, chapterId)
        }
    }

    if (visible && webConfig.enabled && consentAllowed && eligible) {
        AdDisplayWebView(url = webConfig.displayAdUrl, modifier = Modifier.fillMaxWidth())
    }
}
