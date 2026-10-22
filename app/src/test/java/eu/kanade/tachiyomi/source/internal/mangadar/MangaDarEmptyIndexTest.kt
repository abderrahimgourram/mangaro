package eu.kanade.tachiyomi.source.internal.mangadar

import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

class MangaDarEmptyIndexTest {
    private val summary = """<div><span class="tabular-nums">0</span><span>الفصول</span></div><p>لا توجد فصول بعد</p>"""

    @Test fun `two explicit current empty signals confirm zero without fabricating chapters`() {
        MangaDar().declaredChapterCount(Jsoup.parse(summary)) shouldBe 0
        MangaDar().declaredChapterCount(Jsoup.parse("<p>لا توجد فصول بعد</p>")) shouldBe null
        MangaDar().declaredChapterCount(Jsoup.parse("<main>Network error</main>")) shouldBe null
    }

    @Test fun `imported episode metadata cannot turn unavailable actual chapter index into a verified zero`() {
        assertThrows<IOException> {
            MangaDar().declaredChapterCount(
                Jsoup.parse(
                    summary +
                        """<script type="application/ld+json">{"@type":"ComicSeries","numberOfEpisodes":148}</script>""",
                ),
            )
        }
    }

    @Test fun `empty message cannot erase published rows or continuation evidence`() {
        assertThrows<IOException> {
            MangaDar().declaredChapterCount(
                Jsoup.parse(summary + """<li class="wp-manga-chapter"><a href="/manga/test/1/">الفصل 1</a></li>"""),
            )
        }
        assertThrows<IOException> {
            MangaDar().declaredChapterCount(
                Jsoup.parse(summary + """<div class="chapter-pagination"><a href="?page=2">2</a></div>"""),
            )
        }
    }
}
