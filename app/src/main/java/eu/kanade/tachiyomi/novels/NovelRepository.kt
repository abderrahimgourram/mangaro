package eu.kanade.tachiyomi.novels

import android.content.Context
import android.util.AtomicFile
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Novel-only identity, library, progress, indexes and offline storage. Initialized only on demand. */
class NovelRepository private constructor(context: Context) {
    private val app = context.applicationContext
    private val mutableStorageError = MutableStateFlow<String?>(null)
    val storageError = mutableStorageError.asStateFlow()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
        android.util.Log.w("MangaroNovels", "Local persistence failed", error)
        mutableStorageError.value = "تعذّر حفظ تغييرات القراءة. حاول مجددًا."
    })
    private val lock = Mutex()
    private val catalogLock = Mutex()
    private data class RequestLock(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val requestLocks = mutableMapOf<String, RequestLock>()
    private suspend fun <T> coalesce(key: String, action: suspend () -> T): T {
        val entry = synchronized(requestLocks) { requestLocks.getOrPut(key) { RequestLock() }.also { it.users++ } }
        try { return entry.mutex.withLock { action() } }
        finally { synchronized(requestLocks) { if (--entry.users == 0) requestLocks.remove(key) } }
    }
    private val detailCache = LinkedHashMap<String, Pair<Novel, Long>>(32, .75f, true)
    private val indexLocks = ConcurrentHashMap<String, Mutex>()
    private val settingsSequence = java.util.concurrent.atomic.AtomicLong()
    private val positionSequence = java.util.concurrent.atomic.AtomicLong()
    private val appliedPositionSequence = mutableMapOf<String, Long>()
    private val appliedChapterSequence = mutableMapOf<Pair<String, String>, Long>()
    private val pendingPositions = LinkedHashMap<Pair<String, String>, Pair<Long, NovelReadingPosition>>()
    private val json = Json { ignoreUnknownKeys = true }
    private val file = AtomicFile(File(app.filesDir, "novels-local/library.json"))
    private val settingsFile = AtomicFile(File(app.filesDir, "novels-local/reader-settings.json"))
    private val metadata = NovelMetadataStore(File(app.filesDir, "novels-local"))
    private val indexCache = NovelMetadataStore(File(app.cacheDir, "novels-indexes"))
    val disk by lazy { NovelDownloadDisk(File(app.filesDir, "novels-local/text-downloads")) }
    private val downloadQueue = lazy { NovelDownloadQueue(app, this, disk) }
    val downloads by downloadQueue
    private val ready = CompletableDeferred<Unit>()
    private val mutableRestored = MutableStateFlow(false)
    val restored = mutableRestored.asStateFlow()
    @Volatile private var libraryReadable = true
    private val mutableLibrary = MutableStateFlow<List<NovelLibraryItem>>(emptyList())
    private val mutableSettings = MutableStateFlow(NovelReaderSettings())
    private val mutableCatalog = MutableStateFlow(NovelCatalogState())
    val library = mutableLibrary.asStateFlow()
    val settings = mutableSettings.asStateFlow()
    val catalog = mutableCatalog.asStateFlow()
    val unifiedLibrary = combine(library, catalog) { entries, state ->
        entries.groupBy { state.work(it.novel.id)?.id ?: NovelIdentity.initialWorkId(it.novel.id) }.mapNotNull { (id, items) ->
            if (items.none { it.saved || it.favorite }) return@mapNotNull null
            val work = state.work(id) ?: UnifiedNovelWork(id, items.map { it.novel }, items.first().novel.id)
            UnifiedNovelLibraryItem(work, items)
        }.sortedByDescending { it.latest?.position?.updatedAt ?: 0 }
    }.flowOn(Dispatchers.Default).stateIn(io, SharingStarted.WhileSubscribed(5000), emptyList())

    val unifiedHistory = combine(library, catalog) { entries, state ->
        entries.filter { it.position != null }.groupBy { state.work(it.novel.id)?.id ?: NovelIdentity.initialWorkId(it.novel.id) }
            .map { (id, items) -> UnifiedNovelLibraryItem(state.work(id) ?: UnifiedNovelWork(id, items.map { it.novel }, items.first().novel.id), items) }
            .sortedByDescending { it.latest?.position?.updatedAt ?: 0 }
    }.flowOn(Dispatchers.Default).stateIn(io, SharingStarted.WhileSubscribed(5000), emptyList())
    fun refreshRulesForeground() { io.launch { NovelRuleUpdateJob.schedule(app) } }

    val covers by lazy {
        val dispatcher = okhttp3.Dispatcher().apply { maxRequests = 4; maxRequestsPerHost = 2 }
        val client = OkHttpClient.Builder().cookieJar(okhttp3.CookieJar.NO_COOKIES).dispatcher(dispatcher)
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS).callTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor { chain ->
                if (!NovelHttp.allowed(chain.request().url.toString())) throw java.io.IOException("Unapproved novel cover host")
                chain.proceed(chain.request())
            }.build()
        ImageLoader.Builder(app).components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .memoryCache { MemoryCache.Builder().maxSizeBytes(8L * 1024 * 1024).build() }
            .diskCache { DiskCache.Builder().directory(File(app.cacheDir, "novels-covers").toOkioPath()).maxSizeBytes(16L * 1024 * 1024).build() }.build()
    }
    val sources by lazy {
        val http = NovelHttp()
        listOf(KolNovelSource(http), CeneleSource(http), SunovelsSource(http), SeaNovelSource(http))
    }
    private val rules by lazy { NovelRuleStore(app) }
    private val indexRevision = MutableStateFlow(0L)
    private val indexes = LinkedHashMap<String, NovelChapterIndex>()
    private val texts = object : LinkedHashMap<String, NovelText>(4, .75f, true) {}
    private data class ChapterFlight(val result: Deferred<NovelText>, var readers: Int = 1)
    private val chapterFlights = mutableMapOf<Pair<String, String>, ChapterFlight>()
    @kotlinx.serialization.Serializable private data class DiscoveryCache(val result: NovelPage, val at: Long)
    private val discoveryCache = LinkedHashMap<String, DiscoveryCache>(16, .75f, true)

    init {
        io.launch {
            try {
                runCatching { rules.load() }.onFailure { android.util.Log.w("MangaroNovels", "Using compiled selectors", it) }
                runCatching { NovelRuleUpdateJob.schedule(app) }.onFailure { android.util.Log.w("MangaroNovels", "Rule refresh scheduling failed", it) }
                // Restore each store independently; one corrupt optional file cannot erase another store.
                runCatching { if (file.exists()) mutableLibrary.value = json.decodeFromString<List<NovelLibraryItem>>(file.openRead().use { it.readBytes().decodeToString() }).map { if (it.favorite) it.copy(saved = true) else it } }
                    .onFailure { libraryReadable = false; mutableStorageError.value = "تعذّر استعادة مكتبة الروايات."; android.util.Log.w("MangaroNovels", "Library restore failed", it) }
                runCatching { if (settingsFile.exists()) mutableSettings.value = json.decodeFromString(settingsFile.openRead().use { it.readBytes().decodeToString() }) }
                    .onFailure { android.util.Log.w("MangaroNovels", "Reader settings restore failed", it) }
                val old = runCatching { metadata.read("catalog.json")?.let { json.decodeFromString<NovelCatalogState>(it) } }.getOrNull() ?: NovelCatalogState()
                val migrated = NovelWorkReconciler.ingest(old, mutableLibrary.value.map { it.novel }).copy(migrationVersion = 1)
                mutableCatalog.value = migrated
                metadata.write("catalog.json", json.encodeToString(migrated))
            } catch (e: Exception) { android.util.Log.w("MangaroNovels", "Local catalogue restore failed", e) }
            finally { mutableRestored.value = true; ready.complete(Unit) }
        }
    }
    fun source(id: String): NovelSource = sources.single { it.id == id }
    suspend fun awaitLocal() = ready.await()
    suspend fun ingest(novels: List<Novel>) = withContext(Dispatchers.IO) {
        ready.await()
        catalogLock.withLock {
            val reconciled = NovelWorkReconciler.ingest(mutableCatalog.value, novels)
            val protected = library.value.map { it.novel.id }.toSet() + novels.map { it.id } +
                if (downloadQueue.isInitialized()) downloads.tasks.value.map { it.novel.id }.toSet() else emptySet()
            val retained = reconciled.works.filter { w -> w.editions.any { it.id in protected } } +
                reconciled.works.filterNot { w -> w.editions.any { it.id in protected } }.takeLast(1500)
            val ids = retained.map { it.id }.toSet()
            val editionIds = retained.flatMap { it.editions }.map { it.id }.toSet()
            val state = reconciled.copy(works = retained.sortedBy { it.id },
                aliases = reconciled.aliases.filter { (_, target) -> reconciled.resolve(target) in ids },
                evidence = reconciled.evidence.filterKeys { it in editionIds })
            // Metadata registry is limited to discovered works, never a provider-wide startup scan.
            if (state != mutableCatalog.value) {
                mutableCatalog.value = state
                runCatching { metadata.write("catalog.json", json.encodeToString(state)) }
                    .onFailure { android.util.Log.w("MangaroNovels", "Catalogue cache write failed", it) }
            }
        }
    }
    suspend fun detail(novel: Novel): Novel = withContext(Dispatchers.IO) {
        coalesce("detail:" + novel.id) {
            synchronized(detailCache) { detailCache[novel.id]?.takeIf { System.nanoTime() - it.second < 600_000_000_000L } }?.first
                ?: source(novel.sourceId).details(novel).also {
                    ingest(listOf(it))
                    synchronized(detailCache) {
                        detailCache[novel.id] = it to System.nanoTime()
                        while (detailCache.size > 32) detailCache.remove(detailCache.keys.first())
                    }
                }
        }
    }
    suspend fun verifyCandidates(visible: Set<String>) = withContext(Dispatchers.Default) {
        val all = catalog.value.works.flatMap { it.editions }
        val groups = all.groupBy { NovelIdentity.titleKey(it.title) }.values.filter { group ->
            group.map { it.sourceId }.distinct().size > 1 && group.any { it.id in visible }
        }
        groups.flatten().filter { it.description.isBlank() }.distinctBy { it.id }.take(8).forEach { candidate ->
            try { detail(candidate) } catch (c: CancellationException) { throw c }
            catch (e: Exception) { android.util.Log.w("MangaroNovels", "Edition corroboration unavailable", e) }
        }
    }
    private fun discoveryKey(source: NovelSource, term: String, page: Int, genre: String?) =
        listOf(source.id, term, page.toString(), genre.orEmpty()).joinToString("|")
    suspend fun discoverySnapshot(source: NovelSource, term: String, page: Int, genre: String?): NovelPage? = withContext(Dispatchers.IO) {
        val key = discoveryKey(source, term, page, genre)
        synchronized(discoveryCache) { discoveryCache[key]?.let { return@withContext it.result } }
        runCatching { indexCache.read("discovery/" + novelDigest(key) + ".json")?.let { json.decodeFromString<DiscoveryCache>(it) } }
            .getOrNull()?.takeIf { System.currentTimeMillis() - it.at in 0..86_400_000L }?.result
    }
    suspend fun discover(source: NovelSource, term: String, page: Int, genre: String?): NovelPage = withContext(Dispatchers.IO) {
        val key = discoveryKey(source, term, page, genre)
        return@withContext coalesce("discovery:" + key) {
        synchronized(discoveryCache) { discoveryCache[key]?.takeIf { System.currentTimeMillis() - it.at < 300_000 }?.let { return@coalesce it.result } }
        val result = if (term.isNotBlank()) source.search(term, page) else source.catalog(page, latest = true, genre = genre)
        ingest(result.novels)
        synchronized(discoveryCache) {
            discoveryCache[key] = DiscoveryCache(result, System.currentTimeMillis())
            while (discoveryCache.size > 16) discoveryCache.remove(discoveryCache.keys.first())
        }
        io.launch {
            runCatching {
                indexCache.write("discovery/" + novelDigest(key) + ".json", json.encodeToString(DiscoveryCache(result, System.currentTimeMillis())))
                val directory = File(app.cacheDir, "novels-indexes/discovery")
                val files = directory.listFiles().orEmpty().sortedByDescending { it.lastModified() }
                var bytes = 0L
                files.forEachIndexed { i, file -> bytes += file.length(); if (i >= 32 || bytes > 16L * 1024 * 1024) file.delete() }
            }.onFailure { android.util.Log.w("MangaroNovels", "Discovery cache write failed", it) }
        }
        result
        }
    }
    suspend fun setSaved(novel: Novel, saved: Boolean) {
        try {
            ingest(listOf(novel))
            change {
                val work = catalog.value.work(novel.id)
                val ids = work?.editions?.map { it.id }?.toSet() ?: setOf(novel.id)
                val existing = firstOrNull { it.novel.id == novel.id }
                val updated = map { if (it.novel.id in ids) it.copy(saved = saved, favorite = if (saved) it.favorite else false, addedAt = if (saved && it.addedAt == 0L) System.currentTimeMillis() else it.addedAt) else it }
                if (updated.any { it.novel.id == novel.id }) updated
                else updated + NovelLibraryItem(novel, saved, existing?.position, addedAt = if (saved) System.currentTimeMillis() else 0)
            }
            if (saved) withContext(Dispatchers.IO) { runCatching { indexSnapshot(novel)?.let { metadata.write(indexPath(novel), json.encodeToString(it)) } }.onFailure { android.util.Log.w("MangaroNovels", "Saved index cache failed", it) } }
        } catch (c: CancellationException) { throw c }
        catch (e: Exception) { android.util.Log.w("MangaroNovels", "Library change failed", e); mutableStorageError.value = "تعذّر حفظ التغيير. حاول مجددًا." }
    }
    fun savePosition(novel: Novel, chapter: NovelChapter, paragraph: Int, offset: Int, anchor: String = "") {
        val sequence = positionSequence.incrementAndGet()
        val key = novel.id to chapter.id
        val position = NovelReadingPosition(chapter, paragraph.coerceAtLeast(0), offset.coerceAtLeast(0), System.currentTimeMillis(), anchor)
        synchronized(pendingPositions) {
            pendingPositions[key] = sequence to position
            while (pendingPositions.size > 64) pendingPositions.remove(pendingPositions.keys.first())
        }
        io.launch {
            ready.await()
            if (catalog.value.work(novel.id) == null) ingest(listOf(novel))
            change {
                if (sequence < (appliedChapterSequence[key] ?: 0L)) return@change this
                // Keep each edition's chapter position; the library still contains only its latest chapter.
                metadata.write(positionPath(novel, chapter), json.encodeToString(position))
                appliedChapterSequence[key] = sequence
                synchronized(pendingPositions) {
                    if (pendingPositions[key]?.first == sequence) pendingPositions.remove(key)
                }
                if (sequence < (appliedPositionSequence[novel.id] ?: 0L)) return@change this
                val old = firstOrNull { it.novel.id == novel.id }
                val next = filterNot { it.novel.id == novel.id } + (old ?: NovelLibraryItem(novel)).copy(novel = novel, position = position)
                appliedPositionSequence[novel.id] = sequence
                next
            }
        }
    }
    private fun positionPath(novel: Novel, chapter: NovelChapter) =
        "positions/" + novelDigest(novel.id) + "/" + novelDigest(chapter.id) + ".json"

    suspend fun readingPosition(novel: Novel, chapter: NovelChapter): NovelReadingPosition? = withContext(Dispatchers.IO) {
        ready.await()
        lock.withLock {
            // A quick cached chapter switch must see a save queued moments earlier.
            synchronized(pendingPositions) { pendingPositions[novel.id to chapter.id]?.second }
                ?: runCatching {
                    metadata.read(positionPath(novel, chapter))?.let { json.decodeFromString<NovelReadingPosition>(it) }
                        ?.takeIf { it.chapter.id == chapter.id }
                }.getOrNull()
                ?: library.value.firstOrNull { it.novel.id == novel.id }?.position?.takeIf { it.chapter.id == chapter.id }
        }
    }
    suspend fun updateLibrary(novel: Novel, favorite: Boolean? = null, status: String? = null, bookmark: String? = null) {
        require(status == null || status in setOf("reading", "completed", "planned"))
        withContext(Dispatchers.IO) {
            ingest(listOf(novel))
            change {
                val ids = catalog.value.work(novel.id)?.editions?.map { it.id }?.toSet() ?: setOf(novel.id)
                val entries = if (any { it.novel.id == novel.id }) this else this + NovelLibraryItem(novel)
                entries.map { old ->
                    if (old.novel.id !in ids) old else old.copy(
                        saved = old.saved || favorite == true || status != null,
                        addedAt = if (old.addedAt == 0L && (favorite == true || status != null)) System.currentTimeMillis() else old.addedAt,
                        favorite = favorite ?: old.favorite, readingStatus = status ?: old.readingStatus,
                        bookmarks = if (bookmark == null || old.novel.id != novel.id) old.bookmarks else
                            if (bookmark in old.bookmarks) old.bookmarks - bookmark else old.bookmarks + bookmark,
                    )
                }
            }
        }
    }

    fun saveSettings(settings: NovelReaderSettings) {
        val sequence = settingsSequence.incrementAndGet()
        mutableSettings.value = settings
        io.launch {
            ready.await()
            lock.withLock {
                if (sequence != settingsSequence.get()) return@withLock
                write(settingsFile, json.encodeToString(settings))
                if (sequence == settingsSequence.get()) mutableSettings.value = settings
            }
        }
    }
    private suspend fun change(update: List<NovelLibraryItem>.() -> List<NovelLibraryItem>) {
        ready.await()
        withContext(Dispatchers.IO) { lock.withLock {
            check(libraryReadable) { "Library restore failed; preserving the original file" }
            val next = mutableLibrary.value.update().filter { it.saved || it.position != null || it.favorite || it.bookmarks.isNotEmpty() }.sortedByDescending { it.position?.updatedAt ?: 0 }
            write(file, json.encodeToString(next)); mutableLibrary.value = next; mutableStorageError.value = null
        } }
    }
    private fun write(target: AtomicFile, value: String) {
        target.baseFile.parentFile?.mkdirs()
        val stream = target.startWrite()
        try { stream.write(value.toByteArray()); target.finishWrite(stream) }
        catch (e: Exception) { target.failWrite(stream); throw e }
    }
    private fun indexPath(novel: Novel) = "indexes/" + novelDigest(novel.id) + ".json"
    suspend fun indexSnapshot(novel: Novel): NovelChapterIndex? = withContext(Dispatchers.IO) {
        lock.withLock { indexes[novel.id] } ?: runCatching {
            (metadata.read(indexPath(novel)) ?: indexCache.read(indexPath(novel)))?.let { json.decodeFromString<NovelChapterIndex>(it) }
                ?.takeIf { it.editionId == novel.id }
        }.getOrNull()?.also { cacheIndex(it) }
    }
    private suspend fun cacheIndex(index: NovelChapterIndex) = lock.withLock {
        indexes[index.editionId] = index
        while (indexes.size > 5) indexes.remove(indexes.keys.first())
        indexRevision.update { it + 1 }
    }
    suspend fun completeIndex(novel: Novel, refresh: Boolean = false, onPage: suspend (NovelChapterIndex) -> Unit = {}): NovelChapterIndex = withContext(Dispatchers.IO) {
        ready.await()
        indexLocks.getOrPut(novel.id) { Mutex() }.withLock {
            val old = indexSnapshot(novel)
            if (!refresh && old?.complete == true && System.currentTimeMillis() - old.updatedAt < 21_600_000) {
                onPage(old)
            }
            val initial = if (!refresh && old?.complete == false) old else NovelChapterIndex(novel.id)
            try {
                val result = NovelChapterIndexer.collect(source(novel.sourceId), novel, initial ?: NovelChapterIndex(novel.id)) { index ->
                    // Keep a complete last-known-good index during refresh, including offline failures.
                    if (old?.complete != true || index.complete) {
                        cacheIndex(index)
                        if (index.complete || index.fetchedPages.size % 4 == 0) persistIndex(novel, index)
                    }
                    onPage(if (old?.complete == true && !index.complete) old else index)
                }
                updateEvidence(novel, NovelEditionEvidence(true, result.availableCount, catalog.value.evidence[novel.id]?.accessWorks, result.updatedAt))
                result
            } catch (c: CancellationException) {
                indexSnapshot(novel)?.let { withContext(NonCancellable) { persistIndex(novel, it) } }; throw c
            } catch (e: Exception) {
                indexSnapshot(novel)?.let { persistIndex(novel, it) }
                if (old?.complete == true) {
                    android.util.Log.w("MangaroNovels", "Index refresh failed; retaining verified cached chapters", e)
                    onPage(old)
                }
                throw e
            }
        }
    }
    private fun diskHasEdition(novel: Novel) = File(app.filesDir, "novels-local/text-downloads/chapters/" + novelDigest(novel.id)).isDirectory
    private fun persistIndex(novel: Novel, index: NovelChapterIndex) {
        if (library.value.any { it.novel.id == novel.id && it.saved } || diskHasEdition(novel)) metadata.write(indexPath(novel), json.encodeToString(index))
        else {
            indexCache.write(indexPath(novel), json.encodeToString(index))
            val directory = File(app.cacheDir, "novels-indexes/indexes")
            val files = directory.listFiles().orEmpty().sortedBy { it.lastModified() }
            var bytes = files.sumOf { it.length() }
            for (file in files) { if (bytes <= 16L * 1024 * 1024) break; val size = file.length(); if (file.delete()) bytes -= size }
        }
    }
    suspend fun protectOfflineIndex(novel: Novel, chapters: List<NovelChapter>) = withContext(Dispatchers.IO) {
        require(chapters.all { NovelHttp.allowed(it.url) })
        // Snapshot only. Do not wait behind a full remote index refresh or invent chapter ordering.
        val index = indexSnapshot(novel) ?: NovelChapterIndex(novel.id)
        synchronized(metadata) {
            val stored = metadata.read(indexPath(novel))?.let { json.decodeFromString<NovelChapterIndex>(it) }
            if (stored == null || stored.updatedAt <= index.updatedAt)
                metadata.write(indexPath(novel), json.encodeToString(index))
        }
    }
    suspend fun updateEvidence(novel: Novel, evidence: NovelEditionEvidence) = withContext(Dispatchers.IO) {
        ingest(listOf(novel))
        catalogLock.withLock {
            val next = NovelWorkReconciler.withEvidence(mutableCatalog.value, novel.id, evidence)
            metadata.write("catalog.json", json.encodeToString(next)); mutableCatalog.value = next
        }
    }
    suspend fun verifyEditionAccess(novel: Novel, index: NovelChapterIndex) {
        val old = catalog.value.evidence[novel.id] ?: return
        if (old.accessWorks != null && System.currentTimeMillis() - old.checkedAt < 21_600_000) return
        try {
            val samples = listOfNotNull(index.chapters.firstOrNull { it.available }, index.chapters.lastOrNull { it.available }).distinctBy { it.id }
            for (chapter in samples) chapterText(novel, chapter)
            updateEvidence(novel, old.copy(accessWorks = samples.isNotEmpty(), checkedAt = System.currentTimeMillis()))
        } catch (c: CancellationException) { throw c }
        catch (e: Exception) {
            if ((e as? NovelSourceFailure)?.httpStatus in setOf(401, 403, 404, 410))
                updateEvidence(novel, old.copy(accessWorks = false, checkedAt = System.currentTimeMillis()))
            android.util.Log.w("MangaroNovels", "Edition sample unavailable", e)
        }
    }
    suspend fun chapterPage(novel: Novel, page: Int): ChapterPage = withContext(Dispatchers.IO) { source(novel.sourceId).chapters(novel, page) }
    suspend fun adjacent(novel: Novel, chapter: NovelChapter, forward: Boolean): NovelChapter? = withContext(Dispatchers.IO) {
        fun neighbor(index: NovelChapterIndex?): NovelChapter? {
            val location = index?.chapters?.indexOfFirst { it.id == chapter.id } ?: -1
            return if (location < 0) null else index?.chapters?.getOrNull(location + if (forward) 1 else -1)
        }
        val indexMutex = indexLocks.getOrPut(novel.id) { Mutex() }
        while (true) {
            currentCoroutineContext().ensureActive()
            val revision = indexRevision.value
            val current = indexSnapshot(novel) ?: NovelChapterIndex(novel.id)
            neighbor(current)?.let { return@withContext it }
            if (current.complete) return@withContext null
            if (indexMutex.tryLock()) {
                try {
                    val index = NovelChapterIndexer.collect(source(novel.sourceId), novel, current,
                        onPage = { cacheIndex(it); persistIndex(novel, it) },
                        stopWhen = { neighbor(it) != null || (!forward && it.chapters.firstOrNull()?.id == chapter.id) })
                    return@withContext neighbor(index)
                } finally { indexMutex.unlock() }
            }
            // A concurrent index load may already have fetched the neighbor. Observe pages as they arrive,
            // instead of waiting behind every remaining page. Bounded wait also handles loader cancellation.
            withTimeoutOrNull(1000) { indexRevision.first { it != revision } }
        }
        @Suppress("UNREACHABLE_CODE") null
    }
    suspend fun chapterText(novel: Novel, chapter: NovelChapter): NovelText = withContext(Dispatchers.IO) {
        val identity = novel.id to chapter.id
        val key = novel.id + "|" + chapter.id
        lock.withLock { texts[key] }?.let { return@withContext it }
        // A text request must not wait on an unrelated catalog/detail hash collision.
        // Share the actual result with all callers, even if the small text LRU changes meanwhile.
        val flight = synchronized(chapterFlights) {
            chapterFlights[identity]?.also { it.readers++ } ?: ChapterFlight(io.async(start = CoroutineStart.LAZY) {
                lock.withLock { texts[key] }?.let { return@async it }
                val offline = disk.read(novel.id, chapter.id)
                if (offline == null && downloadQueue.isInitialized() && downloads.tasks.value.any {
                    it.novel.id == novel.id && it.chapter.id == chapter.id && it.state == NovelDownloadState.DONE
                }) downloads.invalidate(novel, chapter)
                val result = offline ?: source(novel.sourceId).chapter(chapter)
                check(result.paragraphs.size >= 3 && result.paragraphs.sumOf { it.length } >= 200) { "Incomplete novel chapter" }
                lock.withLock {
                    texts[key] = result
                    while (texts.size > 2 || texts.values.sumOf { v -> v.paragraphs.sumOf { it.length } + v.markup.sumOf { it.length } } > 1_000_000) texts.remove(texts.keys.first())
                }
                result
            }).also { chapterFlights[identity] = it }
        }
        try { flight.result.start(); flight.result.await() }
        finally {
            synchronized(chapterFlights) {
                flight.readers--
                if (flight.readers == 0) {
                    chapterFlights.remove(identity)
                    // Last caller gone: cancel the real HTTP call through the existing transport.
                    if (!flight.result.isCompleted) flight.result.cancel()
                }
            }
        }
    }
    internal suspend fun evictChapter(editionId: String, chapterId: String) {
        lock.withLock { texts.remove(editionId + "|" + chapterId) }
    }
    internal fun activeDownloads(): NovelDownloadQueue? = if (downloadQueue.isInitialized()) downloads else null
    companion object {
        @Volatile private var instance: NovelRepository? = null
        internal fun peek(): NovelRepository? = instance
        internal suspend fun recoverDownloads(context: Context): NovelDownloadQueue? = withContext(Dispatchers.IO) {
            val active = instance?.activeDownloads()
            if (active != null) active
            else if (File(context.filesDir, "novels-local/downloads/queue.db").isFile) get(context).downloads
            else null
        }
        fun get(context: Context): NovelRepository = instance ?: synchronized(this) { instance ?: NovelRepository(context).also { instance = it } }
    }
}
