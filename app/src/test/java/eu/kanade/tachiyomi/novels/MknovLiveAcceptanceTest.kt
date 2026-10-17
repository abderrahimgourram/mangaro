package eu.kanade.tachiyomi.novels

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.concurrent.atomic.AtomicInteger

/** Explicit opt-in: four anonymous requests for ONE work, no crawling/auth/download/ad activity. */
class MknovLiveAcceptanceTest {
    @Test
    @EnabledIfEnvironmentVariable(named="MANGARO_MKNOV_LIVE_PROBE", matches="1")
    fun realPublicWorkIndexAndStoryThroughIntegratedTransport() = runBlocking<Unit> {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .addInterceptor { chain ->
                check(MknovSource.apiUrlAllowed(chain.request().url.toString()))
                check(calls.incrementAndGet() <= 4)
                chain.proceed(chain.request())
            }.build()
        val source=MknovSource(NovelHttp(client))
        val work=source.search("عبد الظل",1).novels.single {it.url=="https://mknov.com/novel/96"}
        val details=source.details(work)
        details.author shouldBe "Guiltythree"
        val index=source.chapters(details,1)
        index.chapters.map {it.id}.distinct().size shouldBe index.declaredTotal
        (index.chapters.size > 3000) shouldBe true
        val selected=index.chapters.first {it.available && it.url=="https://mknov.com/novel/96/chapter/20716"}
        val text=source.chapter(selected)
        validateNovelRepositoryContent(source.id,text)
        calls.get() shouldBe 4
        println("MKNOV LIVE PASS: requests=${calls.get()} indexed=${index.chapters.size} chapter=20716 paragraphs=${text.paragraphs.size} characters=${text.paragraphs.sumOf {it.length}}; Android rendering NOT tested")
    }
}
