package eu.kanade.tachiyomi.novels

import eu.kanade.tachiyomi.focus.*

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Path.Companion.toOkioPath
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.domain.sigils.SigilEvents
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
                ?: MetadataFlight(io.async(context = tachiyomi.core.common.util.lang.LocalAccountScope.StorageElement(offlineOwner()), start = CoroutineStart.LAZY) { action() })
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
    private val downloadDisks = ConcurrentHashMap<String, NovelDownloadDisk>()
    private val downloadQueues = ConcurrentHashMap<String, NovelDownloadQueue>()
    private fun offlineOwner() = tachiyomi.core.common.util.lang.LocalAccountScope.fileOwner()
    private fun offlineRoot() = novelDownloadRoot(app.filesDir, offlineOwner())
    val disk get() = diskFor(offlineOwner())
    private fun diskFor(owner: String) = downloadDisks.computeIfAbsent(owner) { NovelDownloadDisk(File(novelDownloadRoot(app.filesDir, it), "text-downloads")) }
    val downloads get() = downloadQueues.computeIfAbsent(offlineOwner()) { NovelDownloadQueue(app, this, diskFor(it), it) }
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
                var request = chain.request().newBuilder()
                    .header("User-Agent", "MangaroNovelPrototype/0.1").header("Accept", "image/*").build()
                repeat(5) {
                    if (!NovelHttp.allowed(request.url.toString())) throw java.io.IOException("Unapproved novel cover host")
                    val response = chain.proceed(request)
                    if (response.code !in setOf(301, 302, 303, 307, 308)) return@addInterceptor response
                    val next = response.header("Location")?.let { request.url.resolve(it) }
                    response.close()
                    if (next == null || next.host != request.url.host || !NovelHttp.allowed(next.toString()))
                        throw java.io.IOException("Unapproved novel cover redirect")
                    request = request.newBuilder().url(next).build()
                }
                throw java.io.IOException("Novel cover redirect limit")
            }.build()
        ImageLoader.Builder(app).components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .memoryCache { MemoryCache.Builder().maxSizeBytes(16L * 1024 * 1024).build() }
            .diskCache { DiskCache.Builder().directory(File(app.cacheDir, "novels-covers").toOkioPath()).maxSizeBytes(64L * 1024 * 1024).build() }.build()
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
        val sun = SunovelsSource(http)
        listOf(KolNovelSource(http), CeneleSource(http),
            if (!SunovelsSource.directEnabled && SunovelsGatewayConfig.enabled) SunovelsGatewaySource(sun) else sun, SeaNovelSource(http), MknovSource(http))
    }
    val activeSources get() = sources.filter { NovelSourceAvailability.active(it.id) }
    private val rules by lazy { NovelRuleStore(app) }
    private val indexRevision = MutableStateFlow(0L)
    private val indexes = LinkedHashMap<String, NovelChapterIndex>()
    private val texts = object : LinkedHashMap<String, NovelText>(4, .75f, true) {}
    private data class ChapterFlight(val result: Deferred<NovelText>, var readers: Int = 1, val seaTrace: SeaReaderTrace? = null)
    private val chapterFlights = mutableMapOf<Triple<String, String, String>, ChapterFlight>()
    @kotlinx.serialization.Serializable private data class DiscoveryCache(val result: NovelPage, val at: Long)
    private val sunLocalSearch = SunovelsLocalSearch()
    private val sunBootstrapLock = Mutex()
    private var sunBootstrapLoaded = false
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
                val old = runCatching { metadata.read("catalog.json")?.let { SunovelsSource.cleanCatalog(json.decodeFromString<NovelCatalogState>(it)) } }.getOrNull() ?: NovelCatalogState()
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
                if (activeDownloads() != null) downloads.tasks.value.map { it.novel.id }.toSet() else emptySet()
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
    suspend fun detailSnapshot(novel: Novel): Novel? = withContext(Dispatchers.IO) {
        val path = "details/" + novelDigest(novel.id) + ".json"
        runCatching { metadata.read(path)?.let { SunovelsSource.cleanMetadata(json.decodeFromString<Novel>(it)) }?.takeIf { it.id == novel.id } }.getOrNull()
    }
    suspend fun detail(novel: Novel): Novel = withContext(Dispatchers.IO) {
        coalesce("detail:" + novel.id) {
            synchronized(detailCache) { detailCache[novel.id]?.takeIf { System.nanoTime() - it.second < 600_000_000_000L } }?.first
                ?: try {
                    withContext(FocusTask(WorkPriority.ACTIVE_WORK,novel.sourceId,novel.id,operation=FocusOperation.METADATA)) {
                        source(novel.sourceId).details(novel)
                    }.also {
                        ingest(listOf(it))
                        if (novel.sourceId == SunovelsSource.SOURCE_ID) metadata.write("details/" + novelDigest(novel.id) + ".json", json.encodeToString(it))
                        synchronized(detailCache) { detailCache[novel.id] = it to System.nanoTime(); while (detailCache.size > 32) detailCache.remove(detailCache.keys.first()) }
                    }
                } catch (c: CancellationException) { throw c }
                catch (e: Exception) {
                    if (novel.sourceId != SunovelsSource.SOURCE_ID) throw e
                    (detailSnapshot(novel) ?: catalog.value.work(novel.id)?.editions?.firstOrNull { it.id == novel.id })?.also { ingest(listOf(it)) } ?: throw e
                }
        }
    }
    private suspend fun ensureSunBootstrap() {
        if (!SunovelsSource.directEnabled) return
        ready.await()
        sunBootstrapLock.withLock {
            if (sunBootstrapLoaded) return
            // Existing observed metadata wins over a packaged historical seed.
            val known = catalog.value.works.flatMap { it.editions }.mapTo(HashSet()) { it.id }
            val rows = runCatching { SunovelsBootstrap.load(app) }.getOrDefault(emptyList()).filter { it.id !in known }
            if (rows.isNotEmpty()) ingest(rows)
            sunBootstrapLoaded = true
        }
    }
    private suspend fun sunLocalDiscovery(term: String, page: Int, genre: String?): NovelPage? {
        ensureSunBootstrap()
        return sunLocalSearch.page(catalog.value, term, page, genre)
    }
    suspend fun verifyCandidates(visible: Set<String>) = withContext(Dispatchers.Default) {
        val all = catalog.value.works.flatMap { it.editions }.filter { NovelSourceAvailability.active(it.sourceId) }
        val groups = all.groupBy { NovelIdentity.titleKey(it.title) }.values.filter { group ->
            group.map { it.sourceId }.distinct().size > 1 && group.any { it.id in visible }
        }
        groups.flatten().filter { it.description.isBlank() }.distinctBy { it.id }.take(2).forEach { candidate ->
            try { withTimeoutOrNull(8_000L) { detail(candidate) } } catch (c: CancellationException) { throw c }
            catch (e: Exception) { android.util.Log.w("MangaroNovels", "Edition corroboration unavailable", e) }
        }
    }
    private fun discoveryKey(source: NovelSource, term: String, page: Int, genre: String?) =
        listOf(source.id, term, page.toString(), genre.orEmpty()).joinToString("|") +
            when {
                source.id == SeaNovelSource.SOURCE_ID -> "|${SeaNovelSource.PARSER_VERSION}"
                source is SunovelsGatewaySource -> "|${SunovelsGatewayConfig.cacheKey}"
                source.id == SunovelsSource.SOURCE_ID -> "|sun-direct-discovery-v2"
                else -> ""
            }
    suspend fun discoverySnapshot(source: NovelSource, term: String, page: Int, genre: String?): NovelPage? = withContext(Dispatchers.IO) {
        val key = discoveryKey(source, term, page, genre)
        if (source.id == SunovelsSource.SOURCE_ID && source !is SunovelsGatewaySource) ensureSunBootstrap()
        val store = if (source.id == SunovelsSource.SOURCE_ID && source !is SunovelsGatewaySource) metadata else indexCache
        synchronized(discoveryCache) { discoveryCache[key]?.takeIf { System.currentTimeMillis() - it.at in 0..86_400_000L }?.let { return@withContext it.result } }
        val restored = runCatching { store.read("discovery/" + novelDigest(key) + ".json")?.let { json.decodeFromString<DiscoveryCache>(it).let { cached -> cached.copy(result = cached.result.copy(novels = cached.result.novels.map(SunovelsSource::cleanMetadata))) } } }
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
        // Sea paginates a single validated metadata snapshot. Re-enter its session even
        // when a persisted page is visible, so pages from different snapshots cannot mix.
        // The adapter coalesces/cache-bounds metadata requests; snapshot display stays instant.
        if (source.id != SeaNovelSource.SOURCE_ID && source !is SunovelsGatewaySource) synchronized(discoveryCache) {
            discoveryCache[key]?.takeIf { System.currentTimeMillis() - it.at < 300_000 }?.let { return@coalesce it.result }
        }
        val result = try { withTimeoutOrNull(30_000L) {
            if (source is SunovelsGatewaySource) source.discovery(term,page,genre)
            else if (term.isNotBlank()) source.search(term, page) else source.catalog(page, latest = true, genre = genre)
        } ?: throw NovelSourceFailure("استغرق التحميل وقتًا طويلًا. حاول مجددًا.", "Discovery deadline exceeded")
        } catch (c: CancellationException) { throw c }
        catch (e: Exception) {
            if (source.id != SunovelsSource.SOURCE_ID || source is SunovelsGatewaySource) throw e
            return@coalesce discoverySnapshot(source, term, page, genre)?.copy(notice = "تعذّر تحديث المصدر؛ تُعرض بيانات الاكتشاف المحفوظة.") ?: throw e
        }
        ingest(result.novels)
        val snapshot = DiscoveryCache(result, System.currentTimeMillis())
        synchronized(discoveryCache) {
            discoveryCache[key] = snapshot
            while (discoveryCache.size > 16) discoveryCache.remove(discoveryCache.keys.first())
        }
        io.launch {
            runCatching {
                val store = if (source.id == SunovelsSource.SOURCE_ID && source !is SunovelsGatewaySource) metadata else indexCache
                synchronized(store) {
                    val path = "discovery/" + novelDigest(key) + ".json"
                    val old = store.read(path)?.let { json.decodeFromString<DiscoveryCache>(it) }
                    if (old == null || old.at <= snapshot.at) store.write(path, json.encodeToString(snapshot))
                    val directory = if (store === metadata) File(app.filesDir, "novels-local/discovery") else File(app.cacheDir, "novels-indexes/discovery")
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
    private suspend fun maySave(novel: Novel): Boolean {
        ready.await()
        if (NovelSourceAvailability.active(novel.sourceId) || library.value.any { it.novel.id == novel.id } || diskHasEdition(novel)) return true
        val queue = activeDownloads() ?: if (File(offlineRoot(), "downloads/queue.db").isFile) downloads else null
        return try { queue?.awaitReady(); queue?.tasks?.value?.any { it.novel.id == novel.id } == true }
        catch (c: CancellationException) { throw c }
        catch (_: Exception) { false }
    }
    private val retiredSourceMessage = "Sunovels مستبعد من الاكتشاف والإضافة الجديدة حاليًا. محتوياتك المحفوظة لم تُحذف."

    suspend fun setSaved(novel: Novel, saved: Boolean) {
        if (saved && !maySave(novel)) { mutableStorageError.value = retiredSourceMessage; return }
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
            if (saved) {
                SigilEvents.emit(SigilEvents.Kind.LIBRARY, 0, owner = owner)
                withContext(Dispatchers.IO) { runCatching { indexSnapshot(novel)?.let { metadata.write(indexPath(novel), json.encodeToString(it)) } }.onFailure { Log.w("MangaroNovels", "Saved index cache failed", it) } }
            }
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
        if ((saved == true || favorite == true || status != null) && !maySave(novel))
            throw NovelSourceFailure(retiredSourceMessage, "Retired novel source cannot create a library entry")
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
    suspend fun bindCloudAccount(owner: String?, importGuest: Boolean = false, scopeTransition: Boolean = false) = withContext(Dispatchers.IO) {
        val token = tachiyomi.core.common.util.lang.LocalAccountScope.capture()
        fun checkBinding() {
            if (!scopeTransition) {
                tachiyomi.core.common.util.lang.LocalAccountScope.check(token)
                tachiyomi.core.common.util.lang.LocalAccountScope.checkOwner(owner ?: "guest")
            } else check(!tachiyomi.core.common.util.lang.LocalAccountScope.changes.value.ready)
        }
        ready.await()
        lock.withLock {
            checkBinding()
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
                    byId[g.novel.id] = NovelGuestMerge.item(e, g, b)
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
                        val destination = accountPrefix(owner) + "positions/" + relative
                        if (NovelGuestMerge.canImportPosition(metadata.read(destination), positionBaseline[relative])) {
                            metadata.write(destination, value)
                        }
                    }
                }
                write(target, json.encodeToString(normalized))
                write(baselineFile, json.encodeToString(guest))
                metadata.write(positionBaselineName, json.encodeToString(newPositionBaseline))
                write(claim, json.encodeToString(owner!!))
            }
            checkBinding()
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
    private fun indexKey(novel: Novel) = novelDigest("index-v2|${novel.sourceId}|${NovelRules.selector(novel.sourceId, "chapterLinks", "compiled")}|${NovelRules.selector(novel.sourceId, "chapterTitle", "compiled")}" +
        when (novel.sourceId) {
            "novel.kolnovel" -> "|${KolNovelSource.INDEX_PARSER_VERSION}"
            SeaNovelSource.SOURCE_ID -> "|${SeaNovelSource.PARSER_VERSION}"
            SunovelsSource.SOURCE_ID -> "|${SunovelsSource.INDEX_PARSER_VERSION}|${if (SunovelsSource.directEnabled) "direct" else if (SunovelsGatewayConfig.enabled) "gateway" else "direct"}"
            else -> ""
        })
    private fun indexPath(novel: Novel) = "indexes/" + novelDigest(novel.id) + ".json"
    suspend fun indexSnapshot(novel: Novel): NovelChapterIndex? = withContext(Dispatchers.IO) {
        memoryCacheLock.withLock { indexes[novel.id] }?.let {
            if (it.parserKey != indexKey(novel) || SunovelsIndexSnapshots.stale(it)) it.copy(complete = false) else it
        } ?: runCatching {
            (metadata.read(indexPath(novel)) ?: indexCache.read(indexPath(novel)))?.let { json.decodeFromString<NovelChapterIndex>(it) }
                ?.takeIf { it.editionId == novel.id && it.chapters.distinctBy { ch -> ch.id }.size == it.chapters.size &&
                    it.chapters.all { ch -> NovelHttp.allowed(ch.url) && ch.title.isNotBlank() &&
                        ch.url.toHttpUrl().host.removePrefix("www.") == novel.url.toHttpUrl().host.removePrefix("www.") } }
        }.getOrNull()?.let { if (it.parserKey != indexKey(novel) || SunovelsIndexSnapshots.stale(it)) it.copy(complete = false) else it }?.let { restored ->
            // Restore authoritative proof before returning the first disk-backed UI projection.
            if (novel.sourceId == SunovelsSource.SOURCE_ID)
                restored.chapters.forEach { readSunReaderEvidence(novel, it) }
            cacheIndex(restored)
            if (novel.sourceId == SunovelsSource.SOURCE_ID) memoryCacheLock.withLock { indexes[novel.id] } else restored
        }
    }
    /** Local state only: availability checks continue to update an already-open details screen. */
    fun observeSunIndex(novel: Novel): Flow<NovelChapterIndex> {
        require(novel.sourceId == SunovelsSource.SOURCE_ID)
        return indexRevision.mapNotNull { indexSnapshot(novel) }.distinctUntilChanged()
    }
    private fun overlaySunReaderEvidence(index: NovelChapterIndex): NovelChapterIndex =
        if (!index.editionId.startsWith(SunovelsSource.SOURCE_ID + ":")) index else {
            val rows = index.chapters.map {
                SunovelsAccess.mobileIndexCandidate(SunovelsAccess.mergeIndex(it, sunReaderRestrictions[index.editionId + "|" + it.id]))
            }
            index.copy(chapters = rows, notice = index.notice.takeUnless { rows.all { it.available } })
        }
    private suspend fun cacheIndex(index: NovelChapterIndex) = memoryCacheLock.withLock {
        indexes[index.editionId] = overlaySunReaderEvidence(index)
        while (indexes.size > 5) indexes.remove(indexes.keys.first())
        indexRevision.update { it + 1 }
    }
    suspend fun continueIndex(novel: Novel, refresh: Boolean = false, onPage: suspend (NovelChapterIndex) -> Unit = {}): NovelChapterIndex {
        var first = true
        return NovelChapterIndexer.continueBatches {
            NovelChapterIndexer.recoverBatch {
                completeIndex(novel, refresh = first && refresh, maxPages = 8, onPage = onPage)
            }.also { first = false }
        }
    }

    suspend fun completeIndex(novel: Novel, refresh: Boolean = false, onPage: suspend (NovelChapterIndex) -> Unit = {}): NovelChapterIndex =
        completeIndex(novel, refresh, if (novel.sourceId == SunovelsSource.SOURCE_ID) 2 else Int.MAX_VALUE, onPage)

    suspend fun completeIndex(novel: Novel, refresh: Boolean = false, maxPages: Int, onPage: suspend (NovelChapterIndex) -> Unit = {}): NovelChapterIndex = withContext(Dispatchers.IO) {
        ready.await()
        // Bounded UI callers and explicit full-index callers share the edition mutation lock.
        val flightKey = novel.id + ":" + maxPages
        require(maxPages > 0)
        val flight = synchronized(indexFlights) {
            indexFlights[flightKey]?.also { it.readers++; it.release?.cancel(); it.release = null }
                ?: IndexFlight(io.async(start = CoroutineStart.LAZY) {
                    // Eight individually bounded source calls may legitimately take >20s.
                    // Preserve Sunovels' separately accepted two-page/session policy.
                    if (maxPages == Int.MAX_VALUE || novel.sourceId != SunovelsSource.SOURCE_ID) collectCompleteIndex(novel, refresh, maxPages)
                    else withTimeoutOrNull(20_000) { collectCompleteIndex(novel, refresh, maxPages) }
                        ?: run {
                            if (novel.sourceId == SunovelsSource.SOURCE_ID)
                                SunovelsDiagnostics.log("INDEX_DEADLINE", novel.url, "REPOSITORY_INDEX", code = "SUN_INDEX_TIMEOUT", reason = "BOUNDED_SESSION")
                            throw NovelSourceFailure(if (novel.sourceId == SunovelsSource.SOURCE_ID)
                                "استغرق تحميل فهرس الفصول وقتًا طويلًا. الفصول المحفوظة تبقى متاحة؛ أعد المحاولة."
                                else "تعذّر تحميل بقية الفصول. أعد المحاولة.", "Chapter discovery session timed out", code = if (novel.sourceId == SunovelsSource.SOURCE_ID) "SUN_INDEX_TIMEOUT" else null)
                        }
                })
                    .also { indexFlights[flightKey] = it }
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
                    // The collector may have captured a lock hint before availability verification.
                    val visible = if (novel.sourceId == SunovelsSource.SOURCE_ID) indexSnapshot(novel) ?: result else result
                    onPage(visible)
                    visible
                } catch (c: CancellationException) { throw c }
                catch (e: Exception) { observer.cancelAndJoin(); indexSnapshot(novel)?.let { onPage(it) }; throw e }
                finally { observer.cancel() }
            }
        } finally {
            synchronized(indexFlights) {
                if (--flight.readers == 0) {
                    if (flight.result.isCompleted) indexFlights.remove(flightKey, flight)
                    else flight.release = io.launch {
                        // Short ownership grace keeps an in-flight page across navigation; no UI delay.
                        delay(3000)
                        synchronized(indexFlights) {
                            if (flight.readers == 0 && indexFlights[flightKey] === flight) {
                                indexFlights.remove(flightKey); flight.result.cancel()
                            }
                        }
                    }
                }
            }
        }
    }
    private suspend fun collectCompleteIndex(novel: Novel, refresh: Boolean, maxPages: Int): NovelChapterIndex = withContext(Dispatchers.IO) {
        ready.await()
        indexLocks.getOrPut(novel.id) { Mutex() }.withLock {
            val old = indexSnapshot(novel)
            val key = indexKey(novel)
            val checkpointPath = indexPath(novel).removeSuffix(".json") + ".refresh.json"
            val restartSun = novel.sourceId == SunovelsSource.SOURCE_ID && refresh
            val staleSun = old?.let(SunovelsIndexSnapshots::stale) == true
            if (novel.sourceId == SunovelsSource.SOURCE_ID) SunovelsDiagnostics.log(
                if (restartSun) "RETRY_RESTART" else "CHECKPOINT_RESTORE", novel.url, "REPOSITORY_INDEX",
                checkpoint = old?.nextPage, generation = old?.gatewayIndexGeneration, parserKey = old?.parserKey, reason = if (restartSun) "USER_REFRESH" else "AUTO_CONTINUE")
            val pending = runCatching {
                (metadata.read(checkpointPath) ?: indexCache.read(checkpointPath))?.let { json.decodeFromString<NovelChapterIndex>(it) }
                    ?.takeIf { it.editionId == novel.id && it.parserKey == key && !SunovelsIndexSnapshots.stale(it) && it.updatedAt > (old?.updatedAt ?: 0) }
                    ?.takeUnless { novel.sourceId != SunovelsSource.SOURCE_ID && NovelIndexRecovery.invalidContinuation(it) }
                    ?.takeUnless { restartSun }
            }.getOrNull()
            if (old?.complete == true && old.failureState == null && old.parserKey == key && !staleSun && pending == null && !refresh &&
                !NovelChapterIndexer.staleKolnovelCompletion(novel, old) &&
                System.currentTimeMillis() - old.updatedAt < 21_600_000) return@withLock old
            val stranded = old != null && novel.sourceId != SunovelsSource.SOURCE_ID && NovelIndexRecovery.invalidContinuation(old)
            val preserve = old != null && (restartSun || staleSun || old.complete || old.parserKey != key || pending != null || stranded)
            // Sunovels user Retry starts fresh while last-good rows remain visible. Automatic continuation resumes.
            val empty = if (novel.sourceId == SunovelsSource.SOURCE_ID) SunovelsIndexSnapshots.fresh(novel.id, key) else NovelChapterIndex(novel.id, parserKey = key)
            val initial = if (restartSun) empty else pending ?: old?.takeIf { !stranded && !it.complete && it.parserKey == key && !staleSun &&
                !(novel.sourceId == SunovelsSource.SOURCE_ID && refresh && it.nextPage == null) }
                ?: empty
            var latest = initial
            suspend fun checkpoint(incoming: NovelChapterIndex) {
                val index = if (novel.sourceId == SunovelsSource.SOURCE_ID) incoming.copy(chapters = incoming.chapters.map { chapter ->
                    SunovelsAccess.mergeIndex(chapter, readSunReaderEvidence(novel, chapter) ?: old?.chapters?.firstOrNull { it.id == chapter.id })
                }) else incoming
                if (novel.sourceId == SunovelsSource.SOURCE_ID) SunovelsDiagnostics.log("CHECKPOINT_SAVED", novel.url,
                    "REPOSITORY_INDEX", checkpoint = index.nextPage, generation = index.gatewayIndexGeneration, parserKey = index.parserKey, reason = if (index.complete) "COMPLETE" else "PARTIAL")
                latest = index
                if (preserve && novel.sourceId == SunovelsSource.SOURCE_ID && !index.complete) {
                    metadata.write(checkpointPath, json.encodeToString(index))
                    val fresh = index.chapters.associateBy { it.id }
                    val oldIds = old!!.chapters.mapTo(HashSet()) { it.id }
                    // Expose verified availability/evidence immediately while retaining every
                    // old URL. Keep the independent collector checkpoint free of old rows.
                    val visible = if (index.nextPage == null && oldIds.all { it in fresh }) index else SunovelsIndexSnapshots.visible(old, index)
                    cacheIndex(visible); persistIndex(novel, visible)
                    return
                }
                if (preserve && index.complete) {
                    val ids = index.chapters.mapTo(HashSet()) { it.id }
                    val removed = old!!.chapters.filter { it.id !in ids }
                    if (novel.sourceId == SunovelsSource.SOURCE_ID && removed.isNotEmpty()) {
                        // Absence is accepted only after a complete verified traversal. Retain URLs/progress.
                        val retained = index.copy(chapters = index.chapters + removed.map { it.copy(available = false,
                            unavailableReason = SunovelsSource.REMOVED_MESSAGE, sunovelsState = SunovelsChapterState.MISSING, sunovelsEvidence = SunovelsChapterEvidence.INDEX) }, notice = SunovelsSource.REMOVED_MESSAGE)
                        cacheIndex(retained); persistIndex(novel, retained)
                        return
                    }
                    check(removed.isEmpty()) { "Chapter index unexpectedly decreased" }
                }
                if (preserve && !index.complete) {
                    metadata.write(checkpointPath, json.encodeToString(index))
                    val visible = NovelIndexRecovery.visible(old!!, index)
                    cacheIndex(visible); persistIndex(novel, visible)
                }
                else { cacheIndex(index); persistIndex(novel, index) }
            }
            try {
                suspend fun collect() = withContext(FocusTask(WorkPriority.ACTIVE_WORK,novel.sourceId,novel.id,operation=FocusOperation.METADATA)) {
                    NovelChapterIndexer.collect(source(novel.sourceId), novel, initial, maxPages = maxPages) { index ->
                        checkpoint(index) // Every verified page survives process death, not just every fourth page.
                    }
                }
                val result = collect()
                checkpoint(result)
                if (result.complete) updateEvidence(novel, NovelEditionEvidence(true, result.availableCount, catalog.value.evidence[novel.id]?.accessWorks, result.updatedAt))
                if (preserve && novel.sourceId == SunovelsSource.SOURCE_ID && !result.complete) indexSnapshot(novel) ?: result
                else if (preserve && !result.complete) indexSnapshot(novel) ?: result else result
            } catch (c: CancellationException) {
                withContext(NonCancellable) { checkpoint(latest) }; throw c
            } catch (e: Exception) {
                // Never overwrite the prior usable index with an unfinished refresh.
                if (e.message == "Chapter total changed during pagination" || (e as? NovelSourceFailure)?.code == "SUN_INDEX_CHECKPOINT_CHANGED") {
                    // A changing source invalidates the continuation, not the usable chapters.
                    metadata.write(checkpointPath, json.encodeToString(empty.copy(updatedAt = System.currentTimeMillis())))
                } else checkpoint(latest)
                // Failure provenance is durable and distinct from verified-empty. Retain every
                // last-good row and its freshness; a failed refresh cannot renew the success TTL.
                val visible = NovelIndexRecovery.failed(indexSnapshot(novel) ?: old ?: latest, e)
                cacheIndex(visible); persistIndex(novel, visible)
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
            if (novel.sourceId == SunovelsSource.SOURCE_ID && snapshot.parserKey != indexKey(novel)) {
                return@withContext completeIndex(novel, refresh = true, maxPages = 8)
            }
            if (novel.sourceId == SeaNovelSource.SOURCE_ID && snapshot.parserKey != indexKey(novel)) {
                return@withContext completeIndex(novel, refresh = true, maxPages = 1)
            }
            if (indexMutex.tryLock()) {
                try {
                    val latest = indexSnapshot(novel) ?: snapshot
                    if (latest.complete || latest.chapters.size >= count) return@withContext latest
                    val result = NovelChapterIndexer.collect(source(novel.sourceId), novel, latest,
                        stopWhen = { it.chapters.size >= count },
                        maxPages = if (novel.sourceId == SunovelsSource.SOURCE_ID) 8 else Int.MAX_VALUE,
                        onPage = { cacheIndex(it); persistIndex(novel, it) })
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
    private fun diskHasEdition(novel: Novel) = File(offlineRoot(), "text-downloads/chapters/" + novelDigest(novel.id)).listFiles()?.any { it.isFile && it.name.endsWith(".json.gz") } == true
    private fun persistIndex(novel: Novel, index: NovelChapterIndex) {
        if (novel.sourceId == SunovelsSource.SOURCE_ID || !index.complete || metadata.read(indexPath(novel)) != null || library.value.any { it.novel.id == novel.id && it.saved } || diskHasEdition(novel))
            metadata.write(indexPath(novel), json.encodeToString(overlaySunReaderEvidence(index)))
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
    suspend fun adjacent(novel: Novel, chapter: NovelChapter, forward: Boolean): NovelChapter? = withContext(Dispatchers.IO + FocusTask(WorkPriority.VISIBLE, novel.sourceId, novel.id, chapter.id)) {
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
            if (current.complete || novel.sourceId == SunovelsSource.SOURCE_ID && current.nextPage == null && current.parserKey == indexKey(novel)) return@withContext null
            if (novel.sourceId == SunovelsSource.SOURCE_ID && current.parserKey != indexKey(novel)) {
                return@withContext neighbor(completeIndex(novel, refresh = true, maxPages = 8))
            }
            if (novel.sourceId == SeaNovelSource.SOURCE_ID && current.parserKey != indexKey(novel)) {
                // Preserve cached/offline neighbors above. At an unresolved boundary, use the
                // normal last-good-preserving reparse rather than a stale exhausted cursor.
                return@withContext neighbor(completeIndex(novel, refresh = true, maxPages = 1))
            }
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
    @Volatile var lastDataOrigin = "unknown"

    private val sunReaderRestrictions = ConcurrentHashMap<String, NovelChapter>()
    private val sunReaderEpochs = ConcurrentHashMap<String, Long>()
    private fun sunEvidencePath(novel: Novel, chapter: NovelChapter) = "sun-reader-evidence/" + novelDigest(novel.id) + "/" + novelDigest(chapter.id) + ".json"
    private fun readSunReaderEvidence(novel: Novel, chapter: NovelChapter): NovelChapter? {
        val key = novelTextCacheKey(offlineOwner(), novel.id, chapter.id)
        return sunReaderRestrictions[key] ?: runCatching {
            metadata.read(sunEvidencePath(novel, chapter))?.let { json.decodeFromString<NovelChapter>(it) }
                ?.takeIf { it.id == chapter.id && it.sunovelsEvidence != null }
                // A slow disk read must not overwrite proof published by a concurrent verification.
                ?.let { sunReaderRestrictions.putIfAbsent(key, it) ?: it }
        }.getOrNull()
    }
    private suspend fun rememberSunReaderEvidence(novel: Novel, chapter: NovelChapter, report: SunovelsResolutionReport? = null) {
        if (!SunovelsAccess.durableEvidence(chapter)) return
        // The same atomic metadata store retains reader proof independently of a concurrent index refresh.
        synchronized(metadata) {
            val key = novelTextCacheKey(offlineOwner(), novel.id, chapter.id)
            val epoch = report?.epoch ?: 0
            if (epoch < (sunReaderEpochs[key] ?: 0)) return
            sunReaderEpochs[key] = epoch
            metadata.write(sunEvidencePath(novel, chapter), json.encodeToString(chapter))
            sunReaderRestrictions[novelTextCacheKey(offlineOwner(), novel.id, chapter.id)] = chapter
        }
        memoryCacheLock.withLock {
            if ((report?.epoch ?: 0) < (sunReaderEpochs[novelTextCacheKey(offlineOwner(), novel.id, chapter.id)] ?: 0)) return@withLock
            indexes[novel.id]?.let { current ->
                val repairedPage = report?.indexRepair
                val freshRows = repairedPage?.chapters?.associateBy { it.id }.orEmpty()
                val rows = current.chapters.map { row ->
                    val fresh = freshRows[row.id] ?: row
                    if (row.id == chapter.id) fresh.copy(available = chapter.available, unavailableReason = chapter.unavailableReason, sunovelsState = chapter.sunovelsState, sunovelsEvidence = chapter.sunovelsEvidence) else fresh
                }
                val next = overlaySunReaderEvidence(current.copy(chapters = rows,
                    declaredTotal = repairedPage?.declaredTotal ?: current.declaredTotal))
                indexes[novel.id] = next; persistIndex(novel, next)
            }
            // Also notify consumers when proof arrived before an index was loaded into memory.
            indexRevision.update { it + 1 }
        }
    }
    /** User action only: one normal public GET; no URL guessing, lock bypass or reader navigation. */
    suspend fun revalidateSunChapter(novel: Novel, chapter: NovelChapter): NovelChapter = withContext(Dispatchers.IO) {
        require(novel.sourceId == SunovelsSource.SOURCE_ID && SunovelsSource.directEnabled)
        coalesce("sun-revalidate:" + novelTextCacheKey(offlineOwner(), novel.id, chapter.id)) {
            val direct = source(novel.sourceId) as? SunovelsSource ?: error("Direct source unavailable")
            val prior = readSunReaderEvidence(novel, chapter) ?: chapter
            try {
                val candidate = SunovelsAccess.probeCandidate(chapter)
                SunovelsSource.requireResolutionOwnership(novel, candidate)
                val resolved = direct.resolvePublicChapter(candidate, manualRetry = true, availabilityCheck = true)
                SunovelsReaderDiagnostics.record(resolved.report.copy(previousAccessEvidence = SunovelsReaderDiagnostics.lockEvidence(prior)))
                val text = resolved.text
                SunovelsSource.validateText(text)
                memoryCacheLock.withLock {
                    texts[novelTextCacheKey(offlineOwner(), novel.id, chapter.id)] = text
                    while (texts.size > 2 || texts.values.sumOf { value -> value.paragraphs.sumOf { it.length } + value.markup.sumOf { it.length } } > 1_000_000) texts.remove(texts.keys.first())
                }
                SunovelsAccess.readable(chapter.copy(title = SunovelsSource.bestTitle(chapter.title, resolved.report.pageTitle))).also { rememberSunReaderEvidence(novel, it, resolved.report) }
            } catch (c: CancellationException) { throw c }
            catch (e: Exception) {
                SunovelsReaderDiagnostics.get(chapter.id)?.let { SunovelsReaderDiagnostics.record(it.copy(
                    previousAccessEvidence = SunovelsReaderDiagnostics.lockEvidence(prior))) }
                if ((e as? NovelSourceFailure)?.let { it.sourceVerified && it.code == "SUN_LOCKED" } == true)
                    rememberSunReaderEvidence(novel, SunovelsAccess.failed(prior, e), SunovelsReaderDiagnostics.get(chapter.id))
                throw e
            }
        }
    }
    /** User-initiated preflight: no position/history mutation until a verified reader opens. */
    suspend fun prepareReaderChapter(novel: Novel, chapter: NovelChapter, manualRetry: Boolean = false) = withContext(Dispatchers.IO) {
        if (novel.sourceId != SunovelsSource.SOURCE_ID) return@withContext
        SunovelsDiagnostics.log("READER_PREFLIGHT", novel.url, "REPOSITORY_READER", page = chapter.sourcePage)
        try {
            // Keep checksum-validated downloads/memory as the fast path even on manual Retry.
            val retained = memoryCacheLock.withLock { texts[novelTextCacheKey(offlineOwner(), novel.id, chapter.id)] } ?: disk.read(novel.id, chapter.id)
            if (manualRetry && retained == null) revalidateSunChapter(novel, chapter)
            chapterText(novel, chapter)
        }
        catch (c: CancellationException) { throw c }
        catch (e: NovelSourceFailure) {
            if (SunovelsReaderDiagnostics.get(chapter.id) == null) SunovelsReaderDiagnostics.failed(chapter, e)
            SunovelsDiagnostics.log("READER_REFUSED", novel.url, "REPOSITORY_READER", status = e.httpStatus, code = e.code)
            throw e
        }
    }

    internal suspend fun requireSunChapter(novel: Novel, chapter: NovelChapter, storedContent: Boolean = false) {
        if (novel.sourceId != SunovelsSource.SOURCE_ID) return
        fun checked(row: NovelChapter) = if (storedContent) SunovelsAccess.storedCandidate(row) else row
        var validating = chapter
        fun validate(row: NovelChapter) { validating = row; SunovelsSource.requireResolutionOwnership(novel, row) }
        try {
            val evidence = readSunReaderEvidence(novel, chapter)
            // Report durable reader lock proof before a weaker index hint for the same identity.
            evidence?.let { validate(checked(it)) }
            validate(checked(SunovelsAccess.readerCandidate(chapter, evidence)))
            indexSnapshot(novel)?.chapters?.firstOrNull { it.id == chapter.id }?.let {
                validate(checked(SunovelsAccess.readerCandidate(it, readSunReaderEvidence(novel, chapter))))
            }
        } catch (c: CancellationException) { throw c }
        catch (e: Exception) { SunovelsReaderDiagnostics.failed(validating, e); throw e }
    }

    /** Owner-authorized mobile text enters the same checked, atomic disk/queue pipeline as other novels. */
    internal suspend fun ceneleChapterForDownload(novel: Novel, chapter: NovelChapter): NovelText = withContext(Dispatchers.IO) {
        require(novel.sourceId == "novel.cenele")
        val cached = chapterText(novel, chapter)
        if (!CeneleSource.staleIllustration(chapter, cached)) return@withContext cached
        // Preserve the old readable disk text until a successful atomic queue commit. Only
        // this impossible illustration address needs a fresh source parse, not a data reset.
        evictChapter(novel.id, chapter.id)
        source(novel.sourceId).chapter(chapter).also { validateNovelRepositoryContent(novel.sourceId, it) }
    }
    internal suspend fun sunChapterForDownload(novel: Novel, chapter: NovelChapter): NovelText = withContext(Dispatchers.IO) {
        require(novel.sourceId == SunovelsSource.SOURCE_ID)
        disk.read(novel.id, chapter.id)?.let { stored ->
            requireSunChapter(novel, chapter, storedContent = true)
            SunovelsSource.validateText(stored)
            return@withContext stored
        }
        requireSunChapter(novel, chapter)
        if (SunovelsSource.mobileOfflineAuthorized) return@withContext chapterText(novel, chapter).also { SunovelsSource.validateText(it) }
        (source(novel.sourceId) as SunovelsSource).chapterForDownload(chapter).also { SunovelsSource.validateText(it) }
    }
    suspend fun chapterText(novel: Novel, chapter: NovelChapter): NovelText = withContext(Dispatchers.IO) {
        val isCenele = novel.sourceId == "novel.cenele" || novel.id == "novel.cenele" || novel.id.startsWith("novel.cenele:") || novel.id.startsWith("novel.cenele/")
        val seaTrace = SeaNovelReaderDiagnostics.current() ?: SeaNovelReaderDiagnostics.begin(novel, chapter)
        seaTrace?.stage(SeaReaderStage.REPOSITORY_OWNERSHIP)
        if (novel.sourceId == SeaNovelSource.SOURCE_ID) SeaNovelSource.requireOwnership(novel, chapter)
        disk.read(novel.id, chapter.id)?.takeIf { disk.complete(it) }?.let { stored ->
            requireSunChapter(novel, chapter, storedContent = true)
            val validated = if (isCenele) CeneleSanitizer.sanitize(stored) else stored
            validateNovelRepositoryContent(novel.sourceId, validated)
            lastDataOrigin = "disk"
            seaTrace?.cache("CHECKSUM_DOWNLOAD", stored)
            if(novel.sourceId == SunovelsSource.SOURCE_ID) SunovelsReaderDiagnostics.cached(chapter,stored,"CHECKSUM_DOWNLOAD")
            return@withContext validated
        }
        if (novel.sourceId == SunovelsSource.SOURCE_ID) {
            // A later public lock must not erase authorized, validated offline reading.
            // Ownership, ambiguous-address and missing-identity checks still apply.
            val memory = memoryCacheLock.withLock { texts[novelTextCacheKey(offlineOwner(), novel.id, chapter.id)] }
            val retained = memory ?: disk.read(novel.id, chapter.id)
            if (retained != null) {
                requireSunChapter(novel, chapter, storedContent = true)
                SunovelsSource.validateText(retained)
                lastDataOrigin = "stored"
                SunovelsReaderDiagnostics.cached(chapter, retained, if (memory != null) "VERIFIED_MEMORY" else "CHECKSUM_DOWNLOAD")
                return@withContext retained
            }
        }
        requireSunChapter(novel, chapter)
        val requestOwner = offlineOwner()
        val identity = Triple(requestOwner, novel.id, chapter.id)
        val key = novelTextCacheKey(offlineOwner(), novel.id, chapter.id)
        seaTrace?.stage(SeaReaderStage.CACHE_LOOKUP)
        memoryCacheLock.withLock { texts[key] }?.let {
            if (novel.sourceId == SunovelsSource.SOURCE_ID) SunovelsSource.validateText(it)
            lastDataOrigin = "memory"
            seaTrace?.cache("VERIFIED_MEMORY", it)
            return@withContext if (isCenele) CeneleSanitizer.sanitize(it) else it
        }
        // A text request must not wait on an unrelated catalog/detail hash collision.
        // Share the actual result with all callers, even if the small text LRU changes meanwhile.
        val fetchTrace = seaTrace?.fork()
        val requestTask = FocusTask(currentCoroutineContext()[FocusTask]?.priority ?: WorkPriority.VISIBLE, novel.sourceId, novel.id, chapter.id)
        val flight = synchronized(chapterFlights) {
            chapterFlights[identity]?.also { it.readers++ } ?: ChapterFlight(io.async(context = requestTask + tachiyomi.core.common.util.lang.LocalAccountScope.StorageElement(requestOwner) + (fetchTrace ?: kotlin.coroutines.EmptyCoroutineContext), start = CoroutineStart.LAZY) {
                try {
                    fetchTrace?.stage(SeaReaderStage.CACHE_LOOKUP)
                    memoryCacheLock.withLock { texts[key] }?.let {
                        if (novel.sourceId == SunovelsSource.SOURCE_ID) SunovelsSource.validateText(it)
                        lastDataOrigin = "memory"
                        fetchTrace?.cache("VERIFIED_MEMORY", it)
                        return@async if (isCenele) CeneleSanitizer.sanitize(it) else it
                    }
                    val offline = disk.read(novel.id, chapter.id)
                    if (offline == null || !disk.complete(offline)) {
                        // Restore an existing queue on cold reopen; do not create one for ordinary online reading.
                        val queue = if (activeDownloads() != null || File(offlineRoot(), "downloads/queue.db").isFile) downloads else null
                        if (queue != null) {
                            try {
                                queue.awaitReady()
                                if (queue.tasks.value.any {
                                    it.novel.id == novel.id && it.chapter.id == chapter.id && it.state == NovelDownloadState.DONE
                                }) queue.invalidate(novel, chapter)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // A queue failure must not erase or prevent reading valid cached text.
                                android.util.Log.w("MangaroNovels", "Novel download integrity recovery unavailable")
                            }
                        }
                    }
                    var resolvedReport: SunovelsResolutionReport? = null
                    val rawResult = if (offline != null) {
                        lastDataOrigin = "disk"
                        fetchTrace?.cache("CHECKSUM_DOWNLOAD", offline)
                        offline
                    } else {
                        lastDataOrigin = "network"
                        fetchTrace?.cache("NETWORK")
                        try {
                            if (novel.sourceId == SunovelsSource.SOURCE_ID) {
                                val direct = source(novel.sourceId) as SunovelsSource
                                val resolved = direct.resolvePublicChapter(SunovelsAccess.readerCandidate(chapter, readSunReaderEvidence(novel, chapter)))
                                resolvedReport = resolved.report
                                resolved.text
                            } else source(novel.sourceId).chapter(chapter)
                        } catch (c: CancellationException) { throw c }
                        catch (e: Exception) {
                            if (novel.sourceId == SunovelsSource.SOURCE_ID && (e as? NovelSourceFailure)?.let { it.sourceVerified && it.code == "SUN_LOCKED" } == true)
                                rememberSunReaderEvidence(novel, SunovelsAccess.failed(readSunReaderEvidence(novel, chapter) ?: chapter, e), SunovelsReaderDiagnostics.get(chapter.id))
                            throw e
                        }
                    }
                    val result = if (isCenele) CeneleSanitizer.sanitize(rawResult) else rawResult
                    if (novel.sourceId == SunovelsSource.SOURCE_ID) {
                        SunovelsSource.validateText(result)
                        if (offline == null) rememberSunReaderEvidence(novel, SunovelsAccess.readable(chapter.copy(title = resolvedReport?.pageTitle ?: chapter.title)), resolvedReport)
                    }
                    fetchTrace?.stage(SeaReaderStage.REPOSITORY_VALIDATION)
                    if (novel.sourceId != SunovelsSource.SOURCE_ID) validateNovelRepositoryContent(novel.sourceId, result)
                    fetchTrace?.accepted(result, source = false)
                    fetchTrace?.stage(SeaReaderStage.REPOSITORY_ACCEPTED)
                    memoryCacheLock.withLock {
                        texts[key] = result
                        while (texts.size > 2 || texts.values.sumOf { v -> v.paragraphs.sumOf { it.length } + v.markup.sumOf { it.length } + v.blocks.sumOf { (it.imageUrl?.length ?: 0) + it.alt.length } } > 1_000_000) texts.remove(texts.keys.first())
                    }
                    result
                } catch (e: Exception) { fetchTrace?.failed(e); throw e }
            }, seaTrace = fetchTrace).also { chapterFlights[identity] = it }
        }
        try {
            flight.result.start()
            val result = flight.result.await()
            seaTrace?.adopt(flight.seaTrace)
            result
        } catch (e: Exception) {
            seaTrace?.adopt(flight.seaTrace); seaTrace?.failed(e); throw e
        }
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
    suspend fun sunovelsDiagnostic(novel: Novel, chapter: NovelChapter): String = withContext(Dispatchers.IO) {
        require(SunovelsSource.diagnosticsEnabled)
        val identity = "selectedSource=${novel.sourceId}\nsourceName=${sources.firstOrNull { it.id == novel.sourceId }?.name ?: novel.sourceId}\neditionId=${novel.id}\n"
        if (novel.sourceId == SeaNovelSource.SOURCE_ID) return@withContext identity + (SeaNovelReaderDiagnostics.get(novel, chapter)?.copyReport()
            ?: "resolution=SEA_NO_CAPTURED_READER_ATTEMPT\nrequestedUrl=NOT_CAPTURED\nfinalUrl=NOT_CAPTURED\nhttp=UNKNOWN")
        if (novel.sourceId != SunovelsSource.SOURCE_ID) return@withContext identity + "novelUrl=${novel.url}\npersistedChapterId=${chapter.id}\ndisplayTitle=${chapter.title}\nresolution=NON_SUNOVELS_SAVED_EDITION\nrequestedUrl=NOT_OBSERVED\nfinalUrl=NOT_OBSERVED"
        val downloaded = disk.read(novel.id, chapter.id)?.let { runCatching { SunovelsSource.validateText(it) }.isSuccess } == true
        val memory = memoryCacheLock.withLock { texts[novelTextCacheKey(offlineOwner(), novel.id, chapter.id)] != null }
        val report = SunovelsReaderDiagnostics.get(chapter.id) ?: SunovelsResolutionReport(chapter,
            SunovelsOutcome.NETWORK_TRANSIENT, "NOT_ATTEMPTED", provenance = "NO_READER_EVIDENCE")
        identity + report.copy(chapter = chapter).copyReport(downloaded) + "\nmemoryCached=$memory\nindexDeclaredTotal=${indexSnapshot(novel)?.declaredTotal}\nindexRows=${indexSnapshot(novel)?.chapters?.size}\nnovelMetadataCount=${novel.chapterCount}"
    }
    internal suspend fun evictChapter(editionId: String, chapterId: String, owner: String = offlineOwner()) {
        memoryCacheLock.withLock { texts.remove(novelTextCacheKey(owner, editionId, chapterId)) }
    }
    internal fun activeDownloads(): NovelDownloadQueue? = downloadQueues[offlineOwner()]
    companion object {
        @Volatile private var instance: NovelRepository? = null
        internal fun peek(): NovelRepository? = instance
        internal suspend fun recoverDownloads(context: Context): NovelDownloadQueue? = withContext(Dispatchers.IO) {
            val active = instance?.activeDownloads()
            if (active != null) active
            else if (File(novelDownloadRoot(context.filesDir, tachiyomi.core.common.util.lang.LocalAccountScope.fileOwner()), "downloads/queue.db").isFile) get(context).downloads
            else null
        }
        fun get(context: Context): NovelRepository = instance ?: synchronized(this) { instance ?: NovelRepository(context).also { instance = it } }
    }
}
