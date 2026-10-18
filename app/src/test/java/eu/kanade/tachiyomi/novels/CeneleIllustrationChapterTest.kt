package eu.kanade.tachiyomi.novels

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Authored fixtures reproduce public DOM structure, without copying chapter bodies. */
class CeneleIllustrationChapterTest {
    @TempDir lateinit var root: File
    private val chapter = NovelChapter("https://cenele.com/cont/fixture/characters/", "Characters", 0)
    private val edition = "novel.cenele:https://cenele.com/cont/fixture/"
    private val image = "<img src='https://riwyat.com/old.jpg' srcset='https://cenele.com/wp-content/uploads/fixture.jpg 300w' width='200' height='300'>"
    private fun parse(body: String): NovelText = CeneleChapterContent.parse(
        "<html><head><link rel=canonical href='${chapter.url}'></head><body><div class=reading-content>$body</div></body></html>", chapter, ".reading-content")
    @Test fun characterSheetWithSeventeenImagesAndNoProseIsReadable() {
        val text = parse((1..17).joinToString("") { image.replace("fixture.jpg", "fixture$it.jpg") })
        text.paragraphs.size shouldBe 0
        text.blocks.size shouldBe 17
        text.blocks.all { it.kind == NovelBlockKind.IMAGE && it.imageUrl!!.startsWith("https://cenele.com/") } shouldBe true
        validateNovelRepositoryContent("novel.cenele", text)
    }
    @Test fun shortCaptionAndImageKeepTheirOrder() {
        val text = parse("<p>Character guide</p>$image<p>End</p>")
        text.paragraphs shouldBe listOf("Character guide", "End")
        text.blocks.map { it.kind } shouldBe listOf(NovelBlockKind.TEXT, NovelBlockKind.IMAGE, NovelBlockKind.TEXT)
    }
    @Test fun illustrationOnlyPayloadSurvivesRestartWithoutChangingOwnership() {
        val text = parse(image)
        NovelDownloadDisk(root).write(edition, chapter.id, text)
        NovelDownloadDisk(root).read(edition, chapter.id) shouldBe text
        NovelDownloadDisk(root).read(edition + "other", chapter.id) shouldBe null
        NovelDownloadDisk(root).complete(edition, chapter.id) shouldBe false // Missing bytes are never marked downloaded.
    }
    @Test fun otherSourcesAndNearMatchSourceIdsDoNotGainTheException() {
        val text = parse(image)
        for (source in listOf("novel.kolnovel", "novel.cenele-other", "novel.mknov")) {
            runCatching { validateNovelRepositoryContent(source, text) }.isFailure shouldBe true
            runCatching { NovelDownloadDisk(root).write(source + ":fixture", chapter.id, text) }.isFailure shouldBe true
        }
    }
    @Test fun unavailableImageAndEmptyTextStillFail() {
        for (body in listOf("", "<p>Loading</p>", "<img src='https://unapproved.example/image.jpg'>", "<img class=logo src='https://cenele.com/logo.jpg'>")) {
            (runCatching { parse(body) }.exceptionOrNull() as NovelSourceFailure).code shouldBe "CENELE_READER_PARSE"
        }
    }
    @Test fun explicitUnavailableNoticeAndSequentialComicPanelsStillFail() {
        (runCatching { parse("<p>نعتذر، الفصل غير متاح</p>$image") }.exceptionOrNull() as NovelSourceFailure).code shouldBe "CENELE_PUBLIC_UNAVAILABLE"
        (runCatching { parse("<div class=nhv-webtoon-page>$image</div>") }.exceptionOrNull() as NovelSourceFailure).code shouldBe "CENELE_READER_PARSE"
    }
    @Test fun invalidReplacementDoesNotDeleteExistingChapter() {
        val text = parse(image); val disk = NovelDownloadDisk(root)
        disk.write(edition, chapter.id, text)
        runCatching { disk.write(edition, chapter.id, NovelText(emptyList())) }.isFailure shouldBe true
        disk.read(edition, chapter.id) shouldBe text
    }
    @Test
    @EnabledIfEnvironmentVariable(named = "MANGARO_CENELE_IMAGE_ACCEPTANCE", matches = "1")
    fun publicReportedCharacterSheetPassesProductionParserAndImageTransport() = runBlocking {
        val url = "https://cenele.com/cont/return-hua/%d8%a7%d9%84%d9%81%d8%b5%d9%84-0-0/"
        val http = NovelHttp()
        val text = CeneleSource(http).chapter(NovelChapter(url, "صور الشخصيات", 0))
        text.paragraphs.size shouldBe 0
        text.blocks.size shouldBe 17
        validateNovelRepositoryContent("novel.cenele", text)
        val bytes = http.imageBytes(text.blocks.first().imageUrl!!)
        val image = javax.imageio.ImageIO.read(bytes.inputStream())
        (image != null && image.width > 0 && image.height > 0) shouldBe true
        println("CENELE_PUBLIC_ILLUSTRATIONS paragraphs=0 images=17 decodedFirstImage=true requests=2; no content retained")
    }
}
