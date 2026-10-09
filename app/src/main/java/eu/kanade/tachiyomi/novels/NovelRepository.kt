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
import kotlinx.coroutines.channels.Channel
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
    // Memory hits cannot queue behind account snapshots or AtomicFile fsync.
    private val memoryCacheLock = Mutex()
    private val catalogLock = Mutex()
    private val catalogWrites = Channel<NovelCatalogState>(Channel.CONFLATED)
    private data class MetadataFlight(val result: Deferred<Any?>, var readers: Int = 1)
    private val metadataFlights = mutableMapOf<String, MetadataFlight>()
    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> coalesce(key: String, action: suspend () -> T): T {
        val flight = synchronized(metadataFlights) {
            metadataFlights[key]?.also { it.readers++ }
                ?: MetadataFlight(io.async(start = CoroutineStart.LAZY) { action() })
                    .also { metadataFlights[key] = it }
        }
        try { return flight.result.await() as T }
        finally {
            synchronized(metadataFlights) {
                if (--flight.readers == 0) {
                    metadataFlights.remove(key, flight)
                    if (!flight.result.isCompleted) flight.result.cancel()
                }
            }
        }
    }
    private val detailCache = LinkedHashMap<String, Pair<Novel, Long>>(32, .75f, true)
    private val indexLocks = ConcurrentHashMap<String, Mutex>()
    private data class IndexFlight(val result: Deferred<NovelChapterIndex>, var readers: Int = 1, var release: Job? = null)
    private val indexFlights = mutableMapOf<String, IndexFlight>()
    private val settingsSequence = java.util.concurrent.atomic.AtomicLong()
    private val positionSequence = java.util.concurrent.atomic.AtomicLong()
    private val appliedPositionSequence = mutableMapOf<String, Long>()
    private val appliedChapterSequence = mutableMapOf<Triple<String?, String, String>, Long>()
    private val pendingPositions = LinkedHashMap<Triple<String?, String, String>, Pair<Long, NovelReadingPosition>>()
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var cloudAccount: String? = null
    private fun accountPrefix(owner: String?) = owner?.let { "accounts/" + novelDigest(it) + "/" } ?: ""
    private fun libraryFile(owner: String?) = AtomicFile(File(app.filesDir, "novels-local/" + accountPrefix(owner) + "library.json"))
    private val file get() = libraryFile(cloudAccount)
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
    private val chapterNetwork by lazy { NovelHttp() }
    internal suspend fun illustrationBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        require(NovelHttp.allowed(url))
        covers.diskCache?.openSnapshot(url)?.use { snapshot ->
            val file = snapshot.data.toFile()
            if (file.length() in 1..12L * 1024 * 1024) return@withContext file.readBytes()
        }
        chapterNetwork.imageBytes(url)
    }
    val sources by lazy {
        val http = chapterNetwork
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
            for (snapshot in catalogWrites) {
                runCatching { metadata.write("catalog.json", json.encodeToString(snapshot)) }
                    .onFailure { android.util.Log.w("MangaroNovels", "Catalogue cache write failed", it) }
            }
        }
        io.launch {
            try {
                runCatching { rules.load() }.onFailure { android.util.Log.w("MangaroNovels", "Using compiled selectors", it) }
                runCatching { NovelRuleUpdateJob.schedule(app) }.onFailure { android.util.Log.w("MangaroNovels", "Rule refresh scheduling failed", it) }
                // Restore each store independently; one corrupt optional file cannot erase another store.
                runCatching { if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) mutableLibrary.value = json.decodeFromString<List<NovelLibraryItem>>(file.openRead().use { it.readBytes().decodeToString() }).map { if (it.favorite) it.copy(saved = true) else it } }
                    .onFailure { libraryReadable = false; mutableStorageError.value = "تعذّر استعادة مكتبة الروايات."; android.util.Log.w("MangaroNovels", "Library restore failed", it) }
                runCatching { if (settingsFile.baseFile.exists() || File(settingsFile.baseFile.path + ".bak").exists()) mutableSettings.value = json.decodeFromString(settingsFile.openRead().use { it.readBytes().decodeToString() }) }
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
                // Disposable metadata persistence must not serialize independent source results.
                catalogWrites.trySend(state)
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
        groups.flatten().filter { it.description.isBlank() }.distinctBy { it.id }.take(2).forEach { candidate ->
            try { withTimeoutOrNull(8_000L) { detail(candidate) } } catch (c: CancellationException) { throw c }
            catch (e: Exception) { android.util.Log.w("MangaroNovels", "Edition corroboration unavailable", e) }
        }
    }
    private fun discoveryKey(source: NovelSource, term: String, page: Int, genre: String?) =
        listOf(source.id, term, page.toString(), genre.orEmpty()).joinToString("|")
    suspend fun discoverySnapshot(source: NovelSource, term: String, page: Int, genre: String?): NovelPage? = withContext(Dispatchers.IO) {
        val key = discoveryKey(source, term, page, genre)
        synchronized(discoveryCache) { discoveryCache[key]?.takeIf { System.currentTimeMillis() - it.at in 0..86_400_000L }?.let { return@withContext it.result } }
        val restored = runCatching { indexCache.read("discovery/" + novelDigest(key) + ".json")?.let { json.decodeFromString<DiscoveryCache>(it) } }
            .getOrNull()?.takeIf { System.currentTimeMillis() - it.at in 0..86_400_000L } ?: return@withContext null
        synchronized(discoveryCache) {
            discoveryCache[key] = restored
            while (discoveryCache.size > 16) discoveryCache.remove(discoveryCache.keys.first())
        }
        restored.result
    }
    suspend fun discover(source: NovelSource, term: String, page: Int, genre: String?): NovelPage = withContext(Dispatchers.IO) {
        val key = discoveryKey(source, term, page, genre)
        return@withContext coalesce("discovery:" + key) {
        synchronized(discoveryCache) { discoveryCache[key]?.takeIf { System.currentTimeMillis() - it.at < 300_000 }?.let { return@coalesce it.result } }
        val result = withTimeoutOrNull(30_000L) {
            if (term.isNotBlank()) source.search(term, page) else source.catalog(page, latest = true, genre = genre)
        } ?: throw NovelSourceFailure("استغرق التحميل وقتًا طويلًا. حاول مجددًا.", "Discovery deadline exceeded")
        ingest(result.novels)
        val snapshot = DiscoveryCache(result, System.currentTimeMillis())
        synchronized(discoveryCache) {
            discoveryCache[key] = snapshot
            while (discoveryCache.size > 16) discoveryCache.remove(discoveryCache.keys.first())
        }
        io.launch {
            runCatching {
                synchronized(indexCache) {
                    val path = "discovery/" + novelDigest(key) + ".json"
                    val old = indexCache.read(path)?.let { json.decodeFromString<DiscoveryCache>(it) }
                    if (old == null || old.at <= snapshot.at) indexCache.write(path, json.encodeToString(snapshot))
                    val directory = File(app.cacheDir, "novels-indexes/discovery")
                    val files = directory.listFiles().orEmpty().sortedByDescending { it.lastModified() }
                    var bytes = 0L
                    files.forEachIndexed { i, file -> bytes += file.length(); if (i >= 32 || bytes > 16L * 1024 * 1024) file.delete() }
                }
            }.onFailure { android.util.Log.w("MangaroNovels", "Discovery cache write failed", it) }
        }
        result
        }
    }
    val libraryRestoreFailed: Boolean get() = !libraryReadable
    suspend fun retryLibraryRestore() = withContext(Dispatchers.IO) {
        ready.await()
        mutableRestored.value = false
        try {
            lock.withLock {
                val restored = json.decodeFromString<List<NovelLibraryItem>>(file.openRead().use { it.readBytes().decodeToString() })
                    .map { if (it.favorite) it.copy(saved = true) else it }
                mutableLibrary.value = restored
                libraryReadable = true
                mutableStorageError.value = null
            }
            ingest(library.value.map { it.novel })
        } catch (c: CancellationException) { throw c }
        catch (e: Exception) { mutableStorageError.value = "تعذّر استعادة مكتبة الروايات."; android.util.Log.w("MangaroNovels", "Library restore retry failed", e) }
        finally { mutableRestored.value = true }
    }
    suspend fun setSaved(novel: Novel, saved: Boolean) {
        val owner = cloudAccount
        try {
            ingest(listOf(novel))
            change(owner = owner) {
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
    fun savePosition(novel: Novel, chapter: NovelChapter, paragraph: Int, offset: Int, anchor: String = "", refreshHistory: Boolean = false) {
        val owner = cloudAccount
        val sequence = positionSequence.incrementAndGet()
        val key = Triple(owner, novel.id, chapter.id)
        val position = NovelReadingPosition(chapter, paragraph.coerceAtLeast(0), offset.coerceAtLeast(0), System.currentTimeMillis(), anchor)
        synchronized(pendingPositions) {
            pendingPositions[key] = sequence to position
            while (pendingPositions.size > 64) pendingPositions.remove(pendingPositions.keys.first())
        }
        io.launch {
            ready.await()
            if (catalog.value.work(novel.id) == null) ingest(listOf(novel))
            lock.withLock {
                val target = libraryFile(owner)
                val entries = if (owner == cloudAccount) {
                    check(libraryReadable) { "Unreadable library" }; mutableLibrary.value
                } else if (target.baseFile.exists() || File(target.baseFile.path + ".bak").exists())
                    json.decodeFromString<List<NovelLibraryItem>>(target.openRead().use { it.readBytes().decodeToString() }) else emptyList()
                if (sequence < (appliedChapterSequence[key] ?: 0L)) return@withLock
                val last = entries.firstOrNull { it.novel.id == novel.id }?.position
                if (!refreshHistory && last?.chapter?.id == chapter.id && last.paragraph == position.paragraph &&
                    last.offset == position.offset && last.anchor == position.anchor) {
                    appliedChapterSequence[key] = sequence
                    synchronized(pendingPositions) { if (pendingPositions[key]?.first == sequence) pendingPositions.remove(key) }
                    return@withLock
                }
                metadata.write(positionPath(novel, chapter, owner), json.encodeToString(position))
                appliedChapterSequence[key] = sequence
                val editionKey = accountPrefix(owner) + novel.id
                if (sequence >= (appliedPositionSequence[editionKey] ?: 0L)) {
                    val old = entries.firstOrNull { it.novel.id == novel.id } ?: NovelLibraryItem(novel)
                    val next = (entries.filterNot { it.novel.id == novel.id } + old.copy(novel = novel, position = position))
                        .sortedByDescending { it.position?.updatedAt ?: 0 }
                    write(target, json.encodeToString(next))
                    if (owner == cloudAccount) mutableLibrary.value = next
                    appliedPositionSequence[editionKey] = sequence
                }
                synchronized(pendingPositions) { if (pendingPositions[key]?.first == sequence) pendingPositions.remove(key) }
                mihon.domain.account.LocalCloudChanges.changed(mihon.domain.account.LocalCloudChanges.Kind.NOVEL, owner = owner)
            }
        }
    }
    private fun positionPath(novel: Novel, chapter: NovelChapter, owner: String? = cloudAccount) =
        accountPrefix(owner) + "positions/" + novelDigest(novel.id) + "/" + novelDigest(chapter.id) + ".json"

    suspend fun readingPosition(novel: Novel, chapter: NovelChapter): NovelReadingPosition? = withContext(Dispatchers.IO) {
        ready.await()
        lock.withLock {
            // A quick cached chapter switch must see a save queued moments earlier.
            synchronized(pendingPositions) { pendingPositions[Triple(cloudAccount, novel.id, chapter.id)]?.second }
                ?: runCatching {
                    metadata.read(positionPath(novel, chapter))?.let { json.decodeFromString<NovelReadingPosition>(it) }
                        ?.takeIf { it.chapter.id == chapter.id }
                }.getOrNull()
                ?: library.value.firstOrNull { it.novel.id == novel.id }?.position?.takeIf { it.chapter.id == chapter.id }
        }
    }
    suspend fun updateLibrary(novel: Novel, favorite: Boolean? = null, status: String? = null, bookmark: String? = null, saved: Boolean? = null) {
        val owner = cloudAccount
        require(status == null || status in setOf("reading", "completed", "planned"))
        withContext(Dispatchers.IO) {
            ingest(listOf(novel))
            change(owner = owner) {
                val ids = catalog.value.work(novel.id)?.editions?.map { it.id }?.toSet() ?: setOf(novel.id)
                val entries = if (any { it.novel.id == novel.id }) this else this + NovelLibraryItem(novel)
                entries.map { old ->
                    if (old.novel.id !in ids) old else old.copy(
                        saved = saved ?: (old.saved || favorite == true || status != null),
                        addedAt = if (old.addedAt == 0L && (saved == true || (saved != false && (favorite == true || status != null)))) System.currentTimeMillis() else old.addedAt,
                        favorite = if (saved == false) false else favorite ?: old.favorite, readingStatus = status ?: old.readingStatus,
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
    private suspend fun change(owner: String? = cloudAccount, update: List<NovelLibraryItem>.() -> List<NovelLibraryItem>) {
        ready.await()
        withContext(Dispatchers.IO) { lock.withLock {
            if (owner != cloudAccount) return@withLock
            check(libraryReadable) { "Library restore failed; preserving the original file" }
            val next = mutableLibrary.value.update().filter { it.saved || it.position != null || it.favorite || it.bookmarks.isNotEmpty() }.sortedByDescending { it.position?.updatedAt ?: 0 }
            write(file, json.encodeToString(next)); mutableLibrary.value = next; mutableStorageError.value = null
            mihon.domain.account.LocalCloudChanges.changed(mihon.domain.account.LocalCloudChanges.Kind.NOVEL, owner = owner)
        } }
    }
    /** Account switching never moves/deletes the previous library or offline chapter payloads. */
    suspend fun bindCloudAccount(owner: String?, importGuest: Boolean = false) = withContext(Dispatchers.IO) {
        ready.await()
        lock.withLock {
            // Already-bound account state is authoritative in memory. Do not reread every
            // private library/history file on a foreground refresh or each sync worker.
            if (cloudAccount == owner && libraryReadable && !importGuest) return@withLock
            if (cloudAccount != owner) {
                // Hide the previous owner's view before any fallible read. Never erase its files.
                cloudAccount = owner
                mutableLibrary.value = emptyList()
                libraryReadable = false
                mutableStorageError.value = "جارٍ استعادة مكتبة الروايات"
            }
            val target = libraryFile(owner)
            val claim = AtomicFile(File(app.filesDir, "novels-local/guest-cloud-owner.json"))
            val claimed = if (claim.baseFile.exists()) json.decodeFromString<String>(claim.openRead().use { it.readBytes().decodeToString() }) else null
            val fromGuest = owner != null && importGuest && (claimed == null || claimed == owner)
            val existing = if (target.baseFile.exists() || File(target.baseFile.path + ".bak").exists())
                json.decodeFromString<List<NovelLibraryItem>>(target.openRead().use { it.readBytes().decodeToString() }) else emptyList()
            val guestFile = libraryFile(null)
            val guest = if (fromGuest && (guestFile.baseFile.exists() || File(guestFile.baseFile.path + ".bak").exists()))
                json.decodeFromString<List<NovelLibraryItem>>(guestFile.openRead().use { it.readBytes().decodeToString() }) else emptyList()
            val baselineFile = AtomicFile(File(app.filesDir, "novels-local/" + accountPrefix(owner) + "guest-baseline.json"))
            val baseline = if (baselineFile.baseFile.exists()) json.decodeFromString<List<NovelLibraryItem>>(baselineFile.openRead().use { it.readBytes().decodeToString() }) else emptyList()
            val next = if (fromGuest) {
                val byId = existing.associateBy { it.novel.id }.toMutableMap()
                val oldGuest = baseline.associateBy { it.novel.id }
                for (g in guest) {
                    val b = oldGuest[g.novel.id]
                    if (g == b) continue
                    val e = byId[g.novel.id]
                    byId[g.novel.id] = if (e == null || b == null) g else e.copy(
                        saved = if (g.saved != b.saved) g.saved else e.saved,
                        favorite = if (g.favorite != b.favorite) g.favorite else e.favorite,
                        readingStatus = if (g.readingStatus != b.readingStatus) g.readingStatus else e.readingStatus,
                        position = if (g.position != b.position) g.position else e.position,
                        bookmarks = (e.bookmarks + (g.bookmarks - b.bookmarks)) - (b.bookmarks - g.bookmarks),
                    )
                }
                for (b in baseline) if (guest.none { it.novel.id == b.novel.id }) byId[b.novel.id]?.let { byId[b.novel.id] = it.copy(saved = false, favorite = false) }
                byId.values.toList()
            } else existing
            val normalized = next.map { if (it.favorite) it.copy(saved = true) else it }
            if (fromGuest) {
                // Import only guest positions changed since the previous import; unchanged guest data cannot revert cloud progress.
                val positionBaselineName = accountPrefix(owner) + "guest-position-baseline.json"
                val positionBaseline = metadata.read(positionBaselineName)?.let { json.decodeFromString<Map<String, String>>(it) }.orEmpty()
                val newPositionBaseline = mutableMapOf<String, String>()
                val oldPositions = File(app.filesDir, "novels-local/positions")
                if (oldPositions.isDirectory) oldPositions.walkTopDown().filter { it.isFile && it.extension == "json" }.forEach { old ->
                    val relative = old.relativeTo(oldPositions).path
                    val digest = novelDigest(old.readText())
                    newPositionBaseline[relative] = digest
                    if (positionBaseline[relative] != digest) {
                        val value = metadata.read("positions/" + relative) ?: error("Unreadable guest position")
                        json.decodeFromString<NovelReadingPosition>(value)
                        metadata.write(accountPrefix(owner) + "positions/" + relative, value)
                    }
                }
                write(target, json.encodeToString(normalized))
                write(baselineFile, json.encodeToString(guest))
                metadata.write(positionBaselineName, json.encodeToString(newPositionBaseline))
                write(claim, json.encodeToString(owner!!))
            }
            cloudAccount = owner
            mutableLibrary.value = normalized
            libraryReadable = true
            mutableStorageError.value = null
        }
        ingest(library.value.map { it.novel })
    }

    @Volatile private var cloudIncomplete: Pair<String?, Set<String>> = null to emptySet()
    fun cloudIncompleteEditions(owner: String): Set<String> = cloudIncomplete.takeIf { it.first == owner }?.second.orEmpty()
    /** Metadata only. Chapter bodies, download files, reader settings and public profiles never enter sync. */
    suspend fun cloudRows(owner: String): Map<String, kotlinx.serialization.json.JsonObject> = withContext(Dispatchers.IO) {
        ready.await()
        lock.withLock {
            check(cloudAccount == owner && libraryReadable) { "Novel account is not ready" }
            val incomplete = mutableSetOf<String>()
            val rows = buildMap {
                for (item in mutableLibrary.value) try {
                    putAll(NovelCloudState.rows(item, metadataPositions(item.novel)))
                } catch (error: Exception) {
                    incomplete += novelDigest(item.novel.id)
                    // Keep the existing files and server record. Other editions can still sync.
                    android.util.Log.w("MangaroNovels", "Private metadata snapshot could not be read", error)
                }
            }
            cloudIncomplete = owner to incomplete
            rows
        }
    }
    private fun metadataPositions(novel: Novel): List<NovelReadingPosition> {
        val directory = File(app.filesDir, "novels-local/" + accountPrefix(cloudAccount) + "positions/" + novelDigest(novel.id))
        return directory.listFiles().orEmpty().filter { it.extension == "json" }.map { path ->
            json.decodeFromString<NovelReadingPosition>(metadata.read(accountPrefix(cloudAccount) + "positions/" + novelDigest(novel.id) + "/" + path.name) ?: error("Unreadable position"))
        }
    }
    suspend fun applyCloudRow(owner: String, table: String, row: kotlinx.serialization.json.JsonObject, expected: kotlinx.serialization.json.JsonObject?, force: Boolean = false, canApply: () -> Boolean = { true }): Boolean = withContext(Dispatchers.IO) {
        ready.await()
        val decoded = NovelCloudState.decode(table, row)
        lock.withLock {
            if (cloudAccount != owner || !libraryReadable || synchronized(pendingPositions) { pendingPositions.isNotEmpty() }) return@withLock false
            val existing = mutableLibrary.value.firstOrNull { it.novel.id == decoded.novel.id }
            val old = existing ?: NovelLibraryItem(decoded.novel)
            if (!force) {
                if (!canApply()) return@withLock false
                val key = table + "/" + novelDigest(decoded.novel.id) + (decoded.chapterUrl?.let { "/" + novelDigest(it) } ?: "")
                val current = existing?.let { NovelCloudState.rows(it, metadataPositions(it.novel))[key] }
                // Recheck under the SAME mutation lock; a tap/save may have raced the network response.
                val removedBaseline = expected?.get("deleted_at")?.let { it != kotlinx.serialization.json.JsonNull } == true
                if (current != expected && !(current == null && removedBaseline)) return@withLock false
            }
            val nextItem = if (table == NovelCloudState.LIBRARY) {
                if (decoded.deleted) old.copy(saved = false, favorite = false)
                else old.copy(novel = old.novel.copy(title = decoded.novel.title, cover = decoded.novel.cover), saved = decoded.saved || decoded.favorite, favorite = decoded.favorite,
                    readingStatus = decoded.status, addedAt = decoded.addedAt, position = decoded.position)
            } else {
                decoded.position?.takeUnless { decoded.deleted }?.let {
                    metadata.write(positionPath(old.novel, it.chapter), json.encodeToString(it))
                }
                old.copy(position = old.position ?: decoded.position?.takeUnless { decoded.deleted }, bookmarks = if (decoded.bookmarked && !decoded.deleted) old.bookmarks + decoded.chapterUrl!! else old.bookmarks - decoded.chapterUrl!!)
            }
            val next = (mutableLibrary.value.filterNot { it.novel.id == old.novel.id } + nextItem)
                .filter { it.saved || it.favorite || it.position != null || it.bookmarks.isNotEmpty() }
                .sortedByDescending { it.position?.updatedAt ?: 0 }
            write(file, json.encodeToString(next)); mutableLibrary.value = next
            true
        }
    }

    private fun write(target: AtomicFile, value: String) {
        target.baseFile.parentFile?.mkdirs()
        val stream = target.startWrite()
        try { stream.write(value.toByteArray()); target.finishWrite(stream) }
        catch (e: Exception) { target.failWrite(stream); throw e }
    }
    private fun indexPath(novel: Novel) = "indexes/" + novelDigest(novel.id) + ".json"
    suspend fun indexSnapshot(novel: Novel): NovelChapterIndex? = withContext(Dispatchers.IO) {
        memoryCacheLock.withLock { indexes[novel.id] } ?: runCatching {
            (metadata.read(indexPath(novel)) ?: indexCache.read(indexPath(novel)))?.let { json.decodeFromString<NovelChapterIndex>(it) }
                ?.takeIf { it.editionId == novel.id }
        }.getOrNull()?.also { cacheIndex(it) }
    }
    private suspend fun cacheIndex(index: NovelChapterIndex) = memoryCacheLock.withLock {
        indexes[index.editionId] = index
        while (indexes.size > 5) indexes.remove(indexes.keys.first())
        indexRevision.update { it + 1 }
    }
    suspend fun completeIndex(novel: Novel, refresh: Boolean = false, onPage: suspend (NovelChapterIndex) -> Unit = {}): NovelChapterIndex = withContext(Dispatchers.IO) {
        ready.await()
        // One metadata collector per edition, including quick back/forward navigation.
        val flight = synchronized(indexFlights) {
            indexFlights[novel.id]?.also { it.readers++; it.release?.cancel(); it.release = null }
                ?: IndexFlight(io.async(start = CoroutineStart.LAZY) { collectCompleteIndex(novel, refresh) })
                    .also { indexFlights[novel.id] = it }
        }
        try {
            coroutineScope {
                var lastEmission = 0L
                var lastIndex: NovelChapterIndex? = null
                val observer = launch {
                    indexRevision.onStart { emit(indexRevision.value) }.conflate().collect {
                        val index = indexSnapshot(novel) ?: return@collect
                        val now = System.nanoTime()
                        if (index != lastIndex && (lastEmission == 0L || index.complete || now - lastEmission >= 120_000_000L)) {
                            onPage(index); lastIndex = index; lastEmission = now
                        }
                    }
                }
                try {
                    flight.result.start()
                    val result = flight.result.await()
                    observer.cancelAndJoin()
                    onPage(result)
                    result
                } catch (c: CancellationException) { throw c }
                catch (e: Exception) { observer.cancelAndJoin(); indexSnapshot(novel)?.let { onPage(it) }; throw e }
                finally { observer.cancel() }
            }
        } finally {
            synchronized(indexFlights) {
                if (--flight.readers == 0) {
                    if (flight.result.isCompleted) indexFlights.remove(novel.id, flight)
                    else flight.release = io.launch {
                        // Short ownership grace keeps an in-flight page across navigation; no UI delay.
                        delay(3000)
                        synchronized(indexFlights) {
                            if (flight.readers == 0 && indexFlights[novel.id] === flight) {
                                indexFlights.remove(novel.id); flight.result.cancel()
                            }
                        }
                    }
                }
            }
        }
    }
    private suspend fun collectCompleteIndex(novel: Novel, refresh: Boolean): NovelChapterIndex = withContext(Dispatchers.IO) {
        ready.await()
        val before = indexSnapshot(novel)
        indexLocks.getOrPut(novel.id) { Mutex() }.withLock {
            val old = indexSnapshot(novel)
            // A refresh already completed while this caller waited: share it rather than refetch.
            if (old?.complete == true && ((!refresh && System.currentTimeMillis() - old.updatedAt < 21_600_000) || old.updatedAt != before?.updatedAt)) {
                return@withLock old
            }
            val initial = if (!refresh && old?.complete == false) old else NovelChapterIndex(novel.id)
            try {
                val result = NovelChapterIndexer.collect(source(novel.sourceId), novel, initial) { index ->
                    // Keep a complete last-known-good index during refresh, including offline failures.
                    if (old?.complete != true || index.complete) {
                        cacheIndex(index)
                        if (index.complete || index.fetchedPages.size % 4 == 0) persistIndex(novel, index)
                    }
                }
                updateEvidence(novel, NovelEditionEvidence(true, result.availableCount, catalog.value.evidence[novel.id]?.accessWorks, result.updatedAt))
                result
            } catch (c: CancellationException) {
                indexSnapshot(novel)?.let { withContext(NonCancellable) { persistIndex(novel, it) } }; throw c
            } catch (e: Exception) {
                indexSnapshot(novel)?.let { persistIndex(novel, it) }
                if (old?.complete == true) {
                    android.util.Log.w("MangaroNovels", "Index refresh failed; retaining verified cached chapters", e)
                }
                throw e
            }
        }
    }
    /** Extend only metadata as far as a requested range, sharing any active index collector. */
    suspend fun indexThrough(novel: Novel, count: Int): NovelChapterIndex = withContext(Dispatchers.IO) {
        require(count in 1..100_000)
        ready.await()
        val indexMutex = indexLocks.getOrPut(novel.id) { Mutex() }
        while (true) {
            currentCoroutineContext().ensureActive()
            val revision = indexRevision.value
            val snapshot = indexSnapshot(novel) ?: NovelChapterIndex(novel.id)
            if (snapshot.complete || snapshot.chapters.size >= count) return@withContext snapshot
            if (indexMutex.tryLock()) {
                try {
                    val latest = indexSnapshot(novel) ?: snapshot
                    if (latest.complete || latest.chapters.size >= count) return@withContext latest
                    val result = NovelChapterIndexer.collect(source(novel.sourceId), novel, latest,
                        stopWhen = { it.chapters.size >= count },
                        onPage = { cacheIndex(it); if (it.complete || it.fetchedPages.size % 4 == 0) persistIndex(novel, it) })
                    persistIndex(novel, result)
                    if (result.complete) updateEvidence(novel, NovelEditionEvidence(true, result.availableCount, catalog.value.evidence[novel.id]?.accessWorks, result.updatedAt))
                    return@withContext result
                } catch (c: CancellationException) {
                    withContext(NonCancellable) { indexSnapshot(novel)?.let { persistIndex(novel, it) } }; throw c
                } catch (e: Exception) {
                    indexSnapshot(novel)?.let { persistIndex(novel, it) }; throw e
                } finally { indexMutex.unlock() }
            }
            withTimeoutOrNull(1000) { indexRevision.first { it != revision } }
        }
        @Suppress("UNREACHABLE_CODE") error("No chapter index")
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
            mutableCatalog.value = next; catalogWrites.trySend(next)
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
        memoryCacheLock.withLock { texts[key] }?.let { return@withContext it }
        // A text request must not wait on an unrelated catalog/detail hash collision.
        // Share the actual result with all callers, even if the small text LRU changes meanwhile.
        val flight = synchronized(chapterFlights) {
            chapterFlights[identity]?.also { it.readers++ } ?: ChapterFlight(io.async(start = CoroutineStart.LAZY) {
                memoryCacheLock.withLock { texts[key] }?.let { return@async it }
                val offline = disk.read(novel.id, chapter.id)
                if (offline == null && downloadQueue.isInitialized() && downloads.tasks.value.any {
                    it.novel.id == novel.id && it.chapter.id == chapter.id && it.state == NovelDownloadState.DONE
                }) downloads.invalidate(novel, chapter)
                val result = offline ?: source(novel.sourceId).chapter(chapter)
                check(result.paragraphs.size >= 3 && result.paragraphs.sumOf { it.length } >= 200) { "Incomplete novel chapter" }
                memoryCacheLock.withLock {
                    texts[key] = result
                    while (texts.size > 2 || texts.values.sumOf { v -> v.paragraphs.sumOf { it.length } + v.markup.sumOf { it.length } + v.blocks.sumOf { (it.imageUrl?.length ?: 0) + it.alt.length } } > 1_000_000) texts.remove(texts.keys.first())
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
        memoryCacheLock.withLock { texts.remove(editionId + "|" + chapterId) }
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
