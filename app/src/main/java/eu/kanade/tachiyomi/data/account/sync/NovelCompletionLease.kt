package eu.kanade.tachiyomi.data.account.sync

import eu.kanade.tachiyomi.novels.NovelCloudState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mihon.domain.account.AccountSession
import tachiyomi.core.common.util.lang.LocalAccountScope

/** Ephemeral, non-persisted capability. Never carries tokens or authorizes account ownership. */
internal class NovelCompletionLease private constructor(
    private val owner: String,
    private val generation: LocalAccountScope.Token,
    private val accountSession: AccountSession,
    private val sessionFingerprint: String,
    val enabled: Boolean,
) {
    fun valid(user: String, token: LocalAccountScope.Token, session: AccountSession, fingerprint: String?): Boolean =
        user == owner && token == generation && session === accountSession && fingerprint == sessionFingerprint

    companion object {
        fun negotiate(
            owner: String,
            generation: LocalAccountScope.Token,
            session: AccountSession,
            sessionFingerprint: String?,
            capability: JsonObject,
        ): NovelCompletionLease? {
            if (sessionFingerprint == null || generation.owner != owner ||
                (session as? AccountSession.Authenticated)?.profile?.userId != owner
            ) {
                return null
            }
            if (capability["schema"] != JsonPrimitive(1) || capability["private"] != JsonPrimitive(true) ||
                capability["tables"] != JsonArray(NovelCloudState.tables.map(::JsonPrimitive))
            ) {
                return null
            }
            // Unknown, string, boolean and newer versions are not implicitly supported.
            return NovelCompletionLease(
                owner,
                generation,
                session,
                sessionFingerprint,
                capability["completionEvidence"] == JsonPrimitive(1),
            )
        }
    }
}
