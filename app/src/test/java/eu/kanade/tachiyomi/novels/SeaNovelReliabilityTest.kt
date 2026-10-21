package eu.kanade.tachiyomi.novels

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Authored text: corpus contributes structure/counts, never copyrighted chapter bodies. */
class SeaNovelReliabilityTest {
    private val url="https://seanovel.org/novels/fixture/chapters/1"
    private val good=NovelText(List(4) {"وصل المسافر إلى المدينة وقرأ الرسالة ثم تابع السير نحو البيت القديم. ".repeat(5)})
    private val bad=NovelText(List(12) {"هيس نوت هاد دون هيم وويولد هاو يفين افتير يونديرستاند ويذ فروم ذيس يور هاند واز وات شود كود وود بيينغ ".repeat(4)})
    private fun raw(canonical:String, body:String)="<html><head><link rel=canonical href='$canonical'></head><body><article class='reader-content'>${body}</article></body></html>"
    @Test fun severeUnquotedPhoneticCorruptionIsRejectedAtSourceBoundary() {
        assertThrows<NovelSourceFailure> { SeaNovelSource.validateText(bad) }.code shouldBe "NOVEL_TEXT_CORRUPTED"
    }
    @Test fun normalProseIsPreserved() {SeaNovelSource.validateText(good)}
    @Test fun canonicalCredentialsCannotBeNormalizedAway() {
        val body=good.paragraphs.joinToString(""){"<p>$it</p>"}
        runCatching {SeaNovelSource(NovelHttp()).parseChapter(raw("https://user@seanovel.org/novels/fixture/chapters/1",body),NovelChapter(url,"1",0))}.isFailure shouldBe true
    }
    @Test fun wrongChapterAndWorkCannotSupplyTheRequestedBody() {
        val body=good.paragraphs.joinToString(""){"<p>$it</p>"}
        for (canonical in listOf(url.replace("/1","/2"),url.replace("fixture","another"),url+"?chapter=2"))
            runCatching {SeaNovelSource(NovelHttp()).parseChapter(raw(canonical,body),NovelChapter(url,"1",0))}.isFailure shouldBe true
    }
}
