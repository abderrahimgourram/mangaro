package eu.kanade.tachiyomi.data.ads

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.UUID

private class BoundaryRequest {
    val owner: String = UUID.randomUUID().toString()
    var reserved by mutableStateOf(false)
    var started by mutableStateOf(false)
    var ended by mutableStateOf(false)
}

/** One display-first web request, only at the existing completed chapter boundary. */
@Composable
fun NativeBoundaryAd(session: String, chapterId: Long, visible: Boolean) {
    val context = LocalContext.current
    val config = remember(context) { WebAdConfigRepository.get(context) }
    val policy = remember(context) { ReaderWebAdPolicy.get(context) }
    val rewardState = remember(context) { AdFreeRewardState.get(context) }
    val webConfig by config.config.collectAsStateWithLifecycle()
    val completionRevision by policy.completionRevision.collectAsStateWithLifecycle()
    val activeUntil by rewardState.activeUntil.collectAsStateWithLifecycle()
    val adFree = System.currentTimeMillis() < activeUntil
    val request = remember(session, chapterId) { BoundaryRequest() }

    LaunchedEffect(session, chapterId, visible, webConfig.enabled, completionRevision, activeUntil) {
        if ((!visible || !webConfig.enabled || adFree) && request.reserved && !request.started) {
            policy.releaseBoundaryOpportunity(session, chapterId, request.owner)
            request.reserved = false
        }
        if (visible && webConfig.enabled && !adFree && !request.reserved && !request.started && !request.ended) {
            request.reserved = policy.reserveBoundaryOpportunity(session, chapterId, request.owner)
        }
    }

    DisposableEffect(session, chapterId, request) {
        onDispose {
            policy.releaseBoundaryOpportunity(session, chapterId, request.owner)
        }
    }

    // Keep a started request mounted during brief visibility changes; never load it again.
    if ((visible || request.started) && webConfig.enabled && !adFree && request.reserved && !request.ended) {
        AdDisplayWebView(
            url = webConfig.displayAdUrl,
            modifier = Modifier.fillMaxWidth(),
            onRequestStarted = {
                if (!request.started) {
                    if (policy.commitBoundaryRequest(session, chapterId, request.owner)) request.started = true
                    else request.ended = true
                }
            },
            onFailedToLoad = { request.ended = true },
            onReleased = {
                policy.releaseBoundaryOpportunity(session, chapterId, request.owner)
                request.reserved = false
                if (request.started) request.ended = true
            },
        )
    }
}
