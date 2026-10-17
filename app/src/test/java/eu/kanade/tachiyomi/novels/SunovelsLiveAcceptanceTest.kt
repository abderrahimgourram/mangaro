package eu.kanade.tachiyomi.novels

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test

class SunovelsLiveAcceptanceTest {

    private val http = NovelHttp(OkHttpClient.Builder().followRedirects(true).build())
    private val source = SunovelsSource(http)

    @Test
    fun liveAcceptanceOvergeared(): Unit = runBlocking {
        val novel = Novel(SunovelsSource.SOURCE_ID, "https://sunovels.com/novel/overgeared", "مدجج بالعتاد")
        val page1 = source.chapters(novel, page = 1)
        page1.declaredTotal shouldBe 1877
        page1.chapters.isNotEmpty() shouldBe true
        page1.nextPage shouldBe 2

        val ch1 = page1.chapters.first()
        SunovelsSource.requireOwnership(novel, ch1)
        val text = source.chapter(ch1)
        text.paragraphs.isNotEmpty() shouldBe true
    }

    @Test
    fun liveAcceptanceBirthOfTheDemonicSword(): Unit = runBlocking {
        val novel = Novel(SunovelsSource.SOURCE_ID, "https://sunovels.com/novel/birth-of-the-demonic-sword", "ولادة السيف الشيطاني")
        val page1 = source.chapters(novel, page = 1)
        page1.declaredTotal shouldNotBe null
        page1.chapters.isNotEmpty() shouldBe true

        val ch1 = page1.chapters.first()
        SunovelsSource.requireOwnership(novel, ch1)
        val text = source.chapter(ch1)
        text.paragraphs.isNotEmpty() shouldBe true
    }

    @Test
    fun liveAcceptanceApocalypseCity(): Unit = runBlocking {
        val novel = Novel(SunovelsSource.SOURCE_ID, "https://sunovels.com/novel/i-have-an-apocalypse-city", "لدي مدينة نهاية العالم")
        val page1 = source.chapters(novel, page = 1)
        page1.declaredTotal shouldNotBe null
        page1.chapters.isNotEmpty() shouldBe true

        // Chapter 1 is locked in public web index; verified fail-closed protection
        val ch1 = page1.chapters.first()
        ch1.sunovelsState shouldBe SunovelsChapterState.EXPLICITLY_LOCKED
        runCatching { SunovelsSource.requireOwnership(novel, ch1) }.isFailure shouldBe true
    }

    @Test
    fun liveAcceptanceReverendInsanity(): Unit = runBlocking {
        val novel = Novel(SunovelsSource.SOURCE_ID, "https://sunovels.com/novel/reverend-insanity", "القس المجنون")
        val page1 = source.chapters(novel, page = 1)
        page1.declaredTotal shouldNotBe null
        page1.chapters.isNotEmpty() shouldBe true

        val ch1 = page1.chapters.first()
        SunovelsSource.requireOwnership(novel, ch1)
        val text = source.chapter(ch1)
        text.paragraphs.isNotEmpty() shouldBe true
    }
}
