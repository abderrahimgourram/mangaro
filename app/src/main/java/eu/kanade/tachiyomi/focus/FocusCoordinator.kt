package eu.kanade.tachiyomi.focus

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

enum class FocusContext { IDLE, NOVEL_DETAILS, NOVEL_READER, MANGA_DETAILS, MANGA_READER, APP_BACKGROUND }
enum class WorkPriority { VISIBLE, ACTIVE_WORK, DOWNLOAD, OPTIONAL, ENRICHMENT }
enum class FocusOperation { CONTENT, METADATA, VISIBLE_IMAGE, PREFETCH }
data class FocusTarget(val context: FocusContext, val source: String? = null, val work: String? = null, val chapter: String? = null)
data class FocusSnapshot(val target: FocusTarget = FocusTarget(FocusContext.IDLE), val generation: Long = 0)
class FocusTask(val priority: WorkPriority, val source: String?, val work: String?, val chapter: String? = null,
    val operation: FocusOperation = FocusOperation.CONTENT) : AbstractCoroutineContextElement(Key) {
    @Volatile internal var obsoletePrefetch = false
    companion object Key : CoroutineContext.Key<FocusTask>
}

internal class FocusPrefetchSuperseded : Exception()

/** Admission only. Existing HTTP limits, queues, caches, retries and atomic writes remain owners.
 * No request bodies, headers, tokens, URLs or story text are retained in diagnostics.
 */
