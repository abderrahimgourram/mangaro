package eu.kanade.tachiyomi.search

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.service.VerifiedCatalogIdentity
import tachiyomi.domain.manga.service.WorkMetadata

class PresentationAliasAttachmentTraceTest {
    @Test
    fun `new trace correlates keys without logging URL credentials or query tokens`() {
        val secret = "synthetic-hidden-value"
        val row = SearchWorkCandidate(
            "https://fixture.example/work?token=$secret",
            "provider-$secret?token=x",
            SearchWorkKind.COMIC,
            "Fixture Public Work",
        )
        val events = mutableListOf<String>()
        val previous = WorkIdentityDiagnostics.traceObserver
        WorkIdentityDiagnostics.traceObserver = events::add
        try {
            WorkIdentityDiagnostics.groups("fixture", mapOf("raw?token=$secret" to listOf(row)))
            WorkIdentityDiagnostics.navigation(
                "raw?token=$secret",
                "safe-source",
                "https://user:$secret@fixture.example/work",
            )
            WorkIdentityDiagnostics.comparison(
                "fixture",
                row,
                row,
                WorkIdentityDecision(WorkIdentityVerdict.DISTINCT_OR_INSUFFICIENT, "fixture"),
            )
            events.size shouldBe 3
            events.none { secret in it } shouldBe true
            Json.parseToJsonElement(events.first()).jsonObject.getValue("groups").jsonArray.single()
                .jsonObject.getValue("keyDigest").jsonPrimitive.content.length shouldBe 64
        } finally {
            WorkIdentityDiagnostics.traceObserver = previous
        }
    }

    private fun presentation(
        vararg rows: SearchWorkCandidate,
    ) = SearchResultPresentationFilter(probablePresentation = true)
        .present(0, rows.toList())!!

    @Test
    fun `declared translated alias is usable even when the other source has no reciprocal translation`() {
        val translated = SearchWorkCandidate(
            "a:/work/a",
            "a",
            SearchWorkKind.COMIC,
            "رسول المدار البعيد",
            aliases = setOf("Courier of the Distant Orbit"),
            author = "Ada Smith",
            format = "manhwa",
        )
        val untranslated = SearchWorkCandidate(
            "b:/work/b",
            "b",
            SearchWorkKind.COMIC,
            "Courier of the Distant Orbit",
            format = "manhwa",
        )
        presentation(translated, untranslated).single().members.size shouldBe 2
        SearchResultPresentationFilter().present(0, listOf(translated, untranslated))!!.size shouldBe 2
    }

    @Test
    fun `one way alias never waives an actual identity creator sequel or medium veto`() {
        val a = SearchWorkCandidate(
            "a",
            "a",
            SearchWorkKind.COMIC,
            "رسول المدار البعيد",
            aliases = setOf("Courier of the Distant Orbit"),
            author = "Ada Smith",
            format = "manhwa",
        )
        val b = SearchWorkCandidate("b", "b", SearchWorkKind.COMIC, "Courier of the Distant Orbit", format = "manhwa")
        presentation(a, b.copy(author = "Jane White")).size shouldBe 2
        presentation(
            a.copy(externalIds = mapOf("anilist" to "100")),
            b.copy(externalIds = mapOf("anilist" to "200")),
        ).size shouldBe
            2
        presentation(a, b.copy(title = b.title + " Side Story", aliases = setOf(b.title))).size shouldBe 2
        presentation(a, b.copy(kind = SearchWorkKind.NOVEL, format = "novel")).size shouldBe 2
        presentation(
            a.copy(originalTitle = "The First Original Story"),
            b.copy(originalTitle = "Another Original Story"),
        ).size shouldBe
            2
        presentation(a, b.copy(title = "A Different Lunar Adventure")).size shouldBe 2
        val conflicting = a.copy(id = "c", source = "c", author = "Jane White", externalIds = mapOf("anilist" to "200"))
        val groups = presentation(a.copy(externalIds = mapOf("anilist" to "100")), b, conflicting)
        groups.size shouldBe 2
        groups.none { g -> g.members.any { it.id == "a" } && g.members.any { it.id == "c" } } shouldBe true
        groups.flatMap { it.members }.map { it.id }.toSet() shouldBe setOf("a", "b", "c")
    }

