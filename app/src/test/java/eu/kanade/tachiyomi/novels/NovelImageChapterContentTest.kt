package eu.kanade.tachiyomi.novels

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Authored DOM fixtures for the source-independent image/mixed chapter contract. */
class NovelImageChapterContentTest {
    @TempDir lateinit var root: File
    private val chapterUrl = "https://cenele.com/cont/fixture/chapter-1/"
    private fun parse(body: String, allowShortText: Boolean = false): NovelText {
        val doc = Jsoup.parse(
            "<html><head><link rel=canonical href='$chapterUrl'></head><body><div class=reading-content>$body</div></body></html>",
            chapterUrl,
        )
        return extractNovelContent(doc.selectFirst(".reading-content")!!, allowShortText, allowIllustrations = true)
    }

    @Test fun imageOnlyLazyAndRelativeSourcesBecomeOneOrderedChapter() {
        val text = parse("<img data-src='../../images/a.jpg' data-original='https://cenele.com/images/b.jpg'>")
        text.paragraphs shouldBe emptyList()
        text.blocks.size shouldBe 1
        text.blocks.single().kind shouldBe NovelBlockKind.IMAGE
        text.blocks.single().imageUrl shouldBe "https://cenele.com/cont/images/a.jpg"
        text.hasReadableContent() shouldBe true
        text.hasReadableIllustration() shouldBe true
    }

    @Test fun mixedProseAndImageKeepTheirExactOrderAndParagraphIndexes() {
        val text = parse("<p>First paragraph</p><img src='https://cenele.com/images/a.jpg'><p>Last paragraph</p>", true)
        text.paragraphs shouldBe listOf("First paragraph", "Last paragraph")
        text.blocks.map { it.kind } shouldBe
            listOf(NovelBlockKind.TEXT, NovelBlockKind.IMAGE, NovelBlockKind.TEXT)
        text.blocks.map { it.paragraph } shouldBe listOf(0, null, 1)
        text.orderedBlocks() shouldBe text.blocks
    }

    @Test fun pictureSourceWinsOverItsFallbackImageWithoutDuplicateBlocks() {
        val text = parse(
            "<picture><source srcset='https://cenele.com/images/webp.jpg 1x,\n " +
                "https://cenele.com/images/webp2.jpg 2x'><img src='https://cenele.com/images/fallback.jpg'" +
                " width='800' height='1200'></picture>",
        )
        text.blocks.size shouldBe 1
        text.blocks.single().imageUrl shouldBe "https://cenele.com/images/webp.jpg"
        text.blocks.single().width shouldBe 800
        text.blocks.single().height shouldBe 1200
    }

    @Test fun responsiveSrcsetUsesTheFirstAllowedCandidateAndNeverTheDocumentUrl() {
        val text = parse(
            "<img src='$chapterUrl' srcset='https://cenele.com/images/small.jpg 1x,\n" +
                " https://cenele.com/images/large.jpg 2x'>",
        )
        text.blocks.single().imageUrl shouldBe "https://cenele.com/images/small.jpg"
    }

    @Test fun cssDimensionsAreRetainedWithoutAReplacementRequest() {
        val text = parse(
            "<img src='https://cenele.com/images/a.jpg' style='width: 720px; height: 1280px'>",
        )
        text.blocks.single().width shouldBe 720
        text.blocks.single().height shouldBe 1280
    }

    @Test fun adIconsLogosPlaceholdersAndChapterLinksAreNeverIllustrations() {
        val text = parse(
            "<img class=logo src='https://cenele.com/images/logo.jpg'>" +
                "<div class=advert><img src='https://cenele.com/images/ad.jpg'></div>" +
                "<img src='https://cenele.com/images/placeholder.jpg'>" +
                "<a href='$chapterUrl'><img src='$chapterUrl'></a>" +
                "<img src='https://cenele.com/images/real.jpg'>",
        )
        text.blocks.map { it.imageUrl } shouldContainExactly listOf("https://cenele.com/images/real.jpg")
    }

    @Test fun unavailableOrDataOnlyImagesCannotCreateContentByThemselves() {
        for (body in listOf(
            "<img src='https://unapproved.example/image.jpg'>",
            "<img src='data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw=='>",
            "<p>Loading</p>",
        )) {
            assertThrows<NovelSourceFailure> { parse(body) }
        }
    }

    @Test fun aBlockedImageIsNotSuccessfulContentAfterSerializationOrValidation() {
        val blocked = NovelText(emptyList(), blocks = listOf(NovelContentBlock(NovelBlockKind.IMAGE)))
        blocked.hasReadableContent() shouldBe false
        assertThrows<IllegalStateException> { validateNovelRepositoryContent("novel.cenele", blocked) }
        assertThrows<IllegalStateException> {
            NovelDownloadDisk(root).write("novel.cenele:fixture", chapterUrl, blocked)
        }
    }

    @Test fun imageOnlyChapterSurvivesDiskRoundTripAndMissingBytesStayIncomplete() {
        val text = parse("<img src='https://cenele.com/images/a.jpg'>")
        val disk = NovelDownloadDisk(root)
        val edition = "novel.kolnovel:https://kolnovel.com/series/fixture/"
        disk.write(edition, chapterUrl, text)
        disk.read(edition, chapterUrl) shouldBe text
        disk.complete(edition, chapterUrl) shouldBe false
    }
}
