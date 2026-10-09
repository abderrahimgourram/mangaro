package eu.kanade.presentation.novels

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.kanade.presentation.theme.MangaroDesignSystem
import eu.kanade.tachiyomi.data.ads.AdDisplayWebView
import eu.kanade.tachiyomi.data.ads.AdFreeRewardState
import eu.kanade.tachiyomi.data.ads.ReaderWebAdPolicy
import eu.kanade.tachiyomi.data.ads.WebAdConfigRepository
import java.util.UUID

/** Existing isolated display slot only, with separate explicit approval for novels. */
@Composable
internal fun NovelAdPlacement(key: String, visible: Boolean = true, allowStart: Boolean = true) {
    val context = LocalContext.current
    val config = remember(context) { WebAdConfigRepository.get(context) }
    val policy = remember(context) { ReaderWebAdPolicy.get(context) }
    val rewards = remember(context) { AdFreeRewardState.get(context) }
    val settings by config.config.collectAsStateWithLifecycle()
    val adFreeUntil by rewards.activeUntil.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val owner = remember(key) { UUID.randomUUID().toString() }
    var reserved by remember(key) { mutableStateOf(false) }
    var started by remember(key) { mutableStateOf(false) }
    var ended by remember(key) { mutableStateOf(false) }
    DisposableEffect(key, owner) {
        onDispose { policy.releaseContentPlacement(key, owner) }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val eligible = visible && maxWidth >= 300.dp && lifecycle.isAtLeast(Lifecycle.State.RESUMED) &&
            settings.enabled && settings.novelPlacementsApproved && System.currentTimeMillis() >= adFreeUntil
        LaunchedEffect(eligible, allowStart, key) {
            if (eligible && allowStart && !started && !ended) reserved = policy.reserveContentPlacement(key, owner)
            else if ((!eligible || !allowStart) && reserved && !started) {
                policy.releaseContentPlacement(key, owner)
                reserved = false
            }
        }
        if (eligible && (allowStart || started) && reserved && !ended) {
            // Keep the shared creative intact: constrain width, never crop its reported height.
            Column(Modifier.widthIn(max = 320.dp).fillMaxWidth().align(Alignment.TopCenter), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("إعلان", color = MangaroDesignSystem.LavenderPrimary, style = MaterialTheme.typography.labelSmall)
                AdDisplayWebView(settings.displayAdUrl,
                    onRequestStarted = {
                        started = policy.commitContentPlacement(key, owner)
                        if (!started) ended = true
                    },
                    onFailedToLoad = { ended = true },
                    onReleased = {
                        policy.releaseContentPlacement(key, owner)
                        if (started) ended = true
                    })
            }
        }
    }
}
