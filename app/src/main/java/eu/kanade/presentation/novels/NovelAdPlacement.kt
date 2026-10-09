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

/** Screen-owned request state survives lazy-item disposal without reloading the creative. */
internal class NovelAdRequest(val key: String) {
    val owner = UUID.randomUUID().toString()
    var reserved by mutableStateOf(false)
    var started by mutableStateOf(false)
    var ended by mutableStateOf(false)
}

/** Reuses the Manhwa display loader and policy; novel authorization remains fail-closed. */
@Composable
internal fun NovelAdPlacement(request: NovelAdRequest, visible: Boolean = true, allowStart: Boolean = true) {
    val context = LocalContext.current
    val config = remember(context) { WebAdConfigRepository.get(context) }
    val policy = remember(context) { ReaderWebAdPolicy.get(context) }
    val rewards = remember(context) { AdFreeRewardState.get(context) }
    val settings by config.config.collectAsStateWithLifecycle()
    val adFreeUntil by rewards.activeUntil.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val key = request.key
    val owner = request.owner
    DisposableEffect(request) {
        onDispose {
            policy.releaseContentPlacement(key, owner)
            request.reserved = false
            if (request.started) request.ended = true
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val eligible = visible && maxWidth >= 300.dp && lifecycle.isAtLeast(Lifecycle.State.RESUMED) &&
            settings.enabled && settings.novelPlacementsApproved && System.currentTimeMillis() >= adFreeUntil
        LaunchedEffect(eligible, allowStart, key) {
            if (eligible && allowStart && !request.started && !request.ended) request.reserved = policy.reserveContentPlacement(key, owner)
            else if ((!eligible || !allowStart) && request.reserved && !request.started) {
                policy.releaseContentPlacement(key, owner)
                request.reserved = false
            }
        }
        if (eligible && (allowStart || request.started) && request.reserved && !request.ended) {
            // Keep the shared creative intact: constrain width, never crop its reported height.
            Column(Modifier.widthIn(max = 320.dp).fillMaxWidth().align(Alignment.TopCenter), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("إعلان", color = MangaroDesignSystem.LavenderPrimary, style = MaterialTheme.typography.labelSmall)
                AdDisplayWebView(settings.displayAdUrl,
                    onRequestStarted = {
                        if (!request.started) {
                            request.started = policy.commitContentPlacement(key, owner)
                            if (!request.started) request.ended = true
                        }
                    },
                    onFailedToLoad = { request.ended = true },
                    onReleased = {
                        policy.releaseContentPlacement(key, owner)
                        if (request.started) request.ended = true
                    })
            }
        }
    }
}
