package eu.kanade.tachiyomi.novels

import eu.kanade.tachiyomi.search.NovelSearchSelection
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** Real production source parser/projection/search boundaries, authored offline fixtures only. */
class NovelContentTypePresentationTest {
    private fun novel(key: String, format: String? = null, source: String = "novel.cenele") =
        Novel(source,"https://cenele.com/cont/$key/","Shared franchise",author="Fixture creator",
            contentFormat=format?.let { NovelContentTypeClassifier.declared(listOf(it)) })
    private val text=novel("text","novel")
    private val comic=novel("comic","manhwa")
    private val unknown=novel("unknown")
    private fun work(key:String,vararg editions:Novel)=UnifiedNovelWork(key,editions.toList(),editions.first().id)
    private fun state(vararg works:UnifiedNovelWork)=NovelCatalogState(works.toList())

    @Test fun mixedPageKeepsTextAndExplicitlyUnknownButExcludesConfirmedComic() {
        val page=NovelPage(listOf(text,comic,unknown),nextPage=2)
        val visible=NovelCatalogCards.eligiblePage(NovelCatalogState(),page)
        visible.novels.map { it.id } shouldBe listOf(text.id,unknown.id)
        visible.nextPage shouldBe 2
        page.novels.size shouldBe 3
    }
    @Test fun paginationAndSerializedSearchCacheCannotReintroduceKnownComic() {
        val catalog=state(work("text",text),work("comic",comic))
        val staleComic=comic.copy(contentFormat=null)
        val cached=Json.decodeFromString<NovelPage>(Json.encodeToString(NovelPage(listOf(text,staleComic),2)))
        repeat(3) { NovelCatalogCards.eligiblePage(catalog,cached).novels shouldBe listOf(text) }
        NovelCatalogCards.select(catalog.works,cached.novels).single().primary shouldBe text
        val projected=NovelCatalogCards.project(catalog)
        val slots=NovelCatalogCards.eligiblePage(catalog,cached).novels
        NovelCatalogCards.select(projected,slots).single().primary shouldBe text
    }
    @Test fun homeRecommendationsAndGenreProjectionUseTheSameEligibilityPolicy() {
        val catalog=state(work("text",text),work("comic",comic),work("unknown",unknown))
        val home=NovelCatalogCards.project(catalog)
        home.map { it.id } shouldBe listOf("text","unknown")
        NovelCatalogCards.select(home,listOf(comic,text,unknown,comic)).map { it.id } shouldBe listOf("text","unknown")
        NovelSourceAvailability.discoverable(work("comic",comic)) shouldBe null
    }
    @Test fun existingMixedCanonicalGroupKeepsItsGenuineTextEditionAndKey() {
        val catalog=state(work("existing-work",comic,text))
        val card=NovelCatalogCards.project(catalog).single()
        card.id shouldBe "existing-work";card.editions shouldBe listOf(text);card.primary shouldBe text
        // Original catalog remains lossless, including saved/legacy comic records.
        catalog.works.single().editions shouldBe listOf(comic,text)
    }
    @Test fun sameTitleComicAndNovelCannotBecomeOneNewCanonicalWork() {
        val other=comic.copy(sourceId="novel.kolnovel")
        val catalog=NovelWorkReconciler.ingest(NovelCatalogState(),listOf(text,other))
        catalog.works.size shouldBe 2
        NovelCatalogCards.project(catalog).single().editions shouldBe listOf(text)
    }
    @Test fun savedProgressBookmarksAndComicMetadataSurviveDiscoveryFilteringAndSerialization() {
        val saved=NovelLibraryItem(comic,saved=true,position=NovelReadingPosition(NovelChapter(comic.url+"chapter-5","5",4),10,43),
            favorite=true,bookmarks=setOf(comic.url+"chapter-4"))
        val before=Json.encodeToString(saved)
        val catalog=state(work("legacy",comic),work("text",text))
        NovelCatalogCards.project(catalog);NovelCatalogCards.eligiblePage(catalog,NovelPage(listOf(comic,text)))
        Json.encodeToString(saved) shouldBe before
        Json.decodeFromString<NovelLibraryItem>(before) shouldBe saved
        catalog.works.first().editions.single().id shouldBe saved.novel.id
    }
    @Test fun sparseMetadataDoesNotEraseKnownFormatButAnExplicitCorrectionCanReplaceIt() {
        val first=NovelWorkReconciler.ingest(NovelCatalogState(),listOf(comic))
        val sparse=NovelWorkReconciler.ingest(first,listOf(comic.copy(contentFormat=null)))
        sparse.work(comic.id)!!.editions.single().contentFormat shouldBe comic.contentFormat
        NovelCatalogCards.project(sparse).size shouldBe 0
        val corrected=NovelWorkReconciler.ingest(sparse,listOf(comic.copy(contentFormat=text.contentFormat)))
        NovelCatalogCards.project(corrected).size shouldBe 1
        first.works.single().id shouldBe corrected.works.single().id
    }
    @Test fun actualCeneleParserDoesNotTrustUnverifiedFormatAttributesOrProbeChapters()=runBlocking {
        val http=mockk<NovelHttp>()
        val html="""<html><body>
          <article class=nhv-library-card data-content-type=novel><a class=nhv-library-card__cover href='https://cenele.com/cont/text/' title='Text'><img src='https://cenele.com/text.jpg'></a></article>
          <article class=nhv-library-card data-content-type=manhwa><a class=nhv-library-card__cover href='https://cenele.com/cont/comic/' title='Comic'><img src='https://cenele.com/comic.jpg'></a></article>
          <a rel=next href='https://cenele.com/?page=2'>Next</a></body></html>"""
        coEvery { http.get(any(),any(),any()) } returns html
        val raw=CeneleSource(http).search("fixture",1)
        raw.novels.size shouldBe 2
        val page=NovelCatalogCards.eligiblePage(NovelCatalogState(),raw)
        raw.novels.all { it.contentFormat == null } shouldBe true
        page.novels.size shouldBe 2;page.nextPage shouldBe 2
        coVerify(exactly=1) { http.get(any(),any(),any()) }
        coVerify(exactly=0) { http.post(any(),any()) }
    }
    @Test fun actualNovelSearchPresentationExcludesComicsBeforeGroupingAndKeepsTextAlternatives() {
        val edition=text.copy(sourceId="novel.seanovel",url="https://seanovel.org/novels/text",title="Arabic alias")
        val catalog=state(work("verified-text",text,edition),work("comic",comic))
        val repository=mockk<NovelRepository>()
        every { repository.catalog } returns MutableStateFlow(catalog)
        every { repository.activeSources } returns emptyList()
        val selected=NovelSearchSelection(repository).presentation(listOf(text,comic,edition,comic),catalog.works)
        selected.works.single().id shouldBe "verified-text"
        selected.works.single().editions.map { it.id }.toSet() shouldBe setOf(text.id,edition.id)
        selected.groups.single().members.size shouldBe 2
        coVerify(exactly=0) { repository.detail(any()) }
        coVerify(exactly=0) { repository.completeIndex(any(),any(),any<Int>(),any()) }
        coVerify(exactly=0) { repository.chapterText(any(),any()) }
    }
    @Test fun actualJsonAdaptersKeepUnsupportedFormatKeysUnknownWithoutAdditionalRequests()=runBlocking {
        val http=mockk<NovelHttp>()
        coEvery { http.mknovPost(any(),any()) } returns """{"status":"success","total":2,"data":[
          {"id":9001,"name":"Text fixture","content_type":"novel"},
          {"id":9002,"name":"Comic fixture","content_type":"manga"}]}"""
        coEvery { http.get(any(),any(),any()) } returns """[
          {"slug":"text-fixture","title_ar":"Text fixture","content_format":"novel","genres":[],"chapters_count":10},
          {"slug":"comic-fixture","title_ar":"Comic fixture","content_format":"manhua","genres":[],"chapters_count":10}]"""
        for(source in listOf(MknovSource(http),SeaNovelSource(http))) {
            val raw=source.catalog(1,false,null)
            raw.novels.size shouldBe 2
            raw.novels.all { it.contentFormat == null } shouldBe true
            NovelCatalogCards.eligiblePage(NovelCatalogState(),raw).novels.size shouldBe 2
        }
        coVerify(exactly=1) { http.mknovPost("https://api.mknov.com/v1/getWorks",any()) }
        coVerify(exactly=1) { http.get("https://seanovel.org/api/novels?sort=views",any(),any()) }
    }
    @Test fun actualKolnovelCardsDoNotAssumeAnUnverifiedTypeAttributeContract()=runBlocking {
        val http=mockk<NovelHttp>()
        coEvery { http.get(any(),any(),any()) } returns """<html><body>
          <article class=maindet data-content-type=novel><div class=mdinfo><h2><a href='https://kolnovel.com/series/text/'>Text fixture</a></h2></div></article>
          <article class=maindet data-content-type=manga><div class=mdinfo><h2><a href='https://kolnovel.com/series/comic/'>Comic fixture</a></h2></div></article>
          </body></html>"""
        val page=KolNovelSource(http).catalog(1,true,null)
        page.novels.size shouldBe 2
        page.novels.all { it.contentFormat == null } shouldBe true
        NovelCatalogCards.eligiblePage(NovelCatalogState(),page).novels.size shouldBe 2
        coVerify(exactly=1) { http.get(any(),any(),any()) }
    }
    @Test fun sourceIndependentPolicyCoversAllFiveNativeProviders() {
        for(source in listOf("novel.cenele","novel.kolnovel","novel.sunovels","novel.seanovel","novel.mknov")) {
            NovelCatalogCards.eligiblePage(NovelCatalogState(),NovelPage(listOf(text.copy(sourceId=source),comic.copy(sourceId=source)))).novels.single().contentFormat!!.type shouldBe NovelContentType.TEXT_NOVEL
        }
    }
    @Test fun productionScreensAndRepositoryConsumeFilteredProjectionOnEveryPath() {
        fun source(relative:String):String=listOf(Path.of("."),Path.of("app")).map { it.resolve("src/main/java/eu/kanade/$relative") }.first(Files::exists).let(Files::readString)
        val screens=source("presentation/novels/NovelScreens.kt")
        screens.contains("val activeWorks = NovelCatalogCards.project(catalogSnapshot)") shouldBe true
        screens.contains("NovelCatalogCards.eligiblePage(catalogSnapshot, NovelPage(editions))") shouldBe true
        screens.contains("NovelSearchProjection.project(catalogSnapshot, editions, selection, activeWorks)") shouldBe true
        screens.contains("if (search != null) search.works") shouldBe true
        val home=source("presentation/novels/MangaroNovelIntegration.kt")
        home.contains("NovelCatalogCards.project(state).take(12)") shouldBe true
        val repository=source("tachiyomi/novels/NovelRepository.kt")
        repository.contains("NovelCatalogCards.eligiblePage(catalog.value,it.result)") shouldBe true
        repository.contains("NovelCatalogCards.eligiblePage(catalog.value,restored.result)") shouldBe true
        repository.contains("NovelCatalogCards.eligiblePage(catalog.value,pageResult)") shouldBe true
    }
    @Test fun recordedPublicCeneleSearchLosesOnlyTheIndependentlyVerifiedComicEdition() {
        val fixture=javaClass.getResource("/novels/content-types/cenele-public-metadata.json")!!.readText()
        val rows=Json.parseToJsonElement(fixture).jsonObject.getValue("entries").jsonArray.map { raw ->
            val row=raw.jsonObject
            Novel(row.getValue("sourceId").jsonPrimitive.content,row.getValue("url").jsonPrimitive.content,row.getValue("title").jsonPrimitive.content)
        }
        rows.size shouldBe 12
        val classified=rows.groupBy { NovelContentTypeClassifier.classify(it,NovelContentTypeClassifier.REVIEWED_AT+1).type }
        classified.getValue(NovelContentType.COMIC).single().url shouldBe "https://cenele.com/cont/shadow-sl-manhwa/"
        classified.getValue(NovelContentType.UNKNOWN).size shouldBe 11
        // Provisional UNKNOWN is not a fabricated confirmed-text classification.
        classified[NovelContentType.TEXT_NOVEL] shouldBe null
    }
    @Test fun boundedLargeProjectionDoesNotNeedClassificationRequests() {
        val rows=(0 until 5000).map { text.copy(url=text.url+it,contentFormat=if(it%2==0)text.contentFormat else comic.contentFormat) }
        val start=System.nanoTime()
        val visible=NovelCatalogCards.eligiblePage(NovelCatalogState(),NovelPage(rows))
        visible.novels.size shouldBe 2500
        println("NOVEL_TYPE_HOST rows=5000 eligible=2500 elapsedMs=${(System.nanoTime()-start)/1_000_000.0}")
    }
}
