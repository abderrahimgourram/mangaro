package eu.kanade.tachiyomi.source.internal

import eu.kanade.tachiyomi.source.internal.hijala.Hijala
import eu.kanade.tachiyomi.source.internal.mangadar.MangaDar
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

class HtmlCreatorMetadataTest {
    private fun manga(route: String) = SManga.create().apply {
        url = route
        title = "Fixture story"
    }
    private fun dar(body: String, route: String = "/manga/fixture/") = Jsoup.parse(
        "<h1 class='entry-title'>Fixture</h1><div class='thumb'></div><div class='divide-y'>$body</div>",
        "https://mangadar.com$route",
    )
    private fun row(role: String, name: String) = "<div><span>$role</span><a rel='tag'><span>$name</span></a></div>"

    @Test fun `Dar retains explicitly credited creators for different works`() {
        for ((author, artist) in listOf("Chugong" to "Jang, Sung-rak", "SingNSong" to "Sleepy-C")) {
            val result = MangaDar().parseMangaDetails(
                dar(row("المؤلف", author) + row("الرسام", artist)),
                manga("/manga/fixture/"),
            )
            result.author shouldBe author
            result.artist shouldBe artist
        }
    }

    @Test fun `Hijala retains nested credits without borrowing artist as missing author`() {
        fun document(
            body: String,
        ) = Jsoup.parse(
            "<h1 class='entry-title'>Fixture</h1><div class='thumb'></div><div class='tsinfo'>$body</div>",
            "https://hijala.com/fixture/",
        )
        val result = Hijala().parseMangaDetails(
            document(
                "<div class='imptdt'>المؤلف <i>تشوجونج (Chugong)</i></div><div class='imptdt'>الرسام <i>Redice Studio</i></div>",
            ),
            manga("/fixture/"),
        )
        result.author shouldBe "تشوجونج (Chugong)"
        result.artist shouldBe "Redice Studio"
        val second = Hijala().parseMangaDetails(
            document("<div class='imptdt'>الرسام <i>Sleepy-C</i></div>"),
            manga("/fixture/"),
        )
        second.author shouldBe null
        second.artist shouldBe "Sleepy-C"
    }

    @Test fun `conflicts placeholders descriptions and other work redirects supply no identity`() {
        MangaDar().parseMangaDetails(dar(row("المؤلف", "Unknown")), manga("/manga/fixture/")).author shouldBe null
        MangaDar().parseMangaDetails(
            dar(row("المؤلف", "Writer A") + row("المؤلف", "Writer B")),
            manga("/manga/fixture/"),
        ).author shouldBe
            null
        MangaDar().parseMangaDetails(
            dar(row("المؤلف", "Writer A"), "/manga/other/"),
            manga("/manga/fixture/"),
        ).author shouldBe
            null
        MangaDar().parseMangaDetails(
            dar("<p>المؤلف Writer A</p><div><span>المؤلف</span></div><div><span>الرسام</span><a>Artist</a></div>"),
            manga("/manga/fixture/"),
        ).author shouldBe
            null
    }
}
