package eu.kanade.tachiyomi.novels

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import java.util.zip.GZIPInputStream

/** Actual public API responses recorded 2026-10-14; no live requests in these tests. */
class NovelIndexRecoveryTest {
    private fun fixture(slug: String) = GZIPInputStream(javaClass.getResourceAsStream("/novels-recovery/$slug-index.json.gzip")!!)
        .bufferedReader().use { it.readText() }
    private fun http(raw: () -> String) = NovelHttp(OkHttpClient.Builder().addInterceptor { c ->
        Response.Builder().request(c.request()).protocol(Protocol.HTTP_1_1).code(200).message("recorded public response")
            .body(raw().toResponseBody()).build()
    }.build())
    @Test fun shadowSlavePublicIndexRetainsZeroDecimalAndEverySourceIdentity(): Unit = runBlocking {
        val raw = fixture("shadow-slave")
        val novel = Novel(SeaNovelSource.SOURCE_ID,"https://seanovel.org/novels/shadow-slave","عبد الظل",chapterCount=3190)
        val result = NovelChapterIndexer.collect(SeaNovelSource(http { raw }), novel)
        result.complete shouldBe true
        result.chapters.size shouldBe 3190
        result.chapters.first().url shouldBe novel.url + "/chapters/0"
        val ids = Json.parseToJsonElement(raw).jsonObject.getValue("chapters").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
        result.chapters.map { it.id } shouldBe ids.map { novel.url + "/chapters/" + it }
        result.chapters.any { it.url.endsWith("/1841.1") } shouldBe true
        result.chapters.forEach { SeaNovelSource.requireOwnership(novel,it) }
    }
    @Test fun independentSeaWorksKeepTheirExactExistingIds(): Unit = runBlocking {
        for ((slug,total) in listOf("the-birth-of-the-devils-sword" to 2335,"shadow-hack" to 990)) {
            val novel=Novel(SeaNovelSource.SOURCE_ID,"https://seanovel.org/novels/$slug",slug,chapterCount=total)
            val result=NovelChapterIndexer.collect(SeaNovelSource(http {fixture(slug)}),novel)
            result.complete shouldBe true; result.chapters.size shouldBe total
            result.chapters.first().id shouldBe novel.url + "/chapters/1"
        }
    }
    @Test fun duplicateNegativeExponentAndForeignChapterIdentitiesRemainInvalid(): Unit = runBlocking {
        val novel=Novel(SeaNovelSource.SOURCE_ID,"https://seanovel.org/novels/shadow-slave","عبد الظل")
        for (ids in listOf("0,0","-1,1","1e2,101","1,1.0")) {
            val raw="""{"slug":"shadow-slave","chapters_count":2,"chapters":[${ids.split(',').joinToString { "{\"id\":$it,\"title\":\"فصل\"}" }}]}"""
            runCatching { SeaNovelSource(http {raw}).chapters(novel,1) }.isFailure shouldBe true
        }
        runCatching { SeaNovelSource.requireOwnership(novel,NovelChapter("https://seanovel.org/novels/other/chapters/0","فصل",0)) }.isFailure shouldBe true
    }

