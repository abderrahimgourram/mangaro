package tachiyomi.core.common.util.lang

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Local ownership only. Identity is bound by the verified Auth adapter, never by a screen. */
object LocalAccountScope {
    const val GUEST = "guest"
    data class Token(val owner: String, val generation: Long, val fileOwner: String = if (owner == GUEST) "guest:${LocalAccountScope.guestGeneration}" else owner) {
        override fun toString() = "LocalAccountToken(generation=$generation)"
    }
    data class State(val token: Token = Token(GUEST, 0), val ready: Boolean = true, val metadataVersion: Long = 0)
    private val state = MutableStateFlow(State())
    val changes = state.asStateFlow()
    @Volatile private var installed = false
    private val inherited = ThreadLocal<Token?>()
    fun capture(): Token = inherited.get() ?: state.value.token
    fun active(): Token = state.value.token
    fun check(token: Token = capture()) {
        if (installed && (!state.value.ready || token != state.value.token)) throw Changed()
    }
    fun matchesOwner(owner: String) = !installed || state.value.ready && active().owner == owner
    fun boundOwnerOrNull(): String? = if (installed) capture().owner else null
    fun checkOwner(owner: String) { if (!installed) return; check(); if (owner != active().owner) throw Changed() }
    fun beginTransition() { installed = true; state.value = state.value.copy(ready = false) }
    fun commit(owner: String) { installed = true; val old = state.value.token; state.value = State(Token(owner, old.generation + 1)) }
    fun abortTransition() { state.value = state.value.copy(ready = true) }
    class Changed : CancellationException("Local account changed")
    class Element(val token: Token) : ThreadContextElement<Token?>, AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Element>
        override fun updateThreadContext(context: CoroutineContext): Token? = inherited.get().also { inherited.set(token) }
        override fun restoreThreadContext(context: CoroutineContext, oldState: Token?) { inherited.set(oldState) }
    }
    fun context() = Element(capture())
    private val storageOwner = ThreadLocal<String?>()
    @Volatile var guestGeneration: Long = 0
        private set
    @Volatile private var fileClaims: Map<String, List<Long>> = emptyMap()
    @Volatile private var categoryClaims: Map<String, Map<Long, Long>> = emptyMap()
    fun installFileScopes(generation: Long, claims: Map<String, List<Long>>, categories: Map<String, Map<Long, Long>> = emptyMap()) {
        guestGeneration = generation; fileClaims = claims; categoryClaims = categories
        state.value = state.value.copy(metadataVersion = state.value.metadataVersion + 1)
    }
    fun claimedCategory(actor: String, originalOwner: String, id: Long): Long? =
        if (id <= 0 || actor == originalOwner) id else categoryClaims["$actor:$originalOwner"]?.get(id)
    fun activeFileOwner(): String = active().fileOwner
    fun fileOwner(): String = storageOwner.get() ?: capture().fileOwner
    fun readableFileOwners(owner: String = fileOwner()): List<String> = listOf(owner) + fileClaims[owner].orEmpty().map { "guest:$it" }
    fun segment(owner: String = fileOwner()): String = if (owner == "guest:0" || owner == GUEST && guestGeneration == 0L) "" else if (owner.startsWith("guest:")) "guest-${owner.substringAfter(':').toLong()}" else java.security.MessageDigest.getInstance("SHA-256").digest(owner.toByteArray()).joinToString("") { "%02x".format(it) }
    class StorageElement(private val owner: String) : ThreadContextElement<String?>, AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<StorageElement>
        override fun updateThreadContext(context: CoroutineContext): String? = storageOwner.get().also { storageOwner.set(owner) }
        override fun restoreThreadContext(context: CoroutineContext, oldState: String?) { storageOwner.set(oldState) }
    }
}
