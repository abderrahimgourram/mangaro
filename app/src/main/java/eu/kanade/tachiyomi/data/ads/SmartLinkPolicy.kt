package eu.kanade.tachiyomi.data.ads

import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** No impression, view, conversion or reward claim is inferred from these local events. */
object SmartLinkConfiguration {
    const val ID = 31593088L
    const val URL = "https://asiafilm.org/4/71f2337020da186c3958a071da292bec"
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
    val unlocked get() = returnedActions>=required || fallback!=null || reusedUnlock
}
@Serializable internal data class ChapterAdvertising(val observed: Map<String,Int> = emptyMap(), val count: Long = 0,
    val boundary: String? = null, val lastCompleted: String? = null, val dismissed: Boolean = false)
@Serializable internal data class BrowserNavigation(val token: String, val owner: String, val target: String,
    val created: Long, val accepted: Boolean = false, val away: Boolean = false, val returned: Boolean = false)
@Serializable internal data class SmartLinkState(val schema: Int = 1, val batches: Map<String,UnlockBatch> = emptyMap(),
    val chapters: Map<String,ChapterAdvertising> = emptyMap(), val pending: BrowserNavigation? = null,
    val launches: List<Long> = emptyList(), val grantedChapters: Set<String> = emptySet())

/** Mutate a candidate then durably save BEFORE publishing it. Controller serializes this machine. */
internal class SmartLinkPolicy(var state: SmartLinkState = SmartLinkState(), val settings: SmartLinkSettings = SmartLinkSettings()) {
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
        if (chapter in data.observed) return
        state=state.copy(chapters=state.chapters+(key to data.copy(observed=data.observed+(chapter to if(previouslyRead) 0 else 1))))
    }
    fun complete(owner: String, kind: String, chapter: String) {
        val key=adHash(owner,kind); val data=state.chapters[key] ?: return
        if (data.observed[chapter]!=1) return
        val count=data.count+1
        state=state.copy(chapters=state.chapters+(key to data.copy(observed=data.observed+(chapter to 2),count=count,
            lastCompleted=chapter,boundary=if(count%settings.chapterInterval==0L) chapter else null,dismissed=false)))
    }
    fun boundary(owner: String, kind: String, chapter: String): String? {
        val key=adHash(owner,kind); val data=state.chapters[key] ?: return null
        return if(data.boundary==chapter && data.lastCompleted==chapter && !data.dismissed) "boundary:$key:$chapter" else null
    }
    fun dismiss(target: String, owner: String) {
        state.chapters.entries.firstOrNull { (key,data) -> target=="boundary:$key:${data.boundary}" && key in listOf(adHash(owner,"novel"),adHash(owner,"manga")) }?.let { (key,data) ->
            state=state.copy(chapters=state.chapters+(key to data.copy(dismissed=true)),
                pending=state.pending?.takeUnless {it.owner==owner && it.target==target})
        }
    }
    fun canLaunch(owner: String, target: String, now: Long): String? {
        if(state.pending!=null) return "يوجد رابط إعلاني مفتوح بالفعل"
        val batch=state.batches[target]
        if(batch!=null && (batch.owner!=owner || batch.unlocked)) return "الدفعة غير مؤهلة لخطوة أخرى"
        if(batch==null && !state.chapters.any { (key,data) -> target=="boundary:$key:${data.boundary}" && !data.dismissed && key in listOf(adHash(owner,"novel"),adHash(owner,"manga")) }) return "انتهت فرصة هذا الفاصل"
        val last=state.launches.lastOrNull()
        if(last!=null && (now<last || now-last<settings.minimumLaunchGapMs)) return "انتظر لحظة قبل فتح رابط إعلاني آخر"
        if(state.launches.count { now-it in 0 until 3_600_000 }>=settings.maxLaunchesPerHour) return "بلغت حد الروابط الإعلانية لهذا الجهاز؛ يمكنك المتابعة دون إعلان"
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
    private fun settle() {
        val p=state.pending?.takeIf {it.accepted && it.away && it.returned} ?: return
        val b=state.batches[p.target]
        if(b!=null && b.owner==p.owner && !b.unlocked) {
            val next=b.copy(returnedActions=(b.returnedActions+1).coerceAtMost(b.required))
            state=state.copy(batches=state.batches+(b.id to next),grantedChapters=if(next.unlocked) state.grantedChapters+next.chapterTokens else state.grantedChapters)
        }
        else dismiss(p.target,p.owner)
        state=state.copy(pending=null)
    }
    fun cancel(token: String? = state.pending?.token) { if(state.pending?.token==token) state=state.copy(pending=null) }
    fun fallback(owner: String, target: String, reason: String) {
        require(reason in setOf("OFFLINE","NO_BROWSER","LAUNCH_FAILED","USER_REPORTED_UNAVAILABLE","PRIVACY_DISABLED","DEVICE_LIMIT"))
        val b=state.batches[target]?.takeIf {it.owner==owner} ?: return
        state=state.copy(batches=state.batches+(target to b.copy(fallback=reason)),grantedChapters=state.grantedChapters+b.chapterTokens,pending=state.pending?.takeUnless {it.owner==owner && it.target==target})
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
            if(!loaded) { val restored=read();check(restored.schema==1);mutableState.value=restored;loaded=true }
            val before=mutableState.value
            val machine=SmartLinkPolicy(before)
            val result=change(machine)
            if(machine.state!=before) {write(machine.state);mutableState.value=machine.state}
            result
        } finally {mutex.unlock()}
    }
}
