package eu.kanade.tachiyomi.source.internal.mangatime

import eu.kanade.tachiyomi.source.model.FilterList
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

class MangaTimeSearchRequestTest {
    @Test
    fun `nonempty searches use public relevance ordering without changing page or query`() {
        // The public endpoint can broaden a decorated displayed title to a general
        // feed. Popularity ordering then pushes the exact work off the first page.
        val query = "A Different Journey (مترجم)"
        val request = MangaTime().searchMangaRequest(2, " $query ", FilterList())
        val input = Json.parseToJsonElement(request.url.queryParameter("input")!!).jsonObject["json"]!!.jsonObject
        input["query"]!!.jsonPrimitive.content shouldBe query
        input["page"]!!.jsonPrimitive.content shouldBe "2"
        input["limit"]!!.jsonPrimitive.content shouldBe "24"
        input["sortBy"]!!.jsonPrimitive.content shouldBe "relevance"
    }

    @Test
    fun `empty search and popular discovery retain popularity ordering`() {
        val source = MangaTime()
        for (request in listOf(source.searchMangaRequest(1, " ", FilterList()), source.popularMangaRequest(1))) {
            val input = Json.parseToJsonElement(request.url.queryParameter("input")!!).jsonObject["json"]!!.jsonObject
            input["sortBy"]!!.jsonPrimitive.content shouldBe "popularity"
            input.containsKey("query") shouldBe false
        }
    }
}
