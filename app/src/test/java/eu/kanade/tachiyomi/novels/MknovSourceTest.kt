package eu.kanade.tachiyomi.novels

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Collections
import java.util.zip.GZIPInputStream

/** Recorded public metadata/index; reader body is authored fixture text. Zero live requests. */
class MknovSourceTest {
    private fun replay(name: String): String = checkNotNull(javaClass.getResourceAsStream("/novels/mknov/$name")).use {
        if (name.endsWith(".gz")) GZIPInputStream(it).bufferedReader().readText() else it.bufferedReader().readText()
    }
    private fun fixture(name: String) = Json.parseToJsonElement(replay(name)).jsonObject
    private data class Call(val endpoint: String, val body: JsonObject, val method: String, val type: String?)
    private class Harness(val handler: (String, JsonObject) -> String) {
        val calls = Collections.synchronizedList(mutableListOf<Call>())
        val http = NovelHttp(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val buffer = Buffer(); checkNotNull(request.body).writeTo(buffer)
            val body = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            val endpoint = request.url.pathSegments.last()
            calls += Call(endpoint, body, request.method, request.body?.contentType()?.toString())
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(handler(endpoint, body).toResponseBody("application/json".toMediaType())).build()
        }.build())
        val source = MknovSource(http)
    }
    private fun harness() = Harness { endpoint, _ -> replay(when(endpoint) {
        "getWorks" -> "catalog-10.json"
        "latest" -> "latest.json"
        "searchByKeyword" -> "search.json"
        "advancedSearch" -> "genre.json"
        "getWorkById" -> "details-96.json"
        "getChaptersByWorkId" -> "index-96.json.gz"
        "getChapterBatch" -> "chapter-96-first.json"
        else -> error("Unexpected endpoint: $endpoint")
    }) }
    private val novel get() = Novel(MknovSource.SOURCE_ID, "https://mknov.com/novel/96", "عبد الظل")

    @Test fun catalogUsesRealProviderIdsAndNeverTreatsLastChapterAsCount() = runBlocking<Unit> {
        val h = harness(); val page = h.source.catalog(1, false, null)
        page.novels.size shouldBe 10; page.nextPage shouldBe 2
        page.novels.first().url shouldBe novel.url
        page.novels.all { it.chapterCount == null } shouldBe true
        val call = h.calls.single()
        call.method shouldBe "POST"; call.type shouldBe "application/json; charset=utf-8"
        call.body["limit"]!!.jsonPrimitive.int shouldBe 10
        call.body["offset"]!!.jsonPrimitive.int shouldBe 0
        h.source.catalog(1, false, null) shouldBe page
        h.calls.size shouldBe 1
    }
    @Test fun concurrentCatalogLoadsAreCoalescedWithoutMoreSourceTraffic() = runBlocking<Unit> {
        val h=harness()
        coroutineScope { (1..6).map { async(Dispatchers.Default) { h.source.catalog(1, false, null) } }.awaitAll() }
        h.calls.size shouldBe 1
    }
    @Test fun latestAndGenreUseTheirOwnOfficialContracts() = runBlocking<Unit> {
        val h=harness()
        h.source.catalog(1,true,null).novels.size shouldBe 10
        h.source.catalog(1,false,"أكشن").novels.size shouldBe 10
        h.calls.map { it.endpoint } shouldBe listOf("latest","advancedSearch")
        h.calls[0].body["page"]!!.jsonPrimitive.int shouldBe 1
        h.calls[1].body["tags"]!!.jsonArray.single().jsonPrimitive.content shouldBe "أكشن"
    }
    @Test fun keywordSnapshotPaginationDoesNotReplayTheSameRemoteRequest() = runBlocking<Unit> {
        val rows=(1..23).map { buildJsonObject { put("id",it);put("name","عمل اختبار $it") } }
        val h=Harness { _,_-> buildJsonObject {put("status","success");put("total_works",rows.size);put("works",JsonArray(rows))}.toString() }
        val pages=(1..3).map { h.source.search("اختبار",it) }
        pages.map { it.novels.size } shouldBe listOf(10,10,3)
        pages.map { it.nextPage } shouldBe listOf(2,3,null)
        pages.flatMap {it.novels}.map {it.id}.distinct().size shouldBe 23
        h.calls.size shouldBe 1
    }
    @Test fun detailsPreserveEditionAndExposeActualOriginalNameAndAuthor() = runBlocking<Unit> {
        val h=harness();val details=h.source.details(novel)
        details.id shouldBe novel.id;details.author shouldBe "Guiltythree"
        details.originalTitle shouldBe "Shadow Slave"
        details.alternativeTitles.contains("عبد الظلام") shouldBe true
        details.chapterCount shouldBe null
    }
    @Test fun recordedFullIndexContains3218DistinctEntriesIncludingChapterZero() = runBlocking<Unit> {
        val h=harness();val index=h.source.chapters(novel,1)
        index.chapters.size shouldBe 3218;index.declaredTotal shouldBe 3218;index.nextPage shouldBe null
        index.chapters.map {it.id}.distinct().size shouldBe 3218
        index.chapters.first().url shouldBe "https://mknov.com/novel/96/chapter/65671"
        index.chapters.last().url shouldBe "https://mknov.com/novel/96/chapter/5864756"
        index.chapters.map { it.order } shouldBe (0..3217).toList()
        index.chapters.all {it.available} shouldBe true
        h.calls.single().body["id"]!!.jsonPrimitive.isString shouldBe false
    }
    @Test fun readerUsesValidatedIndexMembershipAndDoesNotDownloadNeighborBodies() = runBlocking<Unit> {
        val h=harness();val first=h.source.chapters(novel,1).chapters.first()
        val text=h.source.chapter(first)
        text.paragraphs.size shouldBe 5
        validateNovelRepositoryContent(MknovSource.SOURCE_ID,text)
        h.calls.map {it.endpoint} shouldBe listOf("getChaptersByWorkId","getChapterBatch")
        h.calls.last().body["id"]!!.jsonPrimitive.long shouldBe 65671L
    }
    @Test fun restoredDirectChapterVerifiesOwnershipBeforeReturningText() = runBlocking<Unit> {
        val h=harness()
        h.source.chapter(NovelChapter("https://mknov.com/novel/96/chapter/65671","",0)).paragraphs.size shouldBe 5
        h.calls.map {it.endpoint} shouldBe listOf("getChaptersByWorkId","getChapterBatch")
        val wrong=harness()
        assertThrows<NovelSourceFailure> { runBlocking { wrong.source.chapter(NovelChapter("https://mknov.com/novel/37/chapter/65671","",0)) } }.code shouldBe "MKNOV_IDENTITY"
        wrong.calls.none {it.endpoint=="getChapterBatch"} shouldBe true
    }
    @Test fun invalidAndTruncatedIndexNeverReplacesAValidSnapshot() = runBlocking<Unit> {
        var broken=true
        val h=Harness { endpoint,_->
            check(endpoint=="getChaptersByWorkId")
            if(broken) JsonObject(fixture("index-96.json.gz")+("total_chapters" to JsonPrimitive(9999))).toString()
            else replay("index-96.json.gz")
        }
        assertThrows<NovelSourceFailure> {runBlocking{h.source.chapters(novel,1)}}.code shouldBe "MKNOV_INVALID_RESPONSE"
        broken=false
        h.source.chapters(novel,1).chapters.size shouldBe 3218
        h.calls.size shouldBe 2
    }
    @Test fun missingEmptyAndWrongChapterResponsesAreNeverReadable() = runBlocking<Unit> {
        for(change in listOf("id","content","is_free")) {
            val h=Harness { endpoint,_-> if(endpoint=="getChaptersByWorkId") replay("index-96.json.gz") else {
                val raw=fixture("chapter-96-first.json")
                val data=raw["data"]!!.jsonObject+(change to when(change){"id"->JsonPrimitive(99);"content"->JsonPrimitive("");else->JsonPrimitive(false)})
                JsonObject(raw+("data" to JsonObject(data))).toString()
            } }
            assertThrows<Exception> {runBlocking{h.source.chapter(NovelChapter("https://mknov.com/novel/96/chapter/65671","",0))}}
        }
    }
    @Test fun newDomainsAreNarrowAndSignedRulesRemainUnchanged() {
        NovelHttp.allowed("https://mknov.com/novel/96") shouldBe true
        NovelHttp.allowed("https://api.mknov.com/v1/getChapterBatch") shouldBe false
        MknovSource.apiUrlAllowed("https://api.mknov.com/v1/getChapterBatch") shouldBe true
        listOf("https://api.mknov.com/v1/get-favorites","https://api.mknov.com/v1/getWorks?token=a",
            "https://api.mknov.com/v1/getWorks#x","http://api.mknov.com/v1/getWorks",
            "https://api.mknov.com.evil.test/v1/getWorks","https://user@api.mknov.com/v1/getWorks").forEach {
            MknovSource.apiUrlAllowed(it) shouldBe false
        }
        NovelRules.domains.keys shouldBe setOf("novel.kolnovel","novel.cenele","novel.sunovels","novel.seanovel")
        NovelSourceAvailability.active(MknovSource.SOURCE_ID) shouldBe true
    }
    @Test fun knownShadowSlaveCardsReuseExistingCanonicalWorkWithoutMergingChapters() = runBlocking<Unit> {
        val h=harness();val mknov=h.source.catalog(1,false,null).novels.first()
        val sea=Novel("novel.seanovel","https://seanovel.org/novels/shadow-slave","عنوان آخر",
            author="Guiltythree",originalTitle="Shadow Slave",chapterCount=33333)
        val state=NovelWorkReconciler.ingest(NovelCatalogState(),listOf(sea,mknov,mknov))
        state.works.size shouldBe 1;state.works.single().editions.size shouldBe 2
        val indexed=NovelWorkReconciler.withEvidence(state,mknov.id,NovelEditionEvidence(true,3218,true))
        val preferred=NovelWorkReconciler.withEvidence(indexed,sea.id,NovelEditionEvidence(true,10,true))
        preferred.works.single().primary.id shouldBe mknov.id
        NovelCatalogCards.project(preferred).size shouldBe 1
        val more=NovelWorkReconciler.withEvidence(preferred,sea.id,NovelEditionEvidence(true,4000,true))
        more.works.single().primary.id shouldBe sea.id
        sea.chapterCount shouldBe 33333;mknov.url shouldBe novel.url
        val different=sea.copy(url=sea.url+"-another",author="Different author")
        NovelWorkReconciler.ingest(state,listOf(different)).works.size shouldBe 2
    }
    @Test fun existingFormPostEncodingIsUnchanged() = runBlocking<Unit> {
        var encoded="";var type=""
        val client=OkHttpClient.Builder().addInterceptor {chain->
            val request=chain.request();val buf=Buffer();request.body!!.writeTo(buf)
            encoded=buf.readUtf8();type=request.body!!.contentType().toString()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("ok".toResponseBody("text/plain".toMediaType())).build()
        }.build()
        NovelHttp(client).post("https://cenele.com/wp-admin/admin-ajax.php",mapOf("action" to "test action")) shouldBe "ok"
        java.net.URLDecoder.decode(encoded,"UTF-8") shouldBe "action=test action"
        type shouldBe "application/x-www-form-urlencoded"
    }
}
