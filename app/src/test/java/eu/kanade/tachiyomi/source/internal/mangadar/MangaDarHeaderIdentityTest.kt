package eu.kanade.tachiyomi.source.internal.mangadar

import eu.kanade.tachiyomi.search.AtlasDeclaredNames
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.service.WorkMetadata

/** Minimal fixtures plus a sanitized header/credit excerpt captured from the public source.
 * No full page, reader body, analytics or user information is included. */
class MangaDarHeaderIdentityTest {
    private val source = MangaDar()
    private fun details(title: String, aliases: String, format: String, extra: String = ""): Manga {
        val route = "/manga/fixture-work/"
        val document = Jsoup.parse(
            """
            <main><section><div class="thumb"><img src="/cover.webp"></div><div>
            <span>$format</span><h1 class="entry-title">$title</h1>
            <p>$aliases</p></div></section><h2>معلومات العمل</h2>
            <div class="divide-y"><div><span>المؤلف</span><span>Biga</span></div></div>
            $extra</main>
            """.trimIndent(),
            source.baseUrl + route,
        )
        val parsed = source.parseMangaDetails(
            document,
            SManga.create().apply {
                url = route
                this.title = title
            },
        )
        return Manga.create().copy(
            source = source.id,
            url = route,
            title = parsed.title,
            author = parsed.author,
            memo = parsed.memo,
        )
    }

    @Test fun `captured public header keeps prose edition distinct from its illustrated adaptation`() {
        val html = javaClass.getResource("/mangadar/dar-public-erica-header-20261024.html")!!.readText()
        val route = "/manga/erica-when-a-friend-is-obsessed-with-me/"
        val parsed = source.parseMangaDetails(
            Jsoup.parse("<div class='thumb'></div>" + html, source.baseUrl + route),
            SManga.create().apply {
                url = route
                title = "Erica – When a Friend Is Obsessed With Me"
            },
        )
        val result = Manga.create().copy(source = source.id, url = route, title = parsed.title, memo = parsed.memo)
        AtlasDeclaredNames.format(result) shouldBe "novel"
        AtlasDeclaredNames.aliases(result).contains("Erica - When a Friend Is Obsessed With Me (Novel)") shouldBe true
        parsed.author shouldBe "Jenui"
        parsed.artist shouldBe "Yanggae"
    }

    @Test fun `explicit bibliographic source links survive detail parsing without borrowing footer identities`() {
        val route = "/manga/a-verified-public-work/"
        val html = """<main><div class='thumb'></div><h1>A Verified Public Work</h1><div class='divide-y'>
          <div><span>المصدر</span><a href='https://www.mangaupdates.com/series/hyi03qs/erica-when-my-friend-obsesses-over-me-novel'>عرض</a></div>
          </div><footer><a href='https://anilist.co/manga/99999'>Other work</a></footer></main>"""
        val parsed = source.parseMangaDetails(
            Jsoup.parse(html, source.baseUrl + route),
            SManga.create().apply {
                url = route
                title = "A Verified Public Work"
            },
        )
        val value = Manga.create().copy(source = source.id, url = route, title = parsed.title, memo = parsed.memo)
        WorkMetadata.from(value).externalIds shouldBe emptyMap()
        val identity = value.memo[WorkMetadata.MEMO_KEY] as JsonObject
        (identity["declaredExternalIds"] as JsonObject)["mangaupdates"] shouldBe JsonPrimitive("hyi03qs")
        identity["declaredExternalIdsStatus"] shouldBe JsonPrimitive("PENDING_VERIFICATION")
    }

    @Test fun `navigation taxonomy and another work cannot supply a header medium`() {
        val route = "/manga/a-real-work/"
        val document = Jsoup.parse(
            "<main><div class='thumb'></div><nav><span>رواية</span></nav><h1>A Real Work</h1><p>A valid synopsis</p></main>",
            source.baseUrl + route,
        )
        val parsed = source.parseMangaDetails(
            document,
            SManga.create().apply {
                url = route
                title = "A Real Work"
            },
        )
        val result = Manga.create().copy(source = source.id, url = route, title = parsed.title, memo = parsed.memo)
        AtlasDeclaredNames.format(result) shouldBe null
    }

    @Test fun `public header aliases and explicit medium reach the production details model`() {
        val manga = details(
            "Return of the Mount Hua Sect",
            "Return of the Blossoming Blade | 화산귀환 | Hwasan Gwihwan",
            "مانهوا",
        )
        AtlasDeclaredNames.aliases(manga) shouldBe setOf(
            "Return of the Mount Hua Sect",
            "Return of the Blossoming Blade",
            "화산귀환",
            "Hwasan Gwihwan",
        )
        AtlasDeclaredNames.format(manga) shouldBe "manhwa"
        manga.author shouldBe "Biga"
    }

    @Test fun `another work keeps its own header while prose and footer links cannot become aliases`() {
        val manga = details(
            "One Piece",
            "ون بيس | ONE PIECE",
            "مانغا",
            "<article><p>Unrelated Story | A Different Work</p></article><footer>مانهوا</footer>",
        )
        AtlasDeclaredNames.aliases(manga) shouldBe setOf("One Piece", "ون بيس", "ONE PIECE")
        AtlasDeclaredNames.format(manga) shouldBe "manga"
    }
}
