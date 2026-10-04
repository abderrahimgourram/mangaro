package eu.kanade.presentation.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.kanade.tachiyomi.data.inbox.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import mihon.domain.account.*
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class InboxViewModel(
    private val account: AccountFoundation = Injekt.get(),
    val work: WorkUpdateInbox = Injekt.get(),
    private val replies: ReplyInbox = Injekt.get(),
) : ViewModel() {
    val session = account.session
    private val mutable = MutableStateFlow(InboxState())
    val state = mutable.asStateFlow()
    private var request: Job? = null
    private val readActions = mutableSetOf<Pair<String, String?>>()
    private var generation = 0L
    private fun actor() = (session.value as? AccountSession.Authenticated)?.profile?.userId
    init {
        viewModelScope.launch {
            replies.unreadCounts.collect { counts ->
                val user = actor()
                if (user != null && mutable.value.owner == user) mutable.update { it.copy(unread = counts[user] ?: 0) }
            }
        }
        viewModelScope.launch {
            session.map { (it as? AccountSession.Authenticated)?.profile?.userId }.distinctUntilChanged().collectLatest { user ->
                generation++; request?.cancel(); mutable.value = InboxState(owner = user)
                if (user != null) refreshBadge()
            }
        }
    }
    fun refreshBadge() {
        val user = actor() ?: return
        if (request?.isActive == true) return
        val version = generation
        request = viewModelScope.launch {
            try { val count = replies.unread(user); if (actor() == user && version == generation) mutable.update { it.copy(unread = count) } }
            catch (c: CancellationException) { throw c } catch (_: Exception) { /* Optional badge never blocks Home. */ }
        }
    }
    fun load(more: Boolean = false) {
        val user = actor() ?: return
        if (mutable.value.loading || (more && !mutable.value.hasMore)) return
        request?.cancel()
        val version = generation
        mutable.update { it.copy(loading = true, error = null) }
        request = viewModelScope.launch {
            try {
                val page = replies.page(user, if (more) mutable.value.items.lastOrNull() else null)
                if (actor() == user && version == generation) mutable.update { it.copy(items = ((if (more) it.items else emptyList()) + page.items).distinctBy { row -> row.id },
                    hasMore = page.hasMore, loading = false, loaded = true) }
                try { replies.unread(user) } catch (c: CancellationException) { throw c } catch (_: Exception) { }
            } catch (c: CancellationException) { if (actor() == user && version == generation) mutable.update { it.copy(loading = false) }; throw c }
            catch (_: Exception) { if (actor() == user && version == generation) mutable.update { it.copy(loading = false, error = "تعذّر تحميل الردود. حاول مجددًا") } }
        }
    }
    fun markRead(id: String? = null) {
        val user = actor() ?: return
        val action = user to id
        if ((user to null) in readActions || (id == null && readActions.any { it.first == user }) || !readActions.add(action)) return
        val version = generation
        mutable.update { it.copy(marking = true) }
        viewModelScope.launch {
            try {
                replies.markRead(user, id)
                if (actor() == user && version == generation) {
                    mutable.update { state ->
                        state.copy(items = state.items.map { if (id == null || it.id == id) it.copy(readAt = it.readAt ?: "read") else it },
                            unread = replies.unreadCounts.value[user] ?: state.unread, error = null)
                    }
                }
            } catch (c: CancellationException) { throw c }
            catch (_: Exception) { if (actor() == user && version == generation) { mutable.update { it.copy(error = "تعذّر حفظ حالة القراءة. حاول مجددًا") } } }
            finally {
                readActions.remove(action)
                if (actor() == user && version == generation) mutable.update { it.copy(marking = readActions.any { action -> action.first == user }) }
            }
        }
    }
}
data class InboxState(val owner: String? = null, val items: List<ReplyNotice> = emptyList(), val unread: Long = 0,
    val hasMore: Boolean = false, val loading: Boolean = false, val loaded: Boolean = false, val marking: Boolean = false, val error: String? = null)
