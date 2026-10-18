package eu.kanade.tachiyomi.novels

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** Synthetic text only. No live chapter body or private API implementation copied. */
class CeneleChapterContentTest {
    private val chapter=NovelChapter("https://cenele.com/cont/work/volume/chapter-1/","Chapter 1",0)
    private val story=(1..4).joinToString(""){"<p>"+"An independently authored synthetic story paragraph for validation. ".repeat(4)+"</p>"}
    private fun html(body:String=story,canonical:String=chapter.url)="<html><head><link rel=canonical href='$canonical'></head><body><div class=reading-content>$body</div></body></html>"
    private fun failure(raw:String):NovelSourceFailure = runCatching {CeneleChapterContent.parse(raw,chapter,".reading-content")}.exceptionOrNull() as NovelSourceFailure
    @Test fun validChapterRetainsParagraphOrderAndIdentity(){
        val result=CeneleChapterContent.parse(html(),chapter,".reading-content")
        result.paragraphs.size shouldBe 4;chapter.order shouldBe 0;chapter.url shouldBe "https://cenele.com/cont/work/volume/chapter-1/"
    }
    @Test fun missingContainerHasRetryableParseCodeInsteadOfPermanentUnavailable(){
        val error=failure("<html><body>temporary upstream placeholder</body></html>")
        error.code shouldBe "CENELE_READER_PARSE";error.httpStatus shouldBe null;error.sourceVerified shouldBe false
        error.publicMessage.contains("أعد المحاولة") shouldBe true
    }
    @Test fun emptyOrIncompleteBodyRemainsRejected(){failure(html("<p>Loading</p>")).code shouldBe "CENELE_READER_PARSE"}
    @Test fun explicitUnavailableNoticeIsNotAcceptedAsStoryEvenWithOtherText(){
        failure(html("<p>نعتذر، الفصل غير متاح</p>"+story)).code shouldBe "CENELE_PUBLIC_UNAVAILABLE"
    }
    @Test fun incompleteDocumentStillFailsValidation(){failure(html().removeSuffix("</html>")).code shouldBe "CENELE_DOCUMENT_TRUNCATED"}
    @Test fun anotherChapterCannotSupplyTheRequestedText(){failure(html(canonical=chapter.url.replace("chapter-1","chapter-2"))).code shouldBe "CENELE_CHAPTER_IDENTITY"}
    @Test fun identityBearingQueryIsNotSilentlyStripped(){
        failure(html(canonical=chapter.url+"?chapter=2")).code shouldBe "CENELE_CHAPTER_IDENTITY"
    }
    @Test fun anotherWorkCannotSupplyTheRequestedText(){failure(html(canonical=chapter.url.replace("/work/","/other/"))).code shouldBe "CENELE_CHAPTER_IDENTITY"}
    @Test fun relativeCanonicalUsesTheExactRequestedRoute(){
        CeneleChapterContent.parse(html(canonical="/cont/work/volume/chapter-1/"),chapter,".reading-content").paragraphs.size shouldBe 4
        failure(html(canonical="/cont/work//volume/chapter-1/")).code shouldBe "CENELE_CHAPTER_IDENTITY"
    }
    @Test fun foreignCanonicalAndCredentialsAreRejected(){
        failure(html(canonical="https://sunovels.com/novel/other/1")).code shouldBe "CENELE_CHAPTER_IDENTITY"
        failure(html(canonical="https://user@cenele.com/cont/work/volume/chapter-1/")).code shouldBe "CENELE_CHAPTER_IDENTITY"
    }
    @Test fun wrongProviderRouteDoesNotReachNetwork()=runBlocking {
        val http=mockk<NovelHttp>();val source=CeneleSource(http)
        val result=runCatching{source.chapter(chapter.copy(url="https://seanovel.org/novels/other/1"))}
        (result.exceptionOrNull() as NovelSourceFailure).code shouldBe "CENELE_CHAPTER_IDENTITY"
        coVerify(exactly=0){http.get(any(),any(),any())}
    }
    @Test fun typedHttpErrorsRemainDistinctAndRecoverableWithoutFakeContent()=runBlocking {
        for(status in listOf(403,404,429,503)) {
            val http=mockk<NovelHttp>();val denied=NovelSourceFailure("retry","source HTTP",status,code="HTTP_$status")
            coEvery{http.get(chapter.url,any(),any())} throws denied
            val actual=runCatching{CeneleSource(http).chapter(chapter)}.exceptionOrNull()
            (actual===denied) shouldBe true
            (actual as NovelSourceFailure).code shouldBe "HTTP_$status"
        }
    }
    @Test fun retryAfterParserFailureUsesTheSameStoredChapterRoute()=runBlocking {
        val http=mockk<NovelHttp>()
        coEvery{http.get(chapter.url,any(),any())} returnsMany listOf("<html><body>temporary</body></html>",html())
        val source=CeneleSource(http)
        runCatching{source.chapter(chapter)}.isFailure shouldBe true
        source.chapter(chapter).paragraphs.size shouldBe 4
        coVerify(exactly=2){http.get(chapter.url,any(),any())}
    }
}
