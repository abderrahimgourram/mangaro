package eu.kanade.tachiyomi.source.internal

import eu.kanade.tachiyomi.source.internal.mangadar.MangaDar
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.service.WorkMetadata
import java.io.IOException

class HtmlCatalogIdentityTest {
    private val source = MangaDar()
    private val route = "/manga/a-real-public-work/"
    private fun row(url: String) = "<div><span>المصدر</span><a href='$url'>عرض</a></div>"
    private fun ids(rows: String, extra: String = "", actual: String = route): Map<String, String> {
        val doc = Jsoup.parse(
            "<main><div class='thumb'></div><h1>A Real Public Work</h1><div class='divide-y'>$rows</div>$extra</main>",
            source.baseUrl + actual,
        )
        val value = source.parseMangaDetails(
            doc,
            SManga.create().apply {
                url = route
                title = "A Real Public Work"
            },
        )
        // Neither valid URL syntax nor a bibliographic row proves the target record exists.
        WorkMetadata.from(
            Manga.create().copy(source = source.id, url = route, title = value.title, memo = value.memo),
        ).externalIds shouldBe
            emptyMap()
        val evidence = value.memo[WorkMetadata.MEMO_KEY] as? JsonObject
        return (evidence?.get("declaredExternalIds") as? JsonObject).orEmpty().mapValues {
            it.value.jsonPrimitive.content
        }
    }

    @Test fun `known catalogue declarations preserve namespaces without becoming identity authority`() {
        val link = "https://www.mangaupdates.com/series/hyi03qs/erica-when-my-friend-obsesses-over-me-novel"
        ids(row(link) + row(link)) shouldBe mapOf("mangaupdates" to "hyi03qs")
        ids(row("https://anilist.co/manga/12345/some-name")) shouldBe mapOf("anilist" to "12345")
        ids(row("https://myanimelist.net/manga/12345/name")) shouldBe mapOf("myanimelist" to "12345")
    }

    @Test fun `footer other work redirects untrusted hosts and article authors supply no catalogue identity`() {
        val valid = "https://www.mangaupdates.com/series/hyi03qs/name"
        ids("", "<footer><div class='divide-y'>${row(valid)}</div></footer>") shouldBe emptyMap()
        ids(row(valid), actual = "/manga/a-different-work/") shouldBe emptyMap()
        for (link in listOf(
            "https://evil.example/series/hyi03qs/name",
            "http://www.mangaupdates.com/series/hyi03qs/name",
            "https://account@www.mangaupdates.com/series/hyi03qs/name",
            "https://mangadex.org/title/1-1-1-1-1",
            "https://anilist.co/search/manga?name=work",
        )) {
            ids(row(link)) shouldBe emptyMap()
        }
    }

    @Test fun `contradictory source rows fail instead of choosing an arbitrary authority ID`() {
        assertThrows<IOException> {
            ids(row("https://anilist.co/manga/12345/work") + row("https://anilist.co/manga/67890/other"))
        }
    }
}
