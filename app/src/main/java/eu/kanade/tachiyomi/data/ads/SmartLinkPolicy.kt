package eu.kanade.tachiyomi.data.ads

import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** No impression, view, conversion or reward claim is inferred from these local events. */
object SmartLinkConfiguration {
    const val ID = 31593088L
    const val URL = AdsterraSmartLinkConfig.SMARTLINK_URL
    const val APPROVAL_REFERENCE = "481902"
    const val APPROVAL_PROVENANCE = "PUBLISHER_REPORTED_ACCOUNT_SPECIFIC_EXCEPTION_NOT_INDEPENDENTLY_VERIFIED"
}

internal fun adHash(vararg values: String): String {
    val hash = MessageDigest.getInstance("SHA-256")
    values.forEach { value -> val bytes=value.toByteArray(); hash.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array()); hash.update(bytes) }
    return hash.digest().joinToString("") { "%02x".format(it) }
}

/** Exact identities only; ordering and repeated selection never increase the required count. */
data class DownloadAdRequest(val chapterKeys: List<String>) {
    val unique: List<String> get() = chapterKeys.filter { it.isNotBlank() }.distinct().sorted()
    companion object {
        fun manga(chapters: List<tachiyomi.domain.chapter.model.Chapter>) = DownloadAdRequest(chapters.map { "manga:${it.mangaId}:${it.id}" })
        fun novel(edition: String, chapters: List<String>) = DownloadAdRequest(chapters.map { adHash("novel",edition,it) })
    }
}

@Serializable internal data class SmartLinkSettings(
    val chaptersPerAction: Int = 25, val minActions: Int = 1, val maxActions: Int = 6,
    val chapterInterval: Int = 20, val minimumLaunchGapMs: Long = 3_000,
    val maxLaunchesPerHour: Int = 24,
) {
    init { require(chaptersPerAction>0 && minActions in 1..6 && maxActions in minActions..6 && chapterInterval>0 && minimumLaunchGapMs>=0 && maxLaunchesPerHour>0) }
    fun required(count: Int): Int { require(count>0); return ((count.toLong()+chaptersPerAction-1)/chaptersPerAction).toInt().coerceIn(minActions,maxActions) }
}
@Serializable internal data class UnlockBatch(val id: String, val owner: String, val count: Int, val required: Int,
    val returnedActions: Int = 0, val fallback: String? = null,
    val chapterTokens: Set<String> = emptySet(), val reusedUnlock: Boolean = false) {
    val unlocked get() = returnedActions>=required || reusedUnlock
}
@Serializable internal data class ChapterAdvertising(val observed: Map<String,Int> = emptyMap(), val count: Long = 0,
    val boundary: String? = null, val lastCompleted: String? = null, val dismissed: Boolean = false, val servicedCount: Long? = null)
@Serializable internal data class BrowserNavigation(val token: String, val owner: String, val target: String,
    val created: Long, val accepted: Boolean = false, val away: Boolean = false, val returned: Boolean = false)
/** Local navigation intent, not chapter content. Applied only by its original account/work. */
@Serializable internal data class ReaderAdTransition(val id: String, val owner: String, val kind: String,
    val work: String, val from: String?, val destination: String, val payload: String? = null,
    val satisfied: Boolean = false, val chapterPage: Boolean = false)
/** Already resolved destination metadata, never fetched chapter content. */
internal data class ReaderEndDestination(val work: String, val from: String, val destination: String, val payload: String? = null)
@Serializable internal data class SmartLinkState(val schema: Int = 1, val batches: Map<String,UnlockBatch> = emptyMap(),
    val chapters: Map<String,ChapterAdvertising> = emptyMap(), val pending: BrowserNavigation? = null,
    val launches: List<Long> = emptyList(), val grantedChapters: Set<String> = emptySet(),
    val readerTransitions: Map<String, ReaderAdTransition> = emptyMap(),
    val grantedReaderPages: Set<String> = emptySet())

