package eu.kanade.tachiyomi.novels

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File

class NovelUnifiedTest {
    @TempDir lateinit var temporary: File
    private fun edition(source: String, title: String = "حكاية العرش السماوي", author: String? = "كاتب") =
        Novel("novel.$source", "https://$source.com/novel/story", title, author = author)

    @Test fun conservativeMatchingPreservesDifferentAuthorsSequelsAndProviderRecords() {
        val a = edition("kolnovel", "حِكَايَةُ العَرْش السَّمَاوِيّ")
        val b = edition("cenele")
        NovelIdentity.matches(a,b) shouldBe true
        NovelIdentity.matches(a,b.copy(author="كاتب آخر")) shouldBe false
        NovelIdentity.matches(a,b.copy(title=b.title+" الجزء 2")) shouldBe false
        NovelIdentity.matches(a,a.copy(url="https://kolnovel.com/novel/separate")) shouldBe false
        NovelIdentity.matches(a.copy(author=null),b.copy(author=null)) shouldBe false
        NovelIdentity.matches(a,b.copy(title="حكاية أخرى عن العرش السماوي")) shouldBe false
    }
    @Test fun explicitAliasesAndTransliterationsMergeWithoutMergingFanSequels() {
        val a=edition("kolnovel","القس المجنون",null).copy(originalTitle="Reverend Insanity | Master of GU")
        val b=edition("cenele","رواية القس المجنون","غو زين رن").copy(originalTitle="Reverend Insanity")
        val c=edition("sunovels","القس المجنون",null).copy(originalTitle="Reverend Insanity")
        val d=Novel("novel.seanovel","https://seanovel.org/novels/novel-37","القس المجنون",author="Gu Zhen Ren",originalTitle="القس المجنون")
        NovelWorkReconciler.ingest(NovelCatalogState(),listOf(a,b,c,d)).works.single().editions.size shouldBe 4
        NovelIdentity.matches(a,b.copy(title="القس المجنون: الأرك الأخير (نسخة الفان)")) shouldBe false
        NovelIdentity.matches(a,b.copy(author="مؤلف مختلف",originalTitle=null,description="")) shouldBe false
    }
    @Test fun completeVerifiedCountsBeatAdvertisedCountsAndResponseSpeed() {
        val a=edition("kolnovel").copy(chapterCount=99999);val b=edition("cenele")
        var state=NovelWorkReconciler.ingest(NovelCatalogState(),listOf(a,b))
        state.works.size shouldBe 1
        state=NovelWorkReconciler.withEvidence(state,a.id,NovelEditionEvidence(true,350,true))
        state=NovelWorkReconciler.withEvidence(state,b.id,NovelEditionEvidence(true,510,true))
        state.works.single().primaryEditionId shouldBe b.id
        state=NovelWorkReconciler.withEvidence(state,b.id,NovelEditionEvidence(true,510,false))
        state.works.single().primaryEditionId shouldBe a.id
        NovelWorkReconciler.preferred(listOf(b,a),emptyMap()).id shouldBe NovelWorkReconciler.preferred(listOf(a,b),emptyMap()).id
    }
    @Test fun confirmedUnavailableEditionCannotDefeatAnUnverifiedAlternative() {
        val a=edition("kolnovel"); val b=edition("cenele")
        NovelWorkReconciler.preferred(listOf(a,b),mapOf(a.id to NovelEditionEvidence(true,3000,false))).id shouldBe b.id
    }
    @Test fun sparseCardsNeverEraseCorroboratingMetadataAndCanonicalAliasesSurvive() {
        val a=edition("kolnovel");val b=edition("cenele");val c=edition("sunovels").copy(author="autre")
        var state=NovelWorkReconciler.ingest(NovelCatalogState(),listOf(a))
        val oldId=state.works.single().id
        state=NovelWorkReconciler.ingest(state,listOf(b,c))
        state.work(oldId)!!.editions.size shouldBe 2
        val root=state.work(a.id)!!.id
        state=NovelWorkReconciler.ingest(state,listOf(a.copy(author=null)))
        state.work(a.id)!!.id shouldBe root
        state.work(a.id)!!.editions.first {it.id==a.id}.author shouldBe "كاتب"
        state=NovelWorkReconciler.ingest(state,listOf(b.copy(author="آخر")))
        state.work(a.id)!!.id shouldBe root
        state.work(a.id)!!.editions.size shouldBe 1
        (state.work(a.id)!!.id!=state.work(b.id)!!.id) shouldBe true
    }
    @Test fun bridgeMatchCannotCollapseDistinctRecordsFromOneProvider() {
        val a=edition("kolnovel");val separate=a.copy(url=a.url+"-two");val b=edition("cenele")
        val state=NovelWorkReconciler.ingest(NovelCatalogState(),listOf(a,separate,b))
        state.works.size shouldBe 3
    }
    @Test fun legacyLibraryJsonKeepsEditionPositionAndReaderSettings() {
        val original=NovelLibraryItem(edition("kolnovel"),true,NovelReadingPosition(NovelChapter("https://kolnovel.com/chapter/120","120",119),17,23,123,"anchor"))
        val json=Json {encodeDefaults=false}
        val restored=json.decodeFromString<NovelLibraryItem>(json.encodeToString(original))
        restored shouldBe original
        var state=NovelWorkReconciler.ingest(NovelCatalogState(),listOf(restored.novel,edition("cenele")))
        state=NovelWorkReconciler.withEvidence(state,edition("cenele").id,NovelEditionEvidence(true,510,true))
        UnifiedNovelLibraryItem(state.works.single(),listOf(restored)).latest!!.position shouldBe original.position
        json.decodeFromString<NovelReaderSettings>("{\"fontSize\":24,\"theme\":\"sepia\"}").font shouldBe "naskh"
    }
    private fun fake(pages: Map<Int,ChapterPage>): NovelSource = object: NovelSource {
        override val id="novel.kolnovel";override val name="test";override val baseUrl="https://kolnovel.com/"
        override suspend fun catalog(page:Int,latest:Boolean,genre:String?)=error("unused")
        override suspend fun search(query:String,page:Int)=error("unused")
        override suspend fun details(novel:Novel)=novel
        override suspend fun chapters(novel:Novel,page:Int)=pages[page] ?: error("Unexpected cursor $page")
        override suspend fun chapter(chapter:NovelChapter)=error("Chapter text must not be fetched by indexing")
    }
    private fun chapter(n:Int, volume:String?=null, volumeId:String?=null) = NovelChapter("https://kolnovel.com/chapter/$n",if(n==9) "قصة جانبية" else "الفصل $n.5",n,volume,volumeId)
    @Test fun threeThousandChaptersIncludeSpecialsAndDeduplicatePageBoundaries() = runTest {
        val pages=(1..60).associateWith {p -> ChapterPage(((p-1)*50 until p*50).map(::chapter) + if(p<60) listOf(chapter(p*50)) else emptyList(),if(p<60) p+1 else null)}
        val result=NovelChapterIndexer.collect(fake(pages),edition("kolnovel"))
        result.complete shouldBe true;result.chapters.size shouldBe 3000
        result.chapters.map {it.order} shouldBe (0..2999).toList()
        result.chapters[9].title shouldBe "قصة جانبية"
        result.fetchedPages.size shouldBe 60
    }
    @Test fun genuineVolumesKeepCountsAndOrderWithoutInventedNames() = runTest {
        val volumes=listOf(NovelVolume("v1","البداية",1000),NovelVolume("v2","العودة",0))
        val source=fake(mapOf(1 to ChapterPage(listOf(chapter(0,"البداية","v1")),2,volumes=volumes),
            2 to ChapterPage(listOf(chapter(0,"البداية","v1"),chapter(1,"البداية","v1")),1000001),
            1000001 to ChapterPage(listOf(chapter(2,"العودة","v2")),volumes=volumes)))
        val result=NovelChapterIndexer.collect(source,edition("kolnovel"))
        val groups=novelChapterGroups(result)
        groups.map {it.chapters.size} shouldBe listOf(2,1)
        groups.map {it.title} shouldBe listOf("البداية","العودة")
        result.chapters.map {it.url} shouldBe listOf(chapter(0).url,chapter(1).url,chapter(2).url)
        novelChapterGroups(result.copy(chapters=(0..200).map(::chapter),volumes=emptyList())).all {!it.realVolume} shouldBe true
    }
    @Test fun paginationCyclesAndEmptyIntermediatePagesFailInsteadOfClaimingComplete() = runTest {
        runCatching {NovelChapterIndexer.collect(fake(mapOf(1 to ChapterPage(listOf(chapter(0)),1))),edition("kolnovel"))}.isFailure shouldBe true
        runCatching {NovelChapterIndexer.collect(fake(mapOf(1 to ChapterPage(emptyList(),2))),edition("kolnovel"))}.isFailure shouldBe true
    }
    @Test fun cancellationLeavesAResumableVerifiedCursor() = runTest {
        var saved=NovelChapterIndex(edition("kolnovel").id)
        val source=fake(mapOf(1 to ChapterPage(listOf(chapter(0)),2),2 to ChapterPage(listOf(chapter(1)))))
        val job=launch {NovelChapterIndexer.collect(source,edition("kolnovel")) {saved=it;cancel()} }
        job.join();saved.complete shouldBe false;saved.nextPage shouldBe 2
        NovelChapterIndexer.collect(source,edition("kolnovel"),saved).chapters.size shouldBe 2
    }
    private fun text()=NovelText(List(4) {"فقرة عربية أصلية خاصة بهذا الاختبار للتحقق من سلامة حفظ الفصول. ".repeat(4)})
    @Test fun offlineFilesAreAtomicValidatedAndEditionSpecific() {
        val disk=NovelDownloadDisk(temporary)
        disk.write("a","1",text());disk.read("a","1") shouldBe text()
        disk.read("b","1") shouldBe null
        runCatching {disk.write("a","1",text().copy(paragraphs=listOf("empty")))}.isFailure shouldBe true
        runCatching {disk.write("a","1",text().copy(paragraphs=text().paragraphs.map {it+"changed"})) {false}}.isFailure shouldBe true
        disk.read("a","1") shouldBe text()
        temporary.walkTopDown().first {it.name.endsWith(".json.gz")}.writeText("corrupt")
        disk.read("a","1") shouldBe null
    }
    @Test fun maintenancePreservesActiveAndCompletedFilesAndIsRepeatable() {
        val disk=NovelDownloadDisk(temporary)
        disk.write("a","1",text()) {
            temporary.resolve("temporary").listFiles()!!.forEach {it.setLastModified(1)}
            disk.cleanTemporary() shouldBe 0
            true
        }
        val abandoned=temporary.resolve("temporary/stale.part");abandoned.writeText("unused");abandoned.setLastModified(1)
        val fresh=temporary.resolve("temporary/recent.part");fresh.writeText("ongoing")
        disk.cleanTemporary(cancelled={true}) shouldBe 0
        disk.cleanTemporary() shouldBe 1;disk.cleanTemporary() shouldBe 0
        fresh.exists() shouldBe true;disk.read("a","1") shouldBe text()
    }
    @Test @EnabledIfEnvironmentVariable(named="MANGARO_UNIFIED_INDEX_LIVE",matches="1")
    fun liveCompleteIndexes()=runBlocking<Unit> {
        val http=NovelHttp()
        val samples=listOf(
            KolNovelSource(http) to Novel("novel.kolnovel","https://kolnovel.com/series/dungeon-defense-wn/","دفاع الخنادق"),
            CeneleSource(http) to Novel("novel.cenele","https://cenele.com/cont/create-heaven-riwya/","انشاء القوانين السماوية"),
            SunovelsSource(http) to Novel("novel.sunovels","https://sunovels.com/novel/reverend-insanity","القس المجنون"),
            SeaNovelSource(http) to Novel("novel.seanovel","https://seanovel.org/novels/terra-nova-online-rise-of-the-strongest-player","تيرا نوفا"))
        val results=mutableListOf<String>()
        var failures=0
        for((source,n) in samples) {
            try {
                val index=NovelChapterIndexer.collect(source,n)
                index.complete shouldBe true; (index.chapters.size>50) shouldBe true
                index.chapters.distinctBy {it.id}.size shouldBe index.chapters.size
                results.add(source.name+" chapters="+index.chapters.size+" pages="+index.fetchedPages.size+" genuineVolumes="+index.volumes.size)
            } catch(e:Exception) {failures++;results.add(source.name+" FAILED="+e.javaClass.simpleName+" "+e.message)}
            println(results.last())
        }
        try {
            val n=Novel("novel.cenele","https://cenele.com/cont/mster-mysteiousdd/","سيد الغوامض")
            val index=NovelChapterIndexer.collect(CeneleSource(http),n)
            index.complete shouldBe true; (index.volumes.size>=2) shouldBe true
            val groups=novelChapterGroups(index)
            groups.sumOf {it.chapters.size} shouldBe index.chapters.size
            results.add("Cenele genuine volumes="+groups.map {it.title to it.chapters.size}+" total="+index.chapters.size)
            println(results.last())
        } catch(e:Exception) {failures++;results.add("Cenele volumes FAILED="+e.javaClass.simpleName+" "+e.message)}
        File("/tmp/mangaro-unified-index-proof.txt").writeText(results.joinToString("\n"))
        failures shouldBe 0
    }
}
