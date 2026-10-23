package eu.kanade.tachiyomi.data.stories

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.core.common.util.lang.LocalAccountScope

/** One bounded exact-target refresh per reviewed relation, not a catalog search or chapter probe. */
internal class ConnectedStoryMetadata(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val lock = Mutex()
    private val attempts = LinkedHashMap<Pair<StoryEditionRef, LocalAccountScope.Token>, Long>()

    /** One metadata-only request for the current edition, including pre-upgrade cached records.
     * A name is only a request cue; the fresh response still has to prove reviewed membership.
     */
    suspend fun refreshOrigin(
        registry: VerifiedStoryRelationshipRegistry,
        origin: StoryCandidate,
        token: LocalAccountScope.Token,
        fetch: suspend () -> Unit,
    ) {
        if (!registry.needsOriginMetadata(origin)) return
        lock.withLock {
            val key = origin.destination.edition to token
            if (attempts[key]?.let { now() - it in 0 until 300_000 } == true) return@withLock
            attempts[key] = now()
            while (attempts.size > 64) attempts.remove(attempts.keys.first())
            try {
                withTimeoutOrNull(8_000) { fetch() }
            } catch (
                cancelled: CancellationException,
            ) {
                attempts.remove(key)
                throw cancelled
            } catch (
                _: Exception,
            ) { /* Preserve the current edition, chapters and optional unavailable relation. */ }
        }
    }

    suspend fun prepare(
        registry: VerifiedStoryRelationshipRegistry,
        origin: StoryEditionRef,
        token: LocalAccountScope.Token,
        usable: suspend (StoryEditionRef) -> Boolean,
        fetch: suspend (StoryEditionRef) -> Unit,
    ) {
        val relation = registry.relationship(origin) ?: return
        val target = if (origin.format == StoryFormat.MANHWA) relation.original else relation.adaptation
        // Local metadata wins, including offline use. Do not fetch every alternate edition.
        if (target.editions.any { usable(it) }) return
        lock.withLock {
            if (target.editions.any { usable(it) }) return@withLock
            // A relation has at most eight exact routes; cap attempts to three, and the whole operation to 20 seconds.
            withTimeoutOrNull(20_000) {
                for (route in target.editions.take(3)) {
                    val key = route to token
                    if (attempts[key]?.let { now() - it in 0 until 300_000 } == true) continue
                    attempts[key] = now()
                    while (attempts.size > 64) attempts.remove(attempts.keys.first())
                    try {
                        withTimeoutOrNull(8_000) { fetch(route) }
                    } catch (
                        cancelled: CancellationException,
                    ) {
                        attempts.remove(key)
                        throw cancelled
                    } catch (
                        _: Exception,
                    ) { /* The main details screen and existing cache remain usable. */ }
                    if (usable(route)) break
                }
            }
        }
    }
}