class FocusCoordinator(private val enabled: Boolean = true, private val now: () -> Long = { System.nanoTime()/1_000_000 }) {
    private val lock = Any()
    private var serial = 0L
    private val leases = LinkedHashMap<Long, FocusTarget>()
    private val current = MutableStateFlow(FocusSnapshot())
    val state = current.asStateFlow()
    private data class Ticket(val id: Long, val group: String, val task: FocusTask, val at: Long, val ready: CompletableDeferred<Unit>, var visible: Boolean = false)
    private val waiting = mutableListOf<Ticket>()
    private val active = mutableListOf<Ticket>()
    private var admitted = 0L
    private var waitMs = 0L
    private var peak = 0
    data class Metrics(val admitted: Long, val waiting: Int, val active: Int, val waitMs: Long, val peak: Int)
    fun metrics() = synchronized(lock) { Metrics(admitted, waiting.size, active.size, waitMs, peak) }
    inner class Lease internal constructor(private val id: Long) : AutoCloseable {
        fun update(target: FocusTarget) = synchronized(lock) { if (leases.containsKey(id)) { leases[id]=target; publish() } }
        override fun close() = synchronized(lock) { if(leases.remove(id)!=null) publish() }
    }
    fun enter(target: FocusTarget): Lease = synchronized(lock) {
        val id=++serial; leases[id]=target; publish(); Lease(id)
    }
    fun background(background: Boolean) = synchronized(lock) {
        // Screen leases are restored by their own lifecycle. Never cancel persistent jobs.
        appBackground=background; publish()
    }
    private var appBackground=false
    private fun publish() {
        val target = if(appBackground) FocusTarget(FocusContext.APP_BACKGROUND) else leases.values.lastOrNull() ?: FocusTarget(FocusContext.IDLE)
        if(current.value.target != target) current.value=FocusSnapshot(target,current.value.generation+1)
        drain()
    }
    private fun priority(t: Ticket): Int {
        val f=current.value.target; val same=t.task.source==f.source && t.task.work!=null && t.task.work==f.work
        if(same && t.task.chapter==f.chapter && f.chapter!=null && t.task.operation in setOf(FocusOperation.CONTENT,FocusOperation.VISIBLE_IMAGE)) return 0
        if(t.task.priority==WorkPriority.VISIBLE) {
            if(f.context in setOf(FocusContext.IDLE,FocusContext.APP_BACKGROUND) || same && (f.chapter==null || t.task.chapter==null || t.task.chapter==f.chapter)) return 0
            return 3 // An obsolete screen loses priority; only its caller may cancel it.
        }
        if(same && t.task.operation==FocusOperation.METADATA) return 1
        if(t.task.priority==WorkPriority.ACTIVE_WORK && !same && f.context!=FocusContext.IDLE) return 3
        return t.task.priority.ordinal
    }
    private fun drain() {
        while(true) {
            // A request can keep running for its durable owner after focus moves elsewhere.
            // It must release its former interactive reservation without being cancelled.
            active.forEach { it.visible = priority(it) == 0 }
            val candidates=waiting.filter { t ->
                val p=priority(t);val group=active.filter {it.group==t.group}
                val interactive=active.count {it.visible}
                val reserve=current.value.target.context !in setOf(FocusContext.IDLE,FocusContext.APP_BACKGROUND) || waiting.any {priority(it)==0} || interactive>0
                if(p==0) {
                    // Pre-existing downloads are not cancelled or counted against the reserved lane.
                    interactive<2 && group.count {it.visible}< (if(group.any {!it.visible} || waiting.any {it.group==t.group && priority(it)>0}) 1 else 2)
                } else {
                    // Keep pending bulk work here even on Home, not inside an opaque client's FIFO.
                    // Four overall/two per host match the existing novel transport ceiling; focus
                    // reduces background admission to two overall/one per host. Client limits remain.
                    if (!reserve) active.size < 4 && group.size < 2
                    else active.count {!it.visible}<2 && group.count {!it.visible}<1 && group.size<2
                }
            }
            val next=candidates.minWithOrNull(compareBy<Ticket> {
                val p=priority(it);if(p==0) 0 else (p-((now()-it.at)/750).toInt()).coerceAtLeast(1)
            }.thenBy {it.id}) ?: return
            waiting.remove(next); next.visible=priority(next)==0;active+=next
            admitted++;waitMs+=(now()-next.at).coerceAtLeast(0);peak=maxOf(peak,active.size);next.ready.complete(Unit)
        }
    }
    /** Only disposable, not-yet-started image prefetch. Never cancel a running response or a download. */
    fun discardPrefetch(task: FocusTask): Boolean = synchronized(lock) {
        if (!enabled || task.priority != WorkPriority.OPTIONAL || task.operation != FocusOperation.PREFETCH || active.any {it.task === task}) return@synchronized false
        // Also covers the tiny interval before the loader has registered the ticket.
        task.obsoletePrefetch = true
        waiting.filter {it.task === task}.forEach { waiting.remove(it); it.ready.completeExceptionally(FocusPrefetchSuperseded()) }
        drain()
        true
    }
    suspend fun <T> run(group: String, task: FocusTask, block: suspend () -> T): T {
        if(!enabled) return withContext(task) {block()}
        val ticket=synchronized(lock) {
            if (task.obsoletePrefetch) throw FocusPrefetchSuperseded()
            Ticket(++serial,group,task,now(),CompletableDeferred()).also {waiting+=it;drain()} }
        try {ticket.ready.await(); return withContext(task) {block()} }
        finally {synchronized(lock) {waiting.remove(ticket);active.remove(ticket);drain()} }
    }
}

/** One small process coordinator; no application data or persistent job ownership. */
object FocusEngine {
    private var coordinator=FocusCoordinator()
    val state get()=coordinator.state
    fun enter(target:FocusTarget)=coordinator.enter(target)
    fun background(background:Boolean)=coordinator.background(background)
    fun metrics()=coordinator.metrics()
    fun discardPrefetch(task: FocusTask)=coordinator.discardPrefetch(task)
    suspend fun <T> network(group:String, fallback:FocusTask=FocusTask(WorkPriority.OPTIONAL,null,null,operation=FocusOperation.METADATA), block:suspend()->T):T =
        coordinator.run(group,currentCoroutineContext()[FocusTask] ?: fallback,block)
    internal fun acceptancePolicy(enabled:Boolean) {
        check(eu.kanade.tachiyomi.BuildConfig.SUNOVELS_PRIVATE_ACCEPTANCE)
        check(coordinator.metrics().active==0 && coordinator.metrics().waiting==0)
        coordinator=FocusCoordinator(enabled)
    }
}
