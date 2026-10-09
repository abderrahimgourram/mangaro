package eu.kanade.tachiyomi.novels

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Durable states, never inferred from browser events or manga downloads. */
enum class NovelDownloadState { PENDING, RUNNING, PAUSED, DONE, FAILED, CANCELLED }
data class NovelDownloadTask(val key: String, val novel: Novel, val chapter: NovelChapter,
    val state: NovelDownloadState, val generation: Long = 0, val error: String? = null)
data class NovelDownloadSummary(val novel: Novel, val total: Int, val done: Int, val pending: Int,
    val running: Int, val paused: Int, val failed: Int, val cancelled: Int)
internal fun novelDownloadKey(editionId: String, chapterId: String) = novelDigest(editionId + "|" + chapterId)

/** A separate SQLite file. No app/manga database, queue, paths or schema is used. */
class NovelDownloadQueue(private val app: Context, private val repository: NovelRepository, private val disk: NovelDownloadDisk) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val batchMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ready = CompletableDeferred<Unit>()
    private val active = ConcurrentHashMap<String, Job>()
    private val activeSources = mutableSetOf<String>()
    private val sourceNextAt = mutableMapOf<String, Long>()
    private val sourceLastServed = mutableMapOf<String, Long>()
    private val mutableTasks = MutableStateFlow<List<NovelDownloadTask>>(emptyList())
    private val mutableError = MutableStateFlow<String?>(null)
    val tasks = mutableTasks.asStateFlow()
    val error = mutableError.asStateFlow()
    private val helper by lazy { object : SQLiteOpenHelper(app, File(app.filesDir, "novels-local/downloads/queue.db").also { it.parentFile!!.mkdirs() }.absolutePath, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            NovelQueueSql.schema.forEach { db.execSQL(it) }
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
    }
    private val db get() = helper.writableDatabase
    init {
        scope.launch {
            try {
                db.execSQL(NovelQueueSql.recover)
                publish()
                ready.complete(Unit)
                if (tasks.value.any { it.state == NovelDownloadState.PENDING }) schedule(append = false)
            } catch (e: Exception) {
                mutableError.value = "تعذّر فتح قائمة التنزيلات. حاول مجددًا."
                android.util.Log.e("MangaroNovels", "Novel queue restore failed", e)
                ready.completeExceptionally(e)
            }
        }
    }
    suspend fun awaitReady() = ready.await()
    private fun stateOf(key: String): Pair<NovelDownloadState, Long>? = db.rawQuery("SELECT state,generation FROM tasks WHERE id=?", arrayOf(key)).use { c ->
        if (c.moveToFirst()) NovelDownloadState.valueOf(c.getString(0)) to c.getLong(1) else null
    }
    suspend fun enqueue(novel: Novel, chapters: List<NovelChapter>) = withContext(Dispatchers.IO) {
        ready.await()
        mutableError.value = null
        repository.source(novel.sourceId) // approved edition only
        repository.protectOfflineIndex(novel, chapters)
        val unique = chapters.distinctBy { it.id }.filter { it.available }
        require(unique.all { NovelHttp.allowed(it.url) && java.net.URI(it.url).host == java.net.URI(novel.url).host })
        mutex.withLock {
            db.beginTransaction()
            try {
                db.execSQL("INSERT OR REPLACE INTO editions(id,source_id,novel) VALUES(?,?,?)", arrayOf(novel.id, novel.sourceId, json.encodeToString(novel)))
                for (chapter in unique) {
                    val key = novelDownloadKey(novel.id, chapter.id)
                    val old = stateOf(key)
                    if (old?.first in setOf(NovelDownloadState.PENDING, NovelDownloadState.RUNNING, NovelDownloadState.PAUSED)) continue
                    val state = if (disk.read(novel.id, chapter.id) != null) "DONE" else "PENDING"
                    db.execSQL("INSERT OR REPLACE INTO tasks(id,edition_id,chapter,state,generation,ordinal,created,error) VALUES(?,?,?,?,?,?,?,NULL)",
                        arrayOf<Any?>(key, novel.id, json.encodeToString(chapter), state, (old?.second ?: 0) + 1, chapter.order, System.currentTimeMillis()))
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            publish()
        }
        if (tasks.value.any { it.state == NovelDownloadState.PENDING }) schedule(append = true)
        eu.kanade.tachiyomi.data.cache.StorageMaintenanceJob.schedule(app)
    }
    suspend fun control(editionId: String, action: NovelDownloadState, chapterId: String? = null) = withContext(Dispatchers.IO) {
        ready.await()
        mutableError.value = null
        val cancelKeys = mutableListOf<String>()
        val suffix = if (chapterId == null) "" else " AND id=?"
        val arguments = if (chapterId == null) arrayOf(editionId) else arrayOf(editionId, novelDownloadKey(editionId, chapterId))
        mutex.withLock {
            when (action) {
                NovelDownloadState.PAUSED -> {
                    cancelKeys += tasks.value.filter { it.novel.id == editionId && (chapterId == null || it.chapter.id == chapterId) && it.state == NovelDownloadState.RUNNING }.map { it.key }
                    db.execSQL(NovelQueueSql.pause + suffix, arguments)
                }
                NovelDownloadState.CANCELLED -> {
                    cancelKeys += tasks.value.filter { it.novel.id == editionId && (chapterId == null || it.chapter.id == chapterId) && it.state == NovelDownloadState.RUNNING }.map { it.key }
                    db.execSQL(NovelQueueSql.cancel + suffix, arguments)
                }
                NovelDownloadState.PENDING -> db.execSQL(NovelQueueSql.resume + suffix, arguments)
                NovelDownloadState.FAILED -> db.execSQL(NovelQueueSql.retry + suffix, arguments)
                else -> error("Unsupported novel queue action")
            }
            publish()
        }
        cancelKeys.forEach { active[it]?.cancel() }
        if (action in setOf(NovelDownloadState.PENDING, NovelDownloadState.FAILED)) schedule(append = true)
    }
    suspend fun invalidate(novel: Novel, chapter: NovelChapter) = withContext(Dispatchers.IO) {
        ready.await(); mutex.withLock {
            db.execSQL("UPDATE tasks SET state='FAILED',error=? WHERE id=? AND state='DONE'", arrayOf("تعذّر فتح الفصل المحمّل. أعد تحميله.", novelDownloadKey(novel.id, chapter.id)))
            publish()
        }
    }
    private fun changed(key: String, state: NovelDownloadState, error: String? = null, increment: Boolean = false) {
        mutableTasks.value = mutableTasks.value.map { if (it.key == key) it.copy(state = state, error = error,
            generation = it.generation + if (increment) 1 else 0) else it }
    }
    private fun publish() {
        val editions = db.rawQuery("SELECT id,novel FROM editions", null).use { c -> buildMap {
            while (c.moveToNext()) put(c.getString(0), json.decodeFromString<Novel>(c.getString(1)))
        } }
        mutableTasks.value = db.rawQuery("SELECT id,edition_id,chapter,state,generation,error FROM tasks ORDER BY created,ordinal", null).use { c -> buildList {
            while (c.moveToNext()) {
                val novel = editions[c.getString(1)] ?: continue
                add(NovelDownloadTask(c.getString(0), novel, json.decodeFromString(c.getString(2)), NovelDownloadState.valueOf(c.getString(3)), c.getLong(4), c.getString(5)))
            }
        } }
        mutableError.value = null
    }
    fun summaries(snapshot: List<NovelDownloadTask> = tasks.value): List<NovelDownloadSummary> = snapshot.groupBy { it.novel.id }.values.map { list ->
        fun count(state: NovelDownloadState) = list.count { it.state == state }
        NovelDownloadSummary(list.first().novel, list.size, count(NovelDownloadState.DONE), count(NovelDownloadState.PENDING),
            count(NovelDownloadState.RUNNING), count(NovelDownloadState.PAUSED), count(NovelDownloadState.FAILED), count(NovelDownloadState.CANCELLED))
    }
    private suspend fun claim(): NovelDownloadTask? = mutex.withLock {
        val now = System.currentTimeMillis()
        val next = tasks.value.filter { it.state == NovelDownloadState.PENDING && it.novel.sourceId !in activeSources && now >= (sourceNextAt[it.novel.sourceId] ?: 0) }
            .minWithOrNull(compareBy<NovelDownloadTask> { sourceLastServed[it.novel.sourceId] ?: 0 }.thenBy { it.chapter.order }) ?: return@withLock null
        // Crash after a file commit but before SQLite completion: recover from the real validated file.
        if (disk.read(next.novel.id, next.chapter.id) != null) {
            db.execSQL("UPDATE tasks SET state='DONE' WHERE id=?", arrayOf(next.key)); changed(next.key, NovelDownloadState.DONE); return@withLock null
        }
        db.execSQL("UPDATE tasks SET state='RUNNING' WHERE id=? AND state='PENDING'", arrayOf(next.key))
        activeSources.add(next.novel.sourceId); sourceLastServed[next.novel.sourceId] = now
        changed(next.key, NovelDownloadState.RUNNING); next.copy(state = NovelDownloadState.RUNNING)
    }
    private suspend fun isCurrent(task: NovelDownloadTask): Boolean = mutex.withLock { stateOf(task.key) == (NovelDownloadState.RUNNING to task.generation) }
    private suspend fun complete(task: NovelDownloadTask, text: NovelText) = mutex.withLock {
        if (stateOf(task.key) != (NovelDownloadState.RUNNING to task.generation)) return@withLock
        currentCoroutineContext().ensureActive()
        // Pause/cancel cannot race the short file commit. No network is performed while holding this lock.
        val context = currentCoroutineContext()
        disk.write(task.novel.id, task.chapter.id, text) { context.isActive }
        db.execSQL("UPDATE tasks SET state='DONE',error=NULL WHERE id=?", arrayOf(task.key)); changed(task.key, NovelDownloadState.DONE)
    }
    private suspend fun failed(task: NovelDownloadTask, error: Exception) = mutex.withLock {
        if (stateOf(task.key) != (NovelDownloadState.RUNNING to task.generation)) return@withLock
        android.util.Log.w("MangaroNovels", "Novel chapter download failed", error)
        db.execSQL("UPDATE tasks SET state='FAILED',error=? WHERE id=?", arrayOf("تعذّر تحميل الفصل. حاول مجددًا.", task.key)); changed(task.key, NovelDownloadState.FAILED, "تعذّر تحميل الفصل. حاول مجددًا.")
    }
    private suspend fun release(task: NovelDownloadTask) = mutex.withLock {
        active.remove(task.key); activeSources.remove(task.novel.sourceId)
        sourceNextAt[task.novel.sourceId] = System.currentTimeMillis() + 750
        if (stateOf(task.key) == (NovelDownloadState.RUNNING to task.generation)) {
            db.execSQL("UPDATE tasks SET state='PENDING',generation=generation+1 WHERE id=?", arrayOf(task.key)); changed(task.key, NovelDownloadState.PENDING, increment = true)
        }
    }
    suspend fun runBatch(): Boolean = batchMutex.withLock { supervisorScope {
        ready.await()
        val until = System.currentTimeMillis() + 7 * 60_000
        val lanes = List(2) {
            launch(Dispatchers.IO) {
                while (isActive && System.currentTimeMillis() < until) {
                    val task = claim()
                    if (task == null) {
                        if (tasks.value.none { it.state in setOf(NovelDownloadState.PENDING, NovelDownloadState.RUNNING) }) break
                        delay(250); continue
                    }
                    supervisorScope {
                    val attempt = async {
                        val text = repository.chapterText(task.novel, task.chapter)
                        complete(task, text)
                    }
                    active[task.key] = attempt
                    if (!isCurrent(task)) attempt.cancel()
                    try { attempt.await() }
                    catch (c: CancellationException) { if (!currentCoroutineContext().isActive) throw c }
                    catch (e: Exception) { failed(task, e) }
                    finally { withContext(NonCancellable) { release(task) } }
                    }
                }
            }
        }
        lanes.joinAll()
        tasks.value.any { it.state == NovelDownloadState.PENDING }
    }
    }
    internal fun workerFailed() { mutableError.value = "تعذّر إكمال التنزيل. حاول مجددًا." }
    fun hasPending() = tasks.value.any { it.state in setOf(NovelDownloadState.PENDING, NovelDownloadState.RUNNING) }
    private fun schedule(append: Boolean) {
        try {
            eu.kanade.tachiyomi.data.download.DownloadJob.start(app, shared = true, append = append)
        } catch (e: Exception) { mutableError.value = "تعذّر بدء التنزيل. حاول مجددًا."; android.util.Log.e("MangaroNovels", "Novel worker scheduling failed", e) }
    }
}
