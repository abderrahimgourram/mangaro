package eu.kanade.tachiyomi.novels

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Storage regressions use authored fixtures; live-response replay is a separate acceptance test. */
class NovelDownloadSourceRegressionTest {
    @TempDir lateinit var root: File
    private val sea = "novel.seanovel:https://seanovel.org/novels/storage-contract"
    private val chapter = "https://seanovel.org/novels/storage-contract/chapters/1"
    private val text = NovelText(listOf(
        "وصل المسافر إلى المدينة بعد رحلة طويلة، فجلس بجانب النهر ينتظر صديقه. ".repeat(20),
        "حين أشرقت الشمس فتح الباب وتابع طريقه عبر الوادي، محتفظًا برسالة المعلم. ".repeat(20),
    ))
    @Test fun twoBlockSeaChapterSurvivesDurableWriteAndNewDiskInstance() {
        val disk = NovelDownloadDisk(root)
        disk.write(sea, chapter, text)
        disk.complete(sea, chapter) shouldBe true
        NovelDownloadDisk(root).read(sea, chapter) shouldBe text
        NovelDownloadDisk(root).complete(sea, chapter) shouldBe true
        disk.write(sea, chapter, text)
        File(root, "chapters").walkTopDown().count { it.isFile } shouldBe 1
    }
    @Test fun sourceScopedGateKeepsKolnovelAndCeneleThreeBlockContract() {
        for (source in listOf("kolnovel", "cenele")) {
            runCatching { NovelDownloadDisk(root).write("novel.$source:https://$source.com/book", chapter, text) }.isFailure shouldBe true
        }
    }
    @Test fun navigationLocksErrorsAndInsufficientTextNeverBecomeDownloads() {
        for (bad in listOf(NovelText(listOf("السابق", "التالي")), NovelText(listOf("هذا الفصل مقفل", text.paragraphs.last())),
            NovelText(listOf("يرجى تسجيل الدخول", text.paragraphs.last())), NovelText(listOf("الفصل غير موجود", text.paragraphs.last())),
            NovelText(listOf("قصير", "ناقص")))) {
            runCatching { NovelDownloadDisk(root).write(sea, chapter, bad) }.isFailure shouldBe true
            NovelDownloadDisk(root).complete(sea, chapter) shouldBe false
        }
    }
    @Test fun cancelledOrInvalidReplacementCannotCorruptPreviousCompleteChapter() {
        val disk = NovelDownloadDisk(root); disk.write(sea, chapter, text)
        runCatching { disk.write(sea, chapter, text.copy(paragraphs = text.paragraphs.reversed())) { false } }.isFailure shouldBe true
        runCatching { disk.write(sea, chapter, NovelText(listOf("empty"))) }.isFailure shouldBe true
        NovelDownloadDisk(root).read(sea, chapter) shouldBe text
        File(root,"temporary").listFiles().orEmpty().size shouldBe 0
    }
    @Test fun checksumsAndEditionIdentityRemainMandatoryAfterRestart() {
        val disk = NovelDownloadDisk(root); disk.write(sea, chapter, text)
        disk.read(sea + "-other", chapter) shouldBe null
        disk.read(sea, chapter + "2") shouldBe null
        val saved = File(root,"chapters").walkTopDown().single { it.isFile }
        val payload=java.util.zip.GZIPInputStream(saved.inputStream()).reader().readText()
        val parsed=kotlinx.serialization.json.Json.parseToJsonElement(payload) as kotlinx.serialization.json.JsonObject
        val tampered=kotlinx.serialization.json.JsonObject(parsed.toMutableMap().apply {
            put("checksum",kotlinx.serialization.json.JsonPrimitive("0".repeat(64)))
        }).toString()
        java.util.zip.GZIPOutputStream(saved.outputStream()).use {it.write(tampered.toByteArray())}
        NovelDownloadDisk(root).read(sea, chapter) shouldBe null
        saved.writeBytes(byteArrayOf(1,2,3))
        NovelDownloadDisk(root).read(sea, chapter) shouldBe null
    }
    private val responses get() = File("src/sunovelsAcceptance/assets/novel-recovery").takeIf { it.isDirectory }
        ?: File("app/src/sunovelsAcceptance/assets/novel-recovery")
    private fun replay(name: String) = java.util.zip.GZIPInputStream(File(responses,name).inputStream()).bufferedReader().use { it.readText() }
    @Test fun actualSeaResponsesRoundTripWithSourceValidationAndNoSchemaChange() {
        for (n in listOf(1,13)) {
            val ch = NovelChapter("https://seanovel.org/novels/the-birth-of-the-devils-sword/chapters/$n", "fixture", n)
            val text = SeaNovelSource(NovelHttp()).parseChapter(replay("sea-chapter$n.body.gz"),ch)
            text.paragraphs.size shouldBe if (n == 1) 2 else 58
            val actualEdition="novel.seanovel:https://seanovel.org/novels/the-birth-of-the-devils-sword"
            NovelDownloadDisk(root).write(actualEdition,ch.id,text)
            NovelDownloadDisk(root).read(actualEdition,ch.id) shouldBe text
        }
    }
    @Test fun actualFadaInterruptedResponseCannotBeAcceptedAsCompleteText() = kotlinx.coroutines.runBlocking {
        val raw = replay("fada-chapter0.body.gz")
        val url = "https://cenele.com/cont/fixture/one/"
        var response = raw
        val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200)
                .message("saved response").body(okhttp3.ResponseBody.create(null,response)).build()
        }.build()
        try {
            val source = CeneleSource(NovelHttp(client)); val ch = NovelChapter(url,"fixture",0)
            val good = source.chapter(ch)
            (good.paragraphs.sumOf { it.length } > 5000) shouldBe true
            response = raw.substring(0,(raw.indexOf("<story-layer")+raw.indexOf("</story-layer>"))/2)
            (runCatching { source.chapter(ch) }.exceptionOrNull() as NovelSourceFailure).code shouldBe "CENELE_DOCUMENT_TRUNCATED"
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }

    @Test fun reportedFadaFirstThreeChaptersParseAndPersistUsingActualPublicResponses() = kotlinx.coroutines.runBlocking {
        val records = kotlinx.serialization.json.Json.parseToJsonElement(File(responses,"responses.json").readText()) as kotlinx.serialization.json.JsonArray
        val third = kotlinx.serialization.json.Json.parseToJsonElement(javaClass.getResourceAsStream("/novels-recovery/fada-chapter3.json")!!.reader().readText()) as kotlinx.serialization.json.JsonObject
        val all = listOf(0,1).map { i ->
            val meta = records.map { it as kotlinx.serialization.json.JsonObject }.single { (it["asset"] as kotlinx.serialization.json.JsonPrimitive).content == "fada-chapter$i.body.gz" }
            (meta["url"] as kotlinx.serialization.json.JsonPrimitive).content to replay("fada-chapter$i.body.gz")
        } + ((third["url"] as kotlinx.serialization.json.JsonPrimitive).content to java.util.zip.GZIPInputStream(javaClass.getResourceAsStream("/novels-recovery/fada-chapter3.html.gzip")!!).reader().readText())
        for ((i,pair) in all.withIndex()) {
            val (url,body)=pair
            val client=okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
                chain.request().url.toString() shouldBe url
                okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200)
                    .message("actual recorded public response").body(okhttp3.ResponseBody.create(null,body)).build()
            }.build()
            try {
                val text=CeneleSource(NovelHttp(client)).chapter(NovelChapter(url,"الفصل ${i+1}",i))
                (text.paragraphs.sumOf {it.length}>5000) shouldBe true
                if (i==0) {
                    val expected=(kotlinx.serialization.json.Json.parseToJsonElement(File(responses,"fada-illustration.json").readText()) as kotlinx.serialization.json.JsonObject)["url"] as kotlinx.serialization.json.JsonPrimitive
                    text.blocks.single {it.kind==NovelBlockKind.IMAGE}.imageUrl shouldBe expected.content
                    val chapter=NovelChapter(url,"الفصل 1",0)
                    CeneleSource.staleIllustration(chapter,text) shouldBe false
                    CeneleSource.staleIllustration(chapter,text.copy(blocks=text.blocks.map {if(it.kind==NovelBlockKind.IMAGE) it.copy(imageUrl=url) else it})) shouldBe true
                }
                val edition="novel.cenele:https://cenele.com/cont/rene-immortal/"
                NovelDownloadDisk(root).write(edition,url,text)
                NovelDownloadDisk(root).read(edition,url) shouldBe text
                // Chapter 1's illustration is exercised with actual Android BitmapFactory in the R8 queue test.
                NovelDownloadDisk(root).complete(edition,url) shouldBe (i!=0)
            } finally {client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()}
        }
    }

    @Test fun emptySrcsetCandidatesNeverResolveToTheChapterHtmlUrl() {
        val url="https://cenele.com/cont/book/one/"
        val paragraphs=(1..3).joinToString("") { "<p>" + "نص اختبار عربي واضح يحافظ على جميع فقرات القصة. ".repeat(5) + "</p>" }
        fun image(attrs:String)=extractNovelContent(org.jsoup.Jsoup.parse("<div id=story>$paragraphs<img $attrs></div>",url).selectFirst("#story")!!).blocks.single {it.kind==NovelBlockKind.IMAGE}
        image("src='https://riwyat.com/old.jpg' srcset='https://cenele.com/story.jpg 1080w'").imageUrl shouldBe "https://cenele.com/story.jpg"
        image("src='https://riwyat.com/old.jpg' data-srcset='  ' srcset=' '").imageUrl shouldBe null
        image("src='https://cenele.com/direct.jpg'").imageUrl shouldBe "https://cenele.com/direct.jpg"
        image("data-srcset='https://cenele.com/lazy.jpg 2x' srcset='https://cenele.com/other.jpg 1x'").imageUrl shouldBe "https://cenele.com/lazy.jpg"
    }

    @Test fun kolnovelCutoffAtTwoHundredRowsCannotBecomeACompleteCachedIndex() = kotlinx.coroutines.runBlocking {
        val rows=kotlinx.serialization.json.Json.parseToJsonElement(File(responses,"responses.json").readText()) as kotlinx.serialization.json.JsonArray
        val meta=rows.map {it as kotlinx.serialization.json.JsonObject}.single {(it["asset"] as kotlinx.serialization.json.JsonPrimitive).content=="kol-index.html.gz"}
        val url=(meta["url"] as kotlinx.serialization.json.JsonPrimitive).content
        val good=replay("kol-index.html.gz")
        val damaged=org.jsoup.Jsoup.parse(good,url).apply {select(".eplister li[data-id]").drop(200).forEach {it.remove()} }.outerHtml().substringBeforeLast("</html>")
        var body=damaged;var calls=0
        val client=okhttp3.OkHttpClient.Builder().addInterceptor {chain ->
            calls++;okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("recorded response").body(okhttp3.ResponseBody.create(null,body)).build()
        }.build()
        try {
            val source=KolNovelSource(NovelHttp(client));val novel=Novel(source.id,url,"القس المجنون",chapterCount=2372)
            (runCatching {NovelChapterIndexer.collect(source,novel)}.exceptionOrNull() as NovelSourceFailure).code shouldBe "KOL_INDEX_TRUNCATED"
            body=good
            val index=NovelChapterIndexer.collect(source,novel)
            index.complete shouldBe true;index.chapters.size shouldBe 2372;calls shouldBe 2
        } finally {client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()}
    }

}
