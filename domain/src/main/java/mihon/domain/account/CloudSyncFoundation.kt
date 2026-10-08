package mihon.domain.account

import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext

/** Hints are persisted account-scoped by the app after successful local writes, never network calls. */
object LocalCloudChanges {
    enum class Kind { MANGA, CHAPTER, HISTORY_CHAPTER, HISTORY_MANGA, HISTORY_ALL, COLLECTIONS, COLLECTION_DELETED, RESOLVE }
    data class Change(val kind: Kind, val id: Long = 0)
    private val remote = ThreadLocal.withInitial { false }
    @Volatile var capture: ((Change) -> Unit)? = null
    fun changed(kind: Kind, id: Long = 0) {
        if (!remote.get()) try { capture?.invoke(Change(kind, id)) } catch (_: Exception) { /* Local data remains authoritative. */ }
    }
    fun isRemoteApplication(): Boolean = remote.get() == true
    suspend fun <T> applyRemote(block: suspend () -> T): T = withContext(remote.asContextElement(true)) { block() }
}

data class CloudSyncStatus(val enabled: Boolean = false, val decisionMade: Boolean = false,
    val running: Boolean = false, val pending: Int = 0, val lastSuccess: Long? = null,
    val error: String? = null, val needsMerge: Boolean = false, val unresolved: Int = 0, val loaded: Boolean = false)

/** Initial merge is additive; established conflicts require an observed baseline. */
object CloudSyncPolicy {
    fun canSend(owner: String, active: String?, enabled: Boolean) = enabled && owner == active
    fun initialRead(local: Boolean, remote: Boolean) = local || remote
    fun initialPage(local: Long, remote: Long) = maxOf(local, remote)
    fun initialLibrary(local: Boolean, remote: Boolean) = local || remote
    fun mergeHigherPage(localRead: Boolean, remoteRead: Boolean, localPage: Long, remotePage: Long) =
        localRead == remoteRead && localPage > remotePage
}

/** Device Library is shared: switching its bound account requires an explicit non-destructive merge. */
object AutomaticCloudBinding {
    fun requiresMerge(user: String, bound: String?, otherConfiguredAccounts: Set<String>) =
        (bound != null && bound != user) || (bound == null && otherConfiguredAccounts.any { it != user })
}