    @Test fun recordedKolIndexesContainLaterChaptersWithoutAnInventedContinuation(): Unit = runBlocking {
        for (name in listOf("kol-reverend-index","kol-maria-index")) {
            val raw=GZIPInputStream(javaClass.getResourceAsStream("/novels-recovery/$name.html.gzip")!!).bufferedReader().use {it.readText()}
            val doc=org.jsoup.Jsoup.parse(raw)
            val url=if(name=="kol-reverend-index") "https://kolnovel.com/series/%D8%A7%D9%84%D9%82%D8%B3-%D8%A7%D9%84%D9%85%D8%AC%D9%86%D9%88%D9%86/" else "https://kolnovel.com/series/the-empty-box-and-zeroth-maria/"
            val novel=Novel("novel.kolnovel",url,name)
            val result=NovelChapterIndexer.collect(KolNovelSource(http {raw}),novel)
            result.complete shouldBe true
            result.chapters.size shouldBe doc.select(".eplister li[data-id] > a").size
            if(name=="kol-reverend-index") {
                result.chapters.size shouldBe 2372
                for(n in listOf(1,2,3,199,200,201,399,400,401,599,600,601,2000)) {
                    val actual = doc.select(".eplister li[data-id] > a").single { Regex("الفصل\\s+$n\\s*$").containsMatchIn(it.selectFirst(".epl-num")!!.text()) }.attr("href")
                    result.chapters.any { it.url == actual } shouldBe true
                }
            }
        }
    }
    @Test fun failed3190IndexCannotBecomeVerifiedEmptyAndGoodRowsSurvive() {
        val edition="novel.seanovel:https://seanovel.org/novels/shadow-slave"
        val empty=NovelChapterIndex(edition,declaredTotal=3190)
        val failure=NovelIndexRecovery.failed(empty,NovelSourceFailure("retry","bad ID",code="SEA_INDEX_ID_INVALID"))
        failure.state() shouldBe NovelIndexState.INVALID_SOURCE_RESPONSE
        failure.complete shouldBe false; failure.chapters.size shouldBe 0
        val row=NovelChapter("https://seanovel.org/novels/shadow-slave/chapters/0","تعريف الرتب",0)
        val good=empty.copy(chapters=listOf(row),nextPage=null,complete=true,updatedAt=123)
        val failed=NovelIndexRecovery.failed(good,java.io.IOException("interrupted"))
        failed.state() shouldBe NovelIndexState.TEMPORARY_FAILURE
        failed.chapters shouldBe good.chapters; failed.updatedAt shouldBe 123L
        failed.state(loading=true) shouldBe NovelIndexState.LOADING
        empty.copy(complete=true,nextPage=null,declaredTotal=0).state() shouldBe NovelIndexState.VERIFIED_EMPTY
        empty.state() shouldBe NovelIndexState.NOT_LOADED
    }
    @Test fun refreshProjectionImmediatelyExposesNewVerifiedRowsAndRetainsOldOnes() {
        fun ch(n:Int)=NovelChapter("https://kolnovel.com/chapter/$n/","فصل $n",n)
        val old=NovelChapterIndex("edition",listOf(ch(1)),nextPage=null,complete=false,updatedAt=1,parserKey="old")
        val incoming=NovelChapterIndex("edition",listOf(ch(1),ch(2)),nextPage=3,updatedAt=2,parserKey="new")
        val visible=NovelIndexRecovery.visible(old,incoming)
        visible.chapters.map {it.id} shouldBe incoming.chapters.map {it.id}
        visible.complete shouldBe false; visible.nextPage shouldBe 3
        // Display projections cannot contaminate the independent collector checkpoint.
        visible.parserKey shouldBe "old";visible.updatedAt shouldBe 1L
        val omitted=NovelIndexRecovery.visible(old,incoming.copy(chapters=listOf(ch(2))))
        omitted.chapters.map {it.id}.toSet() shouldBe setOf(ch(1).id,ch(2).id)
    }
    @Test fun cyclesAreRejectedBeforePoisoningTheDurableCheckpoint(): Unit = runBlocking {
        val novel=Novel("novel.kolnovel","https://kolnovel.com/series/mock/","mock")
        val source=object:NovelSource {
            override val id=novel.sourceId; override val name="mock";override val baseUrl="https://kolnovel.com/"
            override suspend fun chapters(novel:Novel,page:Int)=ChapterPage(listOf(NovelChapter("https://kolnovel.com/c/$page/","chapter",page)),nextPage=page)
            override suspend fun chapter(chapter:NovelChapter):NovelText=error("not requested")
            override suspend fun details(novel:Novel)=novel
            override suspend fun catalog(page:Int,latest:Boolean,genre:String?):NovelPage=error("unused")
            override suspend fun search(query:String,page:Int):NovelPage=error("unused")
        }
        var writes=0
        runCatching {NovelChapterIndexer.collect(source,novel) {writes++}}.isFailure shouldBe true
        writes shouldBe 0
    }
    @Test fun recordedRealChapterRangesAndIndependentWorksRemainReadable(): Unit = runBlocking {
        val records=Json.parseToJsonElement(javaClass.getResource("/novels-recovery/live-chapter-checks.json")!!.readText()).jsonArray
        for(record in records) {
            val r=record.jsonObject; r.getValue("status").jsonPrimitive.int shouldBe 200
            val name=r.getValue("name").jsonPrimitive.content
            val raw=GZIPInputStream(javaClass.getResourceAsStream("/novels-recovery/$name.html.gzip")!!).bufferedReader().use {it.readText()}
            val url=r.getValue("url").jsonPrimitive.content
            val source=if(name.startsWith("sea-")) SeaNovelSource(http {raw}) else KolNovelSource(http {raw})
            val text=source.chapter(NovelChapter(url,"recorded chapter",0))
            (text.paragraphs.sumOf {it.length} > 200) shouldBe true
        }
    }
    @Test fun exact203SeaAndKolAdaptersProduceTheSamePublishedIdentities(): Unit = runBlocking {
        for(slug in listOf("shadow-slave","the-birth-of-the-devils-sword","shadow-hack")) {
            val raw=fixture(slug); val novel=Novel(SeaNovelSource.SOURCE_ID,"https://seanovel.org/novels/$slug",slug)
            val baseline=SeaNovel203Fixture(http {raw}).chapters(novel,1)
            val repaired=SeaNovelSource(http {raw}).chapters(novel,1)
            repaired.chapters shouldBe baseline.chapters
        }
        for(name in listOf("kol-reverend-index","kol-maria-index")) {
            val raw=GZIPInputStream(javaClass.getResourceAsStream("/novels-recovery/$name.html.gzip")!!).bufferedReader().use {it.readText()}
            val novel=Novel("novel.kolnovel","https://kolnovel.com/series/replay/",name)
            KolNovelSource(http {raw}).chapters(novel,1).chapters shouldBe KolNovel203Fixture(http {raw}).chapters(novel,1).chapters
        }
    }
    @Test fun fullRecordedCeneleTraversalIsProgressiveAndResumesFromVerifiedPages(): Unit = runBlocking {
        val records=Json.parseToJsonElement(javaClass.getResource("/novels-recovery/cenele-index-responses.json")!!.readText()).jsonArray.map {it.jsonObject}
        fun raw(name:String)=GZIPInputStream(javaClass.getResourceAsStream("/novels-recovery/$name.gz")!!).bufferedReader().use {it.readText()}
        var requests=0
        val client=OkHttpClient.Builder().addInterceptor {c ->
            requests++
            val name=if(c.request().method=="GET") "cenele-full-index" else {
                val body=c.request().body as FormBody
                val fields=(0 until body.size).associate {body.name(it) to body.value(it)}
                if(fields["meta_only"]=="1") "cenele-full-volumes" else "cenele-v${fields.getValue("volume")}-p${fields.getValue("page") }"
            }
            Response.Builder().request(c.request()).protocol(Protocol.HTTP_1_1).code(200).message("recorded AJAX")
                .body(raw(name).toResponseBody()).build()
        }.build()
        val novel=Novel("novel.cenele","https://cenele.com/cont/rene-immortal/","الخالد المرتد")
        val source=CeneleSource(NovelHttp(client))
        var index=NovelChapterIndexer.collect(source,novel,maxPages=2)
        index.complete shouldBe false;index.chapters.size shouldBe 140
        // Actual serialized cache restoration, not reuse of an in-memory checkpoint.
        index=Json.decodeFromString<NovelChapterIndex>(Json.encodeToString(NovelChapterIndex.serializer(),index))
        val observed=mutableListOf<Int>()
        val result=NovelChapterIndexer.continueBatches {
            NovelChapterIndexer.collect(source,novel,index,maxPages=8) {index=it;observed+=it.chapters.size}
        }
        result.complete shouldBe true;result.chapters.size shouldBe 2092
        result.chapters.map {it.id}.distinct().size shouldBe 2092
        (observed.any {it>600}) shouldBe true
        requests shouldBe records.size
    }
    @Test fun failureBeforeTheFirstRefreshPageCannotPoisonACompletedRetryCursor() {
        val old=NovelChapterIndex("edition",listOf(NovelChapter("https://kolnovel.com/c/1/","chapter",0)),complete=true,nextPage=null,fetchedPages=setOf(1),parserKey="current",updatedAt=123)
        val empty=NovelChapterIndex("edition",parserKey="current")
        val visible=NovelIndexRecovery.visible(old,empty)
        visible shouldBe old
        NovelIndexRecovery.invalidContinuation(old.copy(complete=false,nextPage=1)) shouldBe true
        NovelIndexRecovery.invalidContinuation(old.copy(complete=false,nextPage=null)) shouldBe true
        NovelIndexRecovery.invalidContinuation(old.copy(complete=false,nextPage=2)) shouldBe false
        NovelIndexRecovery.invalidContinuation(old) shouldBe false
        val failed=NovelIndexRecovery.failed(visible,NovelSourceFailure("retry","HTTP503",httpStatus=503))
        failed.complete shouldBe true; failed.nextPage shouldBe null; failed.fetchedPages shouldBe setOf(1)
        failed.state() shouldBe NovelIndexState.TEMPORARY_FAILURE
    }
}