    private fun recorded(): List<SearchWorkCandidate> {
        val json = javaClass.getResource("/search/recorded-presentation-attachment.json")!!.readText()
        return Json.parseToJsonElement(json).jsonObject.getValue("candidates").jsonArray.map { element ->
            val row = element.jsonObject
            fun text(key: String) = row[key]?.jsonPrimitive?.contentOrNull
            val source = text("source")!!
            val route = text("url")!!
            val aliases = row.getValue("aliases").jsonArray.map { it.jsonPrimitive.content }.toSet()
            val external = row.getValue("externalIds").jsonObject.mapValues { it.value.jsonPrimitive.content }
            val manga = Manga.create().copy(source = source.toLong(), url = route, title = text("title")!!)
            val identity = VerifiedCatalogIdentity.from(
                WorkMetadata.from(manga).copy(
                    aliases = aliases,
                    author = text("author"),
                    artist = text("artist"),
                    format = text("format"),
                    externalIds = external,
                ),
            )
            SearchWorkCandidate(
                "$source:$route", source, SearchWorkKind.COMIC, manga.title,
                aliases = aliases, author = text("author"), artist = text("artist"), format = text("format"),
                externalIds = external, catalogIdentity = identity,
                chapters = SearchChapterEvidence(
                    text("count")?.toInt(),
                    row.getValue("complete").jsonPrimitive.boolean,
                ),
                metadataOrigin = "captured-signed-release-public-metadata",
            )
        }
    }

    @Test
    fun `recorded one-way translated alias attaches missing-credit edition without changing strict identity`() {
        val rows = recorded()
        val trace = mutableListOf<String>()
        WorkIdentityDiagnostics.traceObserver = {
            trace += it
            println("ACTUAL_POLICY_TRACE $it")
        }
        try {
            val policy = WorkIdentityResolverV2()
            for (a in rows) {
                for (b in rows.filter { it.source > a.source }) {
                    val strict = policy.decide(a, b)
                    val visible = policy.presentationDecision(policy.evidence(a, null), policy.evidence(b, null))
                    println("PAIR ${a.source} -> ${b.source} strict=${strict.reason} visible=${visible.reason}")
                }
            }
            val strictBefore = SearchResultPresentationFilter().present(0, rows)!!
            val filter = SearchResultPresentationFilter(probablePresentation = true)
            val actual = filter.present(0, rows)!!
            println("FINAL_CARD_KEYS " + actual.map { g -> Triple(g.key, g.winner.id, g.members.map { it.id }) })
            actual.size shouldBe 1
            actual.single().members.map { it.id }.toSet() shouldBe rows.map { it.id }.toSet()
            actual.single().winner.chapters.distinct shouldBe 193
            val cached = SearchResultPresentationFilter(probablePresentation = true)
            val oldRows = Json.parseToJsonElement(
                javaClass.getResource("/search/recorded-presentation-attachment.json")!!.readText(),
            )
                .jsonObject.getValue("candidates").jsonArray.associate { element ->
                    val row = element.jsonObject
                    row.getValue("source").jsonPrimitive.content to row.getValue("observedGroup").jsonPrimitive.content
                }
            // Feed the two actual old memberships back as cache hints. They must not
            // become authority or bypass the corrected direct pair comparisons.
            cached.restore(
                rows.groupBy { oldRows.getValue(it.source) }.map { (key, members) ->
                    SearchWorkGroup(key, members, members.first(), true)
                },
            )
            cached.present(0, rows.reversed())!!.single().members.map { it.id }.toSet() shouldBe
                rows.map { it.id }.toSet()
            val projected = WorkPresentationProjection.project(
                actual,
                rows.associate {
                    it.editionKey to it.id
                },
            ).single()
            projected.editions.toSet() shouldBe rows.map { it.id }.toSet()
            projected.selected shouldBe actual.single().winner.id
            SearchResultPresentationFilter().present(0, rows) shouldBe strictBefore
            trace.any { "presentation-attachment" in it } shouldBe true
        } finally {
            WorkIdentityDiagnostics.traceObserver = null
        }
    }
}
