package eu.kanade.tachiyomi.novels

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import java.nio.file.Path

class NovelSourceTest {
    @Test fun cataloguePaginationUsesTheInspectedNextLinks() {
        hasNextNovelPage(Jsoup.parse("<div class=hpage><a class=r href='?page=2'>Next</a></div>")) shouldBe true
        hasNextNovelPage(Jsoup.parse("<nav class=nhv-archive-pagination><a class='next page-numbers' href='/cont/page/2/'>التالي</a></nav>")) shouldBe true
        hasNextNovelPage(Jsoup.parse("<a class='prev page-numbers' href='/cont/'>السابق</a>")) shouldBe false
    }
    @Test
    @EnabledIfEnvironmentVariable(named="MANGARO_NOVEL_PAGE_PROBE", matches="1")
    fun liveCataloguePagination() = runBlocking<Unit> {
        val http=NovelHttp()
        for(source in listOf(KolNovelSource(http),CeneleSource(http))) {
            val first=source.catalog()
            first.nextPage shouldBe 2
            val second=source.catalog(first.nextPage!!)
            second.novels.isNotEmpty() shouldBe true
            second.novels.any {item -> first.novels.none {it.id==item.id}} shouldBe true
            println(source.id+" first-page="+first.novels.size+" second-page="+second.novels.size)
        }
    }
    @Test
    @EnabledIfEnvironmentVariable(named="MANGARO_NOVEL_SEA_PROBE", matches="1")
    fun seaPublicParagraphVisibility() = runBlocking<Unit> {
        if(System.getenv("MANGARO_NOVEL_SEA_PROBE")!="1") return@runBlocking
        val text=SeaNovelSource(NovelHttp()).chapter(NovelChapter(
            "https://seanovel.org/novels/terra-nova-online-rise-of-the-strongest-player/chapters/1","الفصل 1.0",0))
        (text.paragraphs.size>3) shouldBe true
        text.paragraphs.any {it.startsWith("أنت تقرأ الفصل")} shouldBe false
        println("SeaNovel visible-story paragraphs="+text.paragraphs.size+" characters="+text.paragraphs.sumOf {it.length})
    }
    @Test fun domainIsolation() {
        listOf("https://kolnovel.com/series/book/","https://cenele.com/cont/book/","https://sunovels.com/novel/book","https://seanovel.org/novels/book").forEach {NovelHttp.allowed(it) shouldBe true}
        listOf("http://kolnovel.com/","https://kolnovel.com.evil.test/","https://x@cenele.com/","file:///tmp/a","https://seanovel.org:8443/","https://riwyat.com/").forEach {NovelHttp.allowed(it) shouldBe false}
    }
    @Test fun identitiesStaySourceSpecificAndPunctuationIsUnchanged() {
        val a=Novel("novel.kolnovel","https://kolnovel.com/series/x/","53.v — حكاية")
        val b=Novel("novel.cenele","https://cenele.com/cont/x/",a.title)
        (a.id!=b.id) shouldBe true
        Json.decodeFromString<Novel>(Json.encodeToString(Novel.serializer(),a)) shouldBe a
        val settings=NovelReaderSettings(fontSize=24f,theme="sepia")
        Json.decodeFromString<NovelReaderSettings>(Json.encodeToString(NovelReaderSettings.serializer(),settings)) shouldBe settings
    }
    @Test fun flightDataIsParsedAsJsonAndNeverExecuted() {
        val payload="1:[\"x\",{\"href\":\"/novel/book\",\"children\":[{\"src\":\"/uploads/book.webp\"}]}]\n"
        val encoded=Json.encodeToString(JsonArray.serializer(),buildJsonArray {add(1);add(payload)})
        val document=Jsoup.parse("<script>self.__next_f.push("+encoded+")</script>")
        flightObjects(document).flatMap {it.objects().toList()}.any {it.string("src")=="/uploads/book.webp"} shouldBe true
    }
    @Test fun signedRulesRejectTamperingAndForeignDomains() {
        val root=Path.of("src/main/assets/novels").takeIf {Files.exists(it)} ?: Path.of("app/src/main/assets/novels")
        val envelope=Json.parseToJsonElement(Files.readString(root.resolve("rules-envelope.json"))).jsonObject
        val payload=java.util.Base64.getDecoder().decode(envelope.string("payload"))
        val key=Files.readAllBytes(root.resolve("rules-public.der"))
        NovelRuleSignature.verify(payload,envelope.string("signature"),key) shouldBe true
        NovelRuleSignature.verify(payload+byteArrayOf(0),envelope.string("signature"),key) shouldBe false
        NovelRules.validate(payload).second.size shouldBe 4
        runCatching {NovelRules.validate(payload.decodeToString().replace("kolnovel.com","foreign.invalid").toByteArray())}.isFailure shouldBe true
        runCatching {NovelRules.validate(payload.decodeToString().replace("#kol_content","body").toByteArray())}.isFailure shouldBe true
    }
    @Test fun paragraphExtractionIsContainerBoundedAndHonoursHiddenFiller() {
        val parser=object: HtmlNovelSource(NovelHttp()) {
            override val id="test";override val name="test";override val baseUrl="https://kolnovel.com/"
            override suspend fun catalog(page: Int,latest: Boolean,genre: String?)=NovelPage(emptyList())
            override suspend fun search(query: String,page: Int)=NovelPage(emptyList())
            override suspend fun details(novel: Novel)=novel
            override suspend fun chapters(novel: Novel,page: Int)=ChapterPage(emptyList())
            override suspend fun chapter(chapter: NovelChapter)=error("unused")
            fun parse(html: String)=text(Jsoup.parse(html),"#story")
        }
        val ownParagraph="هذه فقرة عربية من تأليف الاختبار، وليست نصًا من أي مصدر. ".repeat(3)
        val html="<style>.hidden-filler{opacity:0;position:fixed}</style><nav>MENU</nav><div id=story><p class=sr-only>SEO SUMMARY</p>"+
            "<p style=\"opacity:0.5\">"+ownParagraph+"</p><p class=hidden-filler>NOISE</p><p>"+ownParagraph+"</p><p>"+ownParagraph+"</p></div>"
        val parsed=parser.parse(html)
        parsed.paragraphs.size shouldBe 3
        parsed.paragraphs.any {"NOISE" in it || "MENU" in it || "SEO SUMMARY" in it} shouldBe false
        runCatching {parser.parse("<div id=story><p>Loading</p></div>")}.isFailure shouldBe true
    }
    @Test fun ceneleSanitizerRemovesInjectedProtectionNoticesAndHexIdentifiers() {
        val originalParagraphs = listOf(
            "كان الضباب الأبيض كثيفاً ومستمراً، حيث تسلل من الخارج وابتلع سون لينغتونغ ونينغ تشو داخل الغرفة.",
            "انطفأ الخط الأخير في المشهد. هذا التطبيق يسرق من موقع وتطبيق فضاء الروايات b9fea347bf اقرأ آلاف الفصول لأشهر الروايات على موقع وتطبيق فضاء الروايات ... وبدأ النهار بالبزوغ.",
            "انطفأ الخط الأخير في المشهد. هذا التطبيق يسرق من موقع وتطبيق فضاء الروايات b9fea347bf اقرأ آلاف الفصول لأشهر الروايات على موقع وتطبيق فضاء الروايات ...",
            "هذا التطبيق يسرق من موقع وتطبيق فضاء الروايات 6b86ba9dff e4d909c29b",
            "وسار الاثنان للأمام، هذا الفصل تم سرقته بواسطة تطبيق مانجارو من موقع وتطبيق فضاء الروايات، رمز الحماية: 6b86ba9dff e4d909c29b، وفتحا الباب.",
            "كان الجو هادئاً (تطبيق مانجارو يسرق الفصول من فضاء الروايات 0x7f3a9b) وقرر الخروج.",
            "المترجم : الدوق سيد دجاجة – فضاء الروايات",
            "ألم ينجُ بسرقة حظ شخص آخر؟",
            "كنت أعتقد أنني سأحتاج إلى تطبيق عدة جرعات على المريض."
        )
        val text = NovelText(originalParagraphs)
        val sanitized = CeneleSanitizer.sanitize(text)

        sanitized.paragraphs.size shouldBe 7
        sanitized.paragraphs[0] shouldBe "كان الضباب الأبيض كثيفاً ومستمراً، حيث تسلل من الخارج وابتلع سون لينغتونغ ونينغ تشو داخل الغرفة."
        sanitized.paragraphs[1] shouldBe "وبدأ النهار بالبزوغ."
        sanitized.paragraphs[2] shouldBe "وسار الاثنان للأمام، وفتحا الباب."
        sanitized.paragraphs[3] shouldBe "كان الجو هادئاً وقرر الخروج."
        sanitized.paragraphs[4] shouldBe "المترجم : الدوق سيد دجاجة – فضاء الروايات"
        sanitized.paragraphs[5] shouldBe "ألم ينجُ بسرقة حظ شخص آخر؟"
        sanitized.paragraphs[6] shouldBe "كنت أعتقد أنني سأحتاج إلى تطبيق عدة جرعات على المريض."
    }
    @Test fun ceneleSourceIdMismatchIsSanitized() {
        val novel = Novel("novel.cenele", "https://cenele.com/cont/book/", "الكتاب")
        novel.id shouldBe "novel.cenele:https://cenele.com/cont/book/"

        val contaminatedText = NovelText(listOf(
            "وكان الجو هادئاً.",
            "انطفأ الخط الأخير في المشهد. هذا التطبيق يسرق من موقع وتطبيق فضاء الروايات 6b86ba9dff وبدأ النهار بالبزوغ.",
            "وسار الاثنان للأمام."
        ))

        val sanitized = CeneleSanitizer.sanitize(contaminatedText)
        sanitized.paragraphs.size shouldBe 3
        sanitized.paragraphs[1] shouldBe "وبدأ النهار بالبزوغ."
    }
    @Test fun ceneleSanitizerIsIdempotentAndCleansConcatenatedNotice() {
        val concatenatedInput = NovelText(listOf(
            "انطفأ الخط الأخير في المشهد. هـٰـذَا اﻟـتـطـبـيـق يـسـرـق مِـن مـوـقـع وـتـطـبـيـق فــضـاـء الروايات b9fea347bf اقرأ آلاف الفصول لأشهر الروايات على موقع وتطبيق فضاء الروايات ...",
            "انطفأ الخط الأخير في المشهد. اﻟـقـس اﻟـمـجـنـوـن، عـوـدـة طـاـئـفـة جـبـل اﻟـهـوـا، ﻟـوـرـد اﻟـغـوـاـمـض، عـلـى مـوـقـع وـتـطـبـيـق فــضـاـء اﻟـرـوـاـيـاـت",
            "انطفأ الخط الأخير في المشهد. هذا التطبيق يسرق من موقع وتطبيق فضاء الروايات b9fea347bf اقرأ آلاف الفصول لأشهر الروايات على موقع وتطبيق فضاء الروايات ... وبدأ النهار بالبزوغ.",
            "المترجم : الدوق سيد دجاجة – فضاء الروايات",
            "ألم ينجُ بسرقة حظ شخص آخر؟"
        ))

        val pass1 = CeneleSanitizer.sanitize(concatenatedInput)
        val pass2 = CeneleSanitizer.sanitize(pass1)

        pass1.paragraphs.size shouldBe 3
        pass1.paragraphs[0] shouldBe "وبدأ النهار بالبزوغ."
        pass1.paragraphs[1] shouldBe "المترجم : الدوق سيد دجاجة – فضاء الروايات"
        pass1.paragraphs[2] shouldBe "ألم ينجُ بسرقة حظ شخص آخر؟"

        pass2 shouldBe pass1
    }
    /** Opt-in single-sample public probes; no story text/HTML is saved to this repository. */
    @Test
    @EnabledIfEnvironmentVariable(named="MANGARO_NOVEL_LIVE", matches="1")
    fun livePublicSourceJourney() = runBlocking<Unit> {
        if(System.getenv("MANGARO_NOVEL_LIVE")!="1") return@runBlocking
        val http=NovelHttp()
        val probes=listOf(
            KolNovelSource(http) to ("دفاع" to Novel("novel.kolnovel","https://kolnovel.com/series/dungeon-defense-wn/","دفاع الخنادق")),
            CeneleSource(http) to ("انشاء" to Novel("novel.cenele","https://cenele.com/cont/create-heaven-riwya/","انشاء القوانين السماوية")),
            SunovelsSource(http) to ("القس" to Novel("novel.sunovels","https://sunovels.com/novel/reverend-insanity","القس المجنون")),
            SeaNovelSource(http) to ("تيرا" to Novel("novel.seanovel","https://seanovel.org/novels/terra-nova-online-rise-of-the-strongest-player","تيرا نوفا")))
        val evidence=mutableListOf<JsonObject>()
        for((source,sample) in probes) {
            val values=linkedMapOf<String,JsonElement>("source" to JsonPrimitive(source.id))
            fun record(key: String,value: String) {values[key]=JsonPrimitive(value)}
            val catalog=source.catalog()
            (catalog.novels.isNotEmpty()) shouldBe true
            record("catalog",catalog.novels.size.toString())
            val search=source.search(sample.first)
            (search.novels.isNotEmpty()) shouldBe true
            record("search",search.novels.size.toString())
            val novel=source.details(sample.second)
            novel.title.isNotBlank() shouldBe true;novel.description.isNotBlank() shouldBe true
            record("details",novel.title);record("cover",novel.cover.orEmpty())
            val page=source.chapters(novel)
            page.chapters.isNotEmpty() shouldBe true
            record("chapters",page.chapters.size.toString());record("nextPage",page.nextPage?.toString().orEmpty())
            record("chapterUrl",page.chapters.first().url)
            try {
                val text=source.chapter(page.chapters.first())
                (text.paragraphs.size>3) shouldBe true;(text.paragraphs.sumOf {it.length}>500) shouldBe true
                record("chapterText","WORKING");record("paragraphs",text.paragraphs.size.toString());record("characters",text.paragraphs.sumOf {it.length}.toString())
                values["paragraphHashes"]=JsonArray(text.paragraphs.map {p -> JsonPrimitive(java.security.MessageDigest.getInstance("SHA-256").digest(p.toByteArray()).joinToString("") {"%02x".format(it)})})
            } catch(e: NovelSourceFailure) {
                if(source.id!="novel.seanovel") throw e
                record("chapterText","BLOCKED");record("reason",e.message.orEmpty())
            }
            evidence.add(JsonObject(values))
            println(source.id+" catalog="+catalog.novels.size+" search="+search.novels.size+" chapters="+page.chapters.size+" text="+values["chapterText"])
        }
        val output=System.getenv("MANGARO_NOVEL_EVIDENCE") ?: "/tmp/mangaro-novel-inspection/kotlin-proof.json"
        Files.writeString(Path.of(output),Json {prettyPrint=true}.encodeToString(JsonArray.serializer(),JsonArray(evidence)))
    }
}
