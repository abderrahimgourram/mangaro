package eu.kanade.tachiyomi.source.internal.util

import eu.kanade.tachiyomi.source.internal.hijala.Hijala
import eu.kanade.tachiyomi.source.internal.mangadar.MangaDar
import eu.kanade.tachiyomi.source.internal.mangalek.MangaLek
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

class ThreeSourceIntegrityTest {
    @Test fun `home page with title cannot initialize MangaLek details`() {
        val manga = SManga.create().apply { url = "/manga/sample/"; title = "Sample" }
        assertThrows<IOException> { MangaLek().parseMangaDetails(Jsoup.parse("<h1 class='entry-title'>Home</h1><div class='summary_image'><img src='/a.jpg'></div>", "https://mangalik.net/"), manga) }
    }
    @Test fun `lazy Hijala image resolves before placeholder and malformed rows fail`() {
        val doc = Jsoup.parse("<div id='readerarea'><img src='data:image/gif;base64,x' data-lazy-src='/real.webp'></div>", "https://hijala.com/chapter-1/")
        Hijala().parsePagesFromDocument(doc).single().imageUrl shouldBe "https://hijala.com/real.webp"
        assertThrows<IOException> { Hijala().parseChapters(Jsoup.parse("<div id='chapterlist'><ul><li>No link</li></ul></div>")) }
    }
    @Test fun `MangaDar blank alt falls through to actual title`() {
        val doc = Jsoup.parse("<a href='/manga/sample/'><img alt='' src='/cover.webp'><h3>Sample</h3></a>", "https://mangadar.com/")
        MangaDar().parseMangaFromAnchor(doc.selectFirst("a")!!)!!.title shouldBe "Sample"
    }
    @Test fun `zero needs explicit declaration and cannot coexist with continuation`() {
        HtmlMangaIntegrity.count(Jsoup.parse("<h1>Sample</h1>")) shouldBe null
        HtmlMangaIntegrity.count(Jsoup.parse("<div data-total-chapters='0'></div>")) shouldBe 0
        assertThrows<IOException> { HtmlMangaIntegrity.count(Jsoup.parse("<div data-total-chapters='0'></div><div class='chapter-pagination'><a href='?page=2'>2</a></div>")) }
    }
    @Test fun `duplicate MangaDar remote IDs are rejected`() {
        val doc = Jsoup.parse("""<div x-data='rows: [[1,1,"/manga/sample/1/",0],[1,2,"/manga/sample/2/",0]]'></div>""")
        assertThrows<IOException> { MangaDar().parseChapters(doc) }
    }
}
