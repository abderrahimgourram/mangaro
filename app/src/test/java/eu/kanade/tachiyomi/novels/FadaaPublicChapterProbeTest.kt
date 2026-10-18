package eu.kanade.tachiyomi.novels

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.security.MessageDigest

/** Two anonymous HTML requests, explicit opt-in only. Never records chapter bodies. */
@EnabledIfEnvironmentVariable(named = "MANGARO_FADAA_PUBLIC_PROBE", matches = "1")
class FadaaPublicChapterProbeTest {
    @Test fun existingProductionSourceAcceptsTwoPublicSamples() = runBlocking {
        val source = CeneleSource(NovelHttp())
        val prefix = "https://cenele.com/cont/rene-immortal/الكتاب-1-الشباب-المتوسط/"
        listOf("الفصل-1-مغادرة-المنزل/", "الفصل-2-الخالدين/").forEachIndexed { index, route ->
            val chapter = NovelChapter(prefix + route, "public sample", index)
            val text = source.chapter(chapter)
            validateNovelRepositoryContent(source.id, text)
            (text.paragraphs.size >= 3) shouldBe true
            text.paragraphs.any { it.contains("نعتذر، الفصل غير متاح") } shouldBe false
            val hash = MessageDigest.getInstance("SHA-256").digest(text.paragraphs.joinToString("\n").toByteArray())
                .joinToString("") { "%02x".format(it) }
            println("FADAA_PUBLIC_SAMPLE chapter=${index + 1} paragraphs=${text.paragraphs.size} characters=${text.paragraphs.sumOf { it.length }} sha256=$hash")
            if (index == 0) kotlinx.coroutines.delay(1200)
        }
    }
    /** At most five metadata requests (details response reused for index configuration).
     * No full-index traversal, account requests, signed-app calls or chapter bodies saved. */
    @Test fun existingProductionDiscoveryAndIndexRetainPublicWorkIdentity() = runBlocking {
        val source = CeneleSource(NovelHttp())
        val catalog = source.catalog(page = 1, latest = false)
        (catalog.novels.isNotEmpty()) shouldBe true
        catalog.novels.all { it.sourceId == "novel.cenele" && it.url.startsWith("https://cenele.com/cont/") } shouldBe true
        kotlinx.coroutines.delay(1200)
        val results = source.search("الخالد المرتد", 1)
        val novel = results.novels.single { it.url == "https://cenele.com/cont/rene-immortal/" }
        kotlinx.coroutines.delay(1200)
        val detail = source.details(novel)
        detail.id shouldBe novel.id
        detail.title.isNotBlank() shouldBe true
        kotlinx.coroutines.delay(1200)
        val page = source.chapters(detail, 1)
        page.chapters.isNotEmpty() shouldBe true
        page.chapters.map { it.id }.distinct().size shouldBe page.chapters.size
        page.chapters.all { it.url.startsWith(detail.url) } shouldBe true
        page.chapters.first().url.endsWith("/") shouldBe true
        println("FADAA_PUBLIC_JOURNEY catalog=${catalog.novels.size} search=${results.novels.size} work_identity=true first_page=${page.chapters.size} next=${page.nextPage} declared=${page.declaredTotal}")
    }

}
