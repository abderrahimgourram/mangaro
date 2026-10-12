package eu.kanade.tachiyomi.novels

import android.app.Instrumentation
import android.os.Bundle
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.domain.account.*
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addFactory
import uy.kohesive.injekt.api.get
import java.io.File

/** Deterministic local storage fault, not live source evidence. No HTTP chapter requests or user DB edits. */
class NovelOfflineRecoveryInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        startActivitySync(android.content.Intent(targetContext,eu.kanade.tachiyomi.ui.main.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        var failure: Throwable? = null
        val folder = File.createTempFile("novel-offline-recovery-", "", targetContext.cacheDir).apply { delete(); check(mkdir()) }
        val pref = "offline-recovery-" + folder.name
        val account = Injekt.get<AccountFoundation>()
        var repository: NovelRepository? = null
        try { runBlocking {
            Injekt.addFactory<AccountFoundation> { AccountFoundation(GuestAccountAuth(), DisabledAccountCloudSync()) }
            val isolated = object : android.content.ContextWrapper(targetContext) {
                override fun getApplicationContext(): android.content.Context = this
                override fun getFilesDir(): File = File(folder,"files").apply { mkdirs() }
                override fun getCacheDir(): File = File(folder,"cache").apply { mkdirs() }
                override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
                    super.getSharedPreferences(pref, mode)
            }
            // Suppress only this test namespace's foreground rule refresh, retaining the real approval gate.
            isolated.getSharedPreferences("novels-rule-refresh",0).edit().putLong("last_attempt",System.currentTimeMillis()).commit()
            repository = NovelRepository::class.java.getDeclaredConstructor(android.content.Context::class.java)
                .apply { isAccessible = true }.newInstance(isolated)
            val repo = repository!!; repo.awaitLocal()
            val novel = Novel("novel.cenele","https://cenele.com/cont/offline-integrity-fixture/","Storage fixture")
            val chapter = NovelChapter(novel.url+"chapter-1/","Fixture chapter",0)
            val paragraphs = List(3) { "Deterministic storage fixture paragraph. ".repeat(5) }
            val text = NovelText(paragraphs,blocks=paragraphs.indices.map { NovelContentBlock(NovelBlockKind.TEXT,paragraph=it) } +
                NovelContentBlock(NovelBlockKind.IMAGE,imageUrl="https://cenele.com/offline-fixture.png"))
            repo.disk.write(novel.id,chapter.id,text)
            check(repo.disk.read(novel.id,chapter.id)!=null && !repo.disk.complete(novel.id,chapter.id))
            check(repo.activeDownloads()==null)
            val database = File(isolated.filesDir,"novels-local/downloads/queue.db").also { it.parentFile!!.mkdirs() }
            val db=android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(database,null)
            try {
                NovelQueueSql.schema.forEach { db.execSQL(it) }
                db.version=1
                val json=Json
                db.execSQL("INSERT INTO editions(id,source_id,novel) VALUES(?,?,?)",arrayOf(novel.id,novel.sourceId,json.encodeToString(novel)))
                db.execSQL("INSERT INTO tasks(id,edition_id,chapter,state,generation,ordinal,created,error) VALUES(?,?,?,'DONE',0,0,0,NULL)",arrayOf(novelDownloadKey(novel.id,chapter.id),novel.id,json.encodeToString(chapter)))
            } finally {db.close()}
            check(repo.activeDownloads()==null) // Persisted DONE state, fresh process-like repository.
            val loaded=repo.chapterText(novel,chapter)
            val queue=repo.downloads; queue.awaitReady()
            check(loaded.paragraphs==paragraphs.map { it.trim() } && repo.lastDataOrigin=="disk")
            check(queue.tasks.value.single().state==NovelDownloadState.FAILED) { "Missing illustration still marked DONE" }
            check(repo.disk.read(novel.id,chapter.id)!=null && repo.library.value.isEmpty())
            println("PASS missingIllustrationInvalidatesDoneWithoutErasingTextOrProgress fixture=true coldReopen=true chapterRequests=0")
        } } catch (error: Throwable) { failure=error;println("FAIL ${error.javaClass.simpleName}: ${error.message?.take(160)}") }
        finally { runBlocking {
            repository?.let { repo ->
                for (obj in listOf(repo,repo.activeDownloads())) if (obj!=null) {
                    val name=if(obj is NovelRepository) "io" else "scope"
                    val scope=obj.javaClass.getDeclaredField(name).apply {isAccessible=true}.get(obj) as CoroutineScope
                    scope.coroutineContext[Job]?.cancelAndJoin()
                }
            }
            Injekt.addFactory<AccountFoundation> {account}
            NovelRuleStore(targetContext).load()
            targetContext.deleteSharedPreferences(pref);folder.deleteRecursively()
        } }
        finish(if(failure==null)0 else 1, Bundle().apply { putString("stream",if(failure==null)"PASS isolated offline recovery" else "FAIL ${failure!!.message}") })
    }
}
