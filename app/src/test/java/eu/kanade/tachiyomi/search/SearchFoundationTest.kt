package eu.kanade.tachiyomi.search

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SearchFoundationTest {
    private fun context(kind: SearchWorkKind = SearchWorkKind.COMIC, query: String = "A") =
        SearchContext(kind, query, listOf("source-a", "source-b"), setOf("ar"), mapOf("status" to "all"))

    @Test fun `ABA never accepts an obsolete callback or pagination result`() {
        val sessions = SearchSessionCoordinator()
        val old = sessions.begin(context()); sessions.begin(context(query = "B"))
        val current = sessions.begin(context())
        sessions.accepts(old) shouldBe false
        sessions.accepts(old.generation) shouldBe false
        sessions.accepts(current) shouldBe true
        sessions.invalidate(); sessions.accepts(current) shouldBe false
    }

    @Test fun `context ownership snapshots mutable provider language and filter inputs`() {
        val providers = mutableListOf("a"); val languages = mutableSetOf("ar"); val filters = mutableMapOf("status" to "all")
        val token = SearchSessionCoordinator().begin(SearchContext(SearchWorkKind.NOVEL, " A ", providers, languages, filters))
        providers += "b"; languages += "en"; filters["status"] = "new"
        token.context shouldBe SearchContext(SearchWorkKind.NOVEL, "A", listOf("a"), setOf("ar"), mapOf("status" to "all"))
    }

    @Test fun `different screen owners cannot accept each others tokens`() {
        val first = SearchSessionCoordinator(); val second = SearchSessionCoordinator()
        val firstToken = first.begin(context()); val secondToken = second.begin(context())
        firstToken.generation shouldBe secondToken.generation
        first.accepts(secondToken) shouldBe false
        second.accepts(firstToken) shouldBe false
    }

    @Test fun `cache isolates content kind providers order languages and filters`() {
        val cache = SearchSnapshotCache<String>(); val key = context(); cache.put(key, "raw")
        cache.get(key.copy(query = " A ")) shouldBe "raw"
        for (other in listOf(key.copy(kind = SearchWorkKind.NOVEL), key.copy(providers = key.providers.reversed()),
            key.copy(languages = setOf("en")), key.copy(filters = mapOf("status" to "new")), key.copy(query = "B"))) {
            cache.get(other) shouldBe null
        }
    }

    @Test fun `cache is bounded LRU and does not resurrect expired or future snapshots`() {
        var now = 0L; val cache = SearchSnapshotCache<String>(2, 10) { now }
        val a = context(); val b = context(query = "B"); val c = context(query = "C")
        cache.put(a, "a"); cache.put(b, "b"); cache.get(a); cache.put(c, "c")
        cache.get(b) shouldBe null; cache.size() shouldBe 2
        now = 10; cache.get(a) shouldBe null
        now = -1; cache.get(c) shouldBe null
        cache.clear(); cache.size() shouldBe 0
    }

    @Test fun `terminal projection preserves every provider route and real chapter evidence`() {
        val a = SearchWorkCandidate("a:/work", "a", SearchWorkKind.COMIC, "Title", chapters = SearchChapterEvidence(850, true))
        val b = a.copy(id = "b:/work", source = "b", chapters = SearchChapterEvidence(90, true))
        val group = SearchWorkGroup("verified-work", listOf(a, b, a), a, false)
        val card = WorkPresentationProjection.project(listOf(group), mapOf(a.editionKey to "a-route", b.editionKey to "b-route")).single()
        card.selected shouldBe "a-route"; card.editions shouldBe listOf("a-route", "b-route")
        card.chapters.distinct shouldBe 850
        assertThrows(IllegalArgumentException::class.java) { WorkPresentationProjection.project(listOf(group), mapOf(a.editionKey to "a-route")) }
        assertThrows(IllegalArgumentException::class.java) { WorkPresentationProjection.project(listOf(group, group), mapOf(a.editionKey to "a", b.editionKey to "b")) }
    }
}