/** Mutate a candidate then durably save BEFORE publishing it. Controller serializes this machine. */
internal class SmartLinkPolicy(var state: SmartLinkState = SmartLinkState(), val settings: SmartLinkSettings = SmartLinkSettings()) {
    init {
        // Legacy error/privacy fallback is not proof of earned steps. Preserve genuine grants,
        // counters and all download files; only stop incomplete fallback grants authorizing new work.
        val invalid = state.batches.values.filter { it.fallback != null && it.returnedActions < it.required }.flatMap { it.chapterTokens }.toSet()
        val genuine = state.batches.values.filter { it.returnedActions >= it.required }.flatMap { it.chapterTokens }.toSet()
        val grants = (state.grantedChapters - invalid) + genuine
        state = state.copy(grantedChapters = grants, batches = state.batches.mapValues { (_, row) ->
            row.copy(reusedUnlock = row.reusedUnlock && grants.containsAll(row.chapterTokens) && row.chapterTokens.isNotEmpty())
        })
    }
    private fun serviced(data: ChapterAdvertising): Long = data.servicedCount ?: if (data.dismissed)
        data.count / settings.chapterInterval * settings.chapterInterval else 0L
    fun dueBoundary(owner: String, kind: String): String? {
        val key = adHash(owner, kind); val data = state.chapters[key] ?: return null
        return if (data.count - serviced(data) >= settings.chapterInterval)
            "boundary:$key:${serviced(data) + settings.chapterInterval}" else null
    }
    /** A virtual page between actual edition index blocks, not a global completion obligation. */
    fun page(owner: String, kind: String, work: String, from: String?, destination: String, payload: String?,
        boundary: ReaderAdPageBoundary?): ReaderAdTransition? {
        require(kind in setOf("novel", "manga") && work.isNotBlank() && destination.isNotBlank())
        val key = adHash(owner, kind)
        val prior = state.readerTransitions[key]
        if (boundary == null) return null
        val id = "reader-page:" + adHash(owner, kind, work, boundary.before, boundary.after)
        if (prior?.chapterPage == true && prior.id == id && prior.work == work && prior.destination == destination) return prior
        // Preserve an already-earned legacy step for this exact destination, without global debt.
        if (prior?.chapterPage == false && prior.satisfied && prior.work == work && prior.destination == destination) {
            state = state.copy(grantedReaderPages = state.grantedReaderPages + id,
                readerTransitions = state.readerTransitions - key)
            return null
        }
        if (id in state.grantedReaderPages) {
            if (prior?.id == id) state = state.copy(readerTransitions = state.readerTransitions - key)
            return null
        }
        val next = ReaderAdTransition(id, owner, kind, work, from, destination, payload, chapterPage = true)
        state = state.copy(readerTransitions = state.readerTransitions + (key to next),
            pending = state.pending?.takeUnless { it.owner == owner && it.target == prior?.id && prior?.id != id })
        return next
    }
    fun pageRoute(owner: String, kind: String, work: String, from: String?, destination: String, payload: String?,
        boundaries: List<ReaderAdPageBoundary>): ReaderAdTransition? {
        for (boundary in boundaries) {
            val id = "reader-page:" + adHash(owner, kind, work, boundary.before, boundary.after)
            if (id in state.grantedReaderPages) continue
            page(owner,kind,work,from,destination,payload,boundary)?.let { return it }
        }
        return null
    }
    fun transition(owner: String, kind: String, work: String, from: String?, destination: String, payload: String?): ReaderAdTransition? {
        require(kind in setOf("novel", "manga") && work.isNotBlank() && (from==null || from.isNotBlank()) && destination.isNotBlank())
        if (from == destination) return null
        val key = adHash(owner, kind)
        state.readerTransitions[key]?.takeIf { it.work == work && it.destination == destination }?.let { return it }
        // A deliberate different selection supersedes the old intent, never replays it later.
        val prior=state.readerTransitions[key]
        if(prior!=null) state=state.copy(readerTransitions=state.readerTransitions-key,
            pending=state.pending?.takeUnless {it.owner==owner && it.target==prior.id})
        val boundary = dueBoundary(owner, kind) ?: return null
        return ReaderAdTransition(boundary, owner, kind, work, from, destination, payload).also {
            state = state.copy(readerTransitions = state.readerTransitions + (key to it))
        }
    }
    fun entry(owner: String, kind: String, work: String, destination: String, payload: String?, chapterKey: String, previouslyRead: Boolean): ReaderAdTransition? {
        state.readerTransitions[adHash(owner,kind)]?.takeIf {
            it.work==work && it.from==destination && !it.satisfied
        }?.let {return it}
        // Opening an old completed chapter is not a new opportunity; a genuinely new entry
        // cannot bypass an already-due interval by leaving and reopening the reader.
        if(previouslyRead || state.chapters[adHash(owner,kind)]?.observed?.get(chapterKey)==2) return null
        return transition(owner,kind,work,null,destination,payload)
    }
    fun applied(owner: String, kind: String, work: String, destination: String) {
        val key = adHash(owner, kind)
        if (state.readerTransitions[key]?.let { it.owner == owner && it.work == work && it.destination == destination && it.satisfied } == true)
            state = state.copy(readerTransitions = state.readerTransitions - key)
    }
    fun batch(owner: String, request: DownloadAdRequest): UnlockBatch {
        val ids=request.unique; require(ids.isNotEmpty())
        val id=adHash(owner,"download",*ids.toTypedArray())
        val tokens=ids.mapTo(linkedSetOf()) {adHash(owner,it)}
        val covered=state.grantedChapters.containsAll(tokens)
        state.batches[id]?.let {
            check(it.owner==owner)
            val restored=if(covered && !it.unlocked) it.copy(reusedUnlock=true) else it
            if(restored!=it) state=state.copy(batches=state.batches+(id to restored))
            return restored
        }
        val notGranted=tokens.count {it !in state.grantedChapters}
        return UnlockBatch(id,owner,ids.size,settings.required(notGranted.coerceAtLeast(1)),chapterTokens=tokens,reusedUnlock=covered)
            .also { state=state.copy(batches=state.batches+(id to it)) }
    }
    fun observe(owner: String, kind: String, chapter: String, previouslyRead: Boolean) {
        val key=adHash(owner,kind); val data=state.chapters[key] ?: ChapterAdvertising()
        if (chapter in data.observed && !(data.observed[chapter] == 0 && !previouslyRead)) return
        state=state.copy(chapters=state.chapters+(key to data.copy(observed=data.observed+(chapter to if(previouslyRead) 0 else 1))))
    }
    fun complete(owner: String, kind: String, chapter: String) {
        val key=adHash(owner,kind); val data=state.chapters[key] ?: return
        if (data.observed[chapter]!=1) return
        val count=data.count+1
        state=state.copy(chapters=state.chapters+(key to data.copy(observed=data.observed+(chapter to 2),count=count,
            lastCompleted=chapter,boundary=if(count-serviced(data)>=settings.chapterInterval) data.boundary ?: chapter else null,
            servicedCount=serviced(data),dismissed=false)))
    }
    /** Journal writes completion and its due navigation obligation in the same commit. */
    fun completeAtEnd(owner: String, kind: String, chapter: String, previouslyRead: Boolean, end: ReaderEndDestination) {
        observe(owner,kind,chapter,previouslyRead)
        complete(owner,kind,chapter)
        val due=dueBoundary(owner,kind) ?: return
        if(state.readerTransitions[adHash(owner,kind)]?.id==due) return
        // At a final indexed chapter the safe boundary may stay on that chapter.
        transition(owner,kind,end.work,end.from.takeUnless {it==end.destination},end.destination,end.payload)
    }
    fun boundary(owner: String, kind: String, chapter: String): String? =
        dueBoundary(owner,kind)?.takeIf { state.chapters[adHash(owner,kind)]?.lastCompleted == chapter }
    /** Leaving a boundary cancels its browser intent, never earns or consumes a step. */
    fun dismiss(target: String, owner: String) {
        if (listOf("novel", "manga").any { dueBoundary(owner,it)==target })
            state=state.copy(pending=state.pending?.takeUnless {it.owner==owner && it.target==target})
    }
    private fun satisfy(target: String, owner: String) {
        val page = state.readerTransitions.values.firstOrNull {
            it.owner == owner && it.id == target && it.chapterPage && !it.satisfied
        }
        if (page != null) {
            state = state.copy(grantedReaderPages = state.grantedReaderPages + target,
                readerTransitions = state.readerTransitions + (adHash(owner, page.kind) to page.copy(satisfied = true)))
            return
        }
        for (kind in listOf("novel", "manga")) {
            val key=adHash(owner,kind);val data=state.chapters[key] ?: continue
            if (dueBoundary(owner,kind)==target) state=state.copy(
                chapters=state.chapters+(key to data.copy(servicedCount=serviced(data)+settings.chapterInterval,dismissed=false,boundary=null)),
                readerTransitions=state.readerTransitions.mapValues { (_, transition) ->
                    if (transition.owner==owner && transition.id==target) transition.copy(satisfied=true) else transition
                })
        }
    }
    fun canLaunch(owner: String, target: String, now: Long): String? {
        if(state.pending!=null) return "يوجد رابط إعلاني مفتوح بالفعل"
        val batch=state.batches[target]
        if(batch!=null && (batch.owner!=owner || batch.unlocked)) return "الدفعة غير مؤهلة لخطوة أخرى"
        val readerPage = state.readerTransitions.values.any { it.owner == owner && it.id == target && it.chapterPage && !it.satisfied }
        if(batch==null && !readerPage && listOf("novel","manga").none { dueBoundary(owner,it)==target }) return "انتهت فرصة هذا الفاصل"
        val last=state.launches.lastOrNull()
        if(last!=null && (now<last || now-last<settings.minimumLaunchGapMs)) return "انتظر لحظة قبل فتح رابط إعلاني آخر"
        // No hourly/daily quota: retain only the short duplicate-launch guard above.
        return null
    }
    fun prepare(owner: String, target: String, token: String, now: Long) {
        check(canLaunch(owner,target,now)==null)
        state=state.copy(pending=BrowserNavigation(token,owner,target,now))
    }
    fun accepted(token: String, now: Long) {
        val p=state.pending?.takeIf { it.token==token && !it.accepted } ?: return
        state=state.copy(pending=p.copy(accepted=true),launches=(state.launches.filter { now-it in 0 until 3_600_000 }+now).takeLast(settings.maxLaunchesPerHour))
        settle()
    }
    fun away(token: String) { state.pending?.takeIf {it.token==token}?.let {state=state.copy(pending=it.copy(away=true))}; settle() }
    fun returned(owner: String, now: Long) {
        val p=state.pending ?: return
        if(p.owner!=owner || now<p.created || now-p.created>15*60_000) { state=state.copy(pending=null); return }
        if(!p.away) return
        state=state.copy(pending=p.copy(returned=true));settle()
    }
    /** Called only after the initiating Activity actually paused for an accepted external launch.
     * Reconcile the pair atomically if asynchronous departure persistence arrived late.
     */
    fun browserReturned(owner: String, token: String, now: Long) {
        val pending = state.pending?.takeIf { it.owner == owner && it.token == token } ?: return
        if (now < pending.created || now - pending.created > 15 * 60_000) { returned(owner, now); return }
        away(token)
        returned(owner, now)
    }
    private fun settle() {
        val p=state.pending?.takeIf {it.accepted && it.away && it.returned} ?: return
        val b=state.batches[p.target]
        if(b!=null && b.owner==p.owner && !b.unlocked) {
            val next=b.copy(returnedActions=(b.returnedActions+1).coerceAtMost(b.required))
            state=state.copy(batches=state.batches+(b.id to next),grantedChapters=if(next.unlocked) state.grantedChapters+next.chapterTokens else state.grantedChapters)
        }
        else satisfy(p.target,p.owner)
        state=state.copy(pending=null)
    }
    fun cancel(token: String? = state.pending?.token) { if(state.pending?.token==token) state=state.copy(pending=null) }
    fun fallback(owner: String, target: String, reason: String) {
        require(reason in setOf("OFFLINE","NO_BROWSER","LAUNCH_FAILED","USER_REPORTED_UNAVAILABLE","PRIVACY_DISABLED","DEVICE_LIMIT"))
        val b=state.batches[target]?.takeIf {it.owner==owner} ?: return
        state=state.copy(batches=state.batches+(target to b.copy(fallback=reason)),pending=state.pending?.takeUnless {it.owner==owner && it.target==target})
    }
}

/** One transactional ad journal: failed durable writes never publish an earned step. */
internal class SmartLinkJournal(private val read: () -> SmartLinkState, private val write: (SmartLinkState) -> Unit) {
    private val mutex=kotlinx.coroutines.sync.Mutex()
    private var loaded=false
    private val mutableState=kotlinx.coroutines.flow.MutableStateFlow(SmartLinkState())
    val state: kotlinx.coroutines.flow.StateFlow<SmartLinkState> = mutableState
    suspend fun <T> edit(change: (SmartLinkPolicy)->T): T = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        mutex.lock()
        try {
            if(!loaded) {
                val stored=read();check(stored.schema==1)
                val restored=SmartLinkPolicy(stored).state
                if(restored!=stored) write(restored)
                mutableState.value=restored;loaded=true
            }
            val before=mutableState.value
            val machine=SmartLinkPolicy(before)
            val result=change(machine)
            if(machine.state!=before) {write(machine.state);mutableState.value=machine.state}
            result
        } finally {mutex.unlock()}
    }
}
