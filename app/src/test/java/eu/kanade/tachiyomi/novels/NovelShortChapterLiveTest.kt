package eu.kanade.tachiyomi.novels

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Nine bounded public requests, only previously failed cases and controls; no repeated full audit. */
class NovelShortChapterLiveTest {
    @TempDir lateinit var root:File
    @Test
    @EnabledIfEnvironmentVariable(named="MANGARO_NOVEL_SHORT_LIVE", matches="1")
    fun shortSourceTextIsRestoredWithoutSubstitutingWrongIdsOrCorruptBodies() = runBlocking {
        val source=MknovSource(NovelHttp())
        for((work,chapter) in listOf(108 to 41602,619 to 201495)) {
            val url="https://mknov.com/novel/$work/chapter/$chapter"
            val text=source.chapter(NovelChapter(url,"sample",0))
            validateNovelRepositoryContent(source.id,text)
            NovelContentQuality.assess(text).verdict shouldBe NovelContentQuality.Verdict.UNKNOWN
            val edition="novel.mknov:https://mknov.com/novel/$work"
            NovelDownloadDisk(root).write(edition,url,text)
            (NovelDownloadDisk(root).read(edition,url)==text) shouldBe true
            println("SHORT_PUBLIC_PASS source=mknov work=$work chapter=$chapter characters=${text.paragraphs.sumOf{it.length}} offline=true")
        }
        for(chapter in listOf(41603,344148)) {
            assertThrows<NovelSourceFailure> {runBlocking {source.chapter(NovelChapter("https://mknov.com/novel/108/chapter/$chapter","sample",0))}}.code shouldBe "MKNOV_IDENTITY"
            println("UPSTREAM_WRONG_ID_REJECTED requested=$chapter noSubstitution=true")
        }
        val sea=SeaNovelSource(NovelHttp())
        val short=sea.chapter(NovelChapter("https://seanovel.org/novels/invincible/chapters/0","0",0))
        NovelContentQuality.assess(short).verdict shouldBe NovelContentQuality.Verdict.UNKNOWN
        NovelDownloadDisk(root).write("novel.seanovel:https://seanovel.org/novels/invincible","0",short)
        println("SHORT_PUBLIC_PASS source=sea chapter=0 characters=${short.paragraphs.sumOf{it.length}} offline=true")
        assertThrows<NovelSourceFailure> {runBlocking {sea.chapter(NovelChapter("https://seanovel.org/novels/invincible/chapters/1979","1979",0))}}.code shouldBe "NOVEL_TEXT_CORRUPTED"
        val good=sea.chapter(NovelChapter("https://seanovel.org/novels/invincible/chapters/1620","1620",0))
        NovelContentQuality.assess(good).verdict shouldBe NovelContentQuality.Verdict.READABLE
        println("PUBLIC_CONTROLS sea1979=CORRUPTED_REJECTED sea1620=READABLE")
    }
}
