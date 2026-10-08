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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Device-local prototype state. No account merge, manga DB, manga cache or downloads. */
class NovelRepository private constructor(context: Context) {
    private val app = context.applicationContext
    private val mutableStorageError = MutableStateFlow<String?>(null)
    val storageError = mutableStorageError.asStateFlow()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
        android.util.Log.w("MangaroNovels","Local persistence failed",error)
        mutableStorageError.value="تعذّر حفظ تغييرات القراءة. حاول مجددًا."
    })
    private val lock = Mutex()
    private val settingsSequence=java.util.concurrent.atomic.AtomicLong()
    private val positionSequence=java.util.concurrent.atomic.AtomicLong()
    private val appliedPositionSequence=mutableMapOf<String,Long>()
    private val json = Json { ignoreUnknownKeys = true }
    private val file = AtomicFile(File(app.filesDir,"novels-local/library.json"))
    private val settingsFile = AtomicFile(File(app.filesDir,"novels-local/reader-settings.json"))
    private val ready = CompletableDeferred<Unit>()
    private val mutableLibrary = MutableStateFlow<List<NovelLibraryItem>>(emptyList())
    private val mutableSettings = MutableStateFlow(NovelReaderSettings())
    val library = mutableLibrary.asStateFlow()
    val settings = mutableSettings.asStateFlow()
    val covers by lazy {
        val client=OkHttpClient.Builder().cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(10,java.util.concurrent.TimeUnit.SECONDS).callTimeout(20,java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor { chain ->
                if(!NovelHttp.allowed(chain.request().url.toString())) throw java.io.IOException("Unapproved novel cover host")
                chain.proceed(chain.request())
            }.build()
        ImageLoader.Builder(app).components {add(OkHttpNetworkFetcherFactory(callFactory={client}))}
            .memoryCache {MemoryCache.Builder().maxSizeBytes(8L*1024*1024).build()}
            .diskCache {DiskCache.Builder().directory(File(app.cacheDir,"novels-covers").toOkioPath())
                .maxSizeBytes(16L*1024*1024).build()}.build()
    }
    val sources by lazy {
        val http=NovelHttp()
        listOf(KolNovelSource(http),CeneleSource(http),SunovelsSource(http),SeaNovelSource(http))
    }
    private val rules by lazy {NovelRuleStore(app)}
    private val indexes = LinkedHashMap<String, Pair<List<NovelChapter>,Int?>>()
    private val texts = object : LinkedHashMap<String, NovelText>(4,.75f,true) {}
    init {
        io.launch {
            try {
                runCatching {rules.load()}.onFailure {android.util.Log.w("MangaroNovels","Rules restore failed; using compiled selectors",it)}
                NovelRuleUpdateJob.schedule(app)
                if(file.baseFile.exists()) mutableLibrary.value=json.decodeFromString(file.openRead().use { it.readBytes().decodeToString() })
                if(settingsFile.baseFile.exists()) mutableSettings.value=json.decodeFromString(settingsFile.openRead().use { it.readBytes().decodeToString() })
            } catch(e: Exception) { android.util.Log.w("MangaroNovels","Local state could not be restored",e) }
            finally { ready.complete(Unit) }
        }
    }
    fun source(id: String): NovelSource = sources.single { it.id==id }
    suspend fun awaitLocal() = ready.await()
    suspend fun setSaved(novel: Novel, saved: Boolean) {
        try { change {
            val old=firstOrNull { it.novel.id==novel.id }
            filterNot { it.novel.id==novel.id } + NovelLibraryItem(novel,saved,old?.position)
        } } catch(c: CancellationException) {throw c}
        catch(e: Exception) {
            android.util.Log.w("MangaroNovels","Library change failed",e)
            mutableStorageError.value="تعذّر حفظ التغيير في مكتبة الروايات. حاول مجددًا."
        }
    }
    fun savePosition(novel: Novel, chapter: NovelChapter, paragraph: Int, offset: Int, anchor: String = "") {
        val sequence=positionSequence.incrementAndGet()
        io.launch {
            change {
                if(sequence<(appliedPositionSequence[novel.id] ?: 0L)) return@change this
                appliedPositionSequence[novel.id]=sequence
                val old=firstOrNull { it.novel.id==novel.id }
                filterNot { it.novel.id==novel.id } + NovelLibraryItem(novel,old?.saved ?: false,
                    NovelReadingPosition(chapter,paragraph.coerceAtLeast(0),offset.coerceAtLeast(0),System.currentTimeMillis(),anchor))
            }
        }
    }
    fun saveSettings(settings: NovelReaderSettings) {
        val sequence=settingsSequence.incrementAndGet()
        mutableSettings.value=settings
        io.launch {
            ready.await()
            lock.withLock {
                // A late older slider write must not overwrite a newer theme/settings choice.
                if(sequence!=settingsSequence.get()) return@withLock
                write(settingsFile,json.encodeToString(settings))
                if(sequence==settingsSequence.get()) mutableSettings.value=settings
            }
        }
    }
    private suspend fun change(update: List<NovelLibraryItem>.() -> List<NovelLibraryItem>) {
        ready.await()
        withContext(Dispatchers.IO) { lock.withLock {
            val next=mutableLibrary.value.update().filter { it.saved || it.position != null }.sortedByDescending { it.position?.updatedAt ?: 0 }
            write(file,json.encodeToString(next))
            mutableLibrary.value=next
            mutableStorageError.value=null
        } }
    }
    private fun write(target: AtomicFile, value: String) {
        target.baseFile.parentFile?.mkdirs()
        val stream=target.startWrite()
        try { stream.write(value.toByteArray());target.finishWrite(stream) }
        catch(e: Exception) { target.failWrite(stream);throw e }
    }
    suspend fun chapterPage(novel: Novel, page: Int): ChapterPage {
        val result=source(novel.sourceId).chapters(novel,page)
        lock.withLock {
            val old=if(page==1) emptyList() else indexes[novel.id]?.first.orEmpty()
            indexes[novel.id]=(old+result.chapters).distinctBy { it.id } to result.nextPage
            while(indexes.size>5) indexes.remove(indexes.keys.first())
        }
        return result
    }
    suspend fun adjacent(novel: Novel, chapter: NovelChapter, forward: Boolean): NovelChapter? {
        var pair=lock.withLock { indexes[novel.id] }
        if(pair==null || pair.first.none { it.id==chapter.id }) {
            val page=if(novel.sourceId in listOf("novel.cenele","novel.sunovels")) chapter.order/50+1 else 1
            chapterPage(novel,page)
            pair=lock.withLock { indexes[novel.id] }
        }
        var list=pair?.first.orEmpty()
        var index=list.indexOfFirst { it.id==chapter.id }
        if(forward && index==list.lastIndex && pair?.second!=null) {
            chapterPage(novel,pair.second!!)
            list=lock.withLock { indexes[novel.id]?.first.orEmpty() }
            index=list.indexOfFirst { it.id==chapter.id }
        } else if(!forward && index==0 && chapter.order>=50 && novel.sourceId in listOf("novel.cenele","novel.sunovels")) {
            val earlier=source(novel.sourceId).chapters(novel,chapter.order/50).chapters
            list=(earlier+list).distinctBy { it.id };index=list.indexOfFirst { it.id==chapter.id }
            lock.withLock { indexes[novel.id]=list to pair?.second }
        }
        return if(index<0) null else list.getOrNull(index+if(forward) 1 else -1)
    }
    suspend fun chapterText(novel: Novel, chapter: NovelChapter): NovelText {
        lock.withLock { texts[chapter.id] }?.let { return it }
        val result=source(novel.sourceId).chapter(chapter)
        lock.withLock {
            texts[chapter.id]=result
            // Private memory only: never bundle or persist third-party chapter text for offline distribution.
            while(texts.size>2 || texts.values.sumOf { v -> v.paragraphs.sumOf { it.length }+v.markup.sumOf { it.length } }>1_000_000) {
                texts.remove(texts.keys.first())
            }
        }
        return result
    }
    companion object {
        @Volatile private var instance: NovelRepository?=null
        fun get(context: Context): NovelRepository = instance ?: synchronized(this) {
            instance ?: NovelRepository(context).also { instance=it }
        }
    }
}
