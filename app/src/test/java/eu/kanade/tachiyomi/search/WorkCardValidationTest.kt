package eu.kanade.tachiyomi.search

import eu.kanade.tachiyomi.util.asJsoup
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.WorkPresentationEligibility as Gate

class WorkCardValidationTest {
    @Test
    fun `unknown candidates are held pending while genuine new and cached unavailable works survive`() {
        Gate.state("A real new work", "/manga/new").displayable shouldBe false
        Gate.state("A real new work", "/manga/new", initialized = true, indexedChapters = 0).displayable shouldBe true
        Gate.state(
            "The List of Lost Stars",
            "/manga/list-of-lost-stars",
            cover = "https://example.org/a.jpg",
        ).displayable shouldBe
            true
        Gate.state("Cached", "/manga/cached", initialized = true, unavailable = true) shouldBe
            Gate.State.CACHED_UNAVAILABLE_WORK
        Gate.state("Work directory", "/manga/", initialized = true).displayable shouldBe false
        Gate.state("Category", "/genre/action/", cover = "https://example.org/a.jpg").displayable shouldBe false
        Gate.state("Search", "/?s=story", initialized = true).displayable shouldBe false
        Gate.state("Any title", "/series/a", resourceType = "security-challenge").displayable shouldBe false
    }
    private fun response(html: String, challenge: Boolean = false): Response = Response.Builder()
        .request(Request.Builder().url("https://example.org/manga/a").build()).protocol(Protocol.HTTP_1_1)
        .code(200).message("OK").body(html.toResponseBody("text/html".toMediaType()))
        .apply { if (challenge) header("cf-mitigated", "challenge") }.build()

    @Test
    fun `HTTP200 security challenge is an error not source work content`() {
        shouldThrow<java.io.IOException> { response("<html></html>", true).asJsoup() }
        shouldThrow<java.io.IOException> {
            response(
                "<form id='challenge-form' action='/cdn-cgi/challenge'></form><script src='/cdn-cgi/challenge-platform/run.js'></script>",
            ).asJsoup()
        }
    }

    @Test
    fun `a legitimate security themed title and standalone verification widget are not blocked`() {
        response("<h1>Just a Moment</h1><div class='cf-turnstile'></div>").asJsoup().selectFirst("h1")!!.text() shouldBe
            "Just a Moment"
    }

    @Test
    fun `production presentation filter retains pending diagnostics but never puts them in card groups`() {
        val real = SearchWorkCandidate("a", "s", SearchWorkKind.COMIC, "Verified new work")
        val pending = real.copy(id = "b", title = "Unknown", presentationState = Gate.State.PENDING)
        val invalid = real.copy(id = "c", title = "Directory", presentationState = Gate.State.NON_WORK)
        SearchResultPresentationFilter(
            probablePresentation = true,
        ).present(0, listOf(real, pending, invalid))!!.flatMap {
            it.members
        }.map { it.id } shouldBe
            listOf("a")
    }
}
