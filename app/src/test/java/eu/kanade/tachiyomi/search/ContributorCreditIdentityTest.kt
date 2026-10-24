package eu.kanade.tachiyomi.search

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ContributorCreditIdentityTest {
    private val resolver = WorkIdentityResolverV2()
    private fun edition(source: String, artist: String) = SearchWorkCandidate(
        id = "$source:/public-edition",
        source = source,
        kind = SearchWorkKind.COMIC,
        title = "Return of the Mount Hua Sect",
        author = "Biga",
        artist = artist,
    )

    @Test fun `captured release credits cannot split independently corroborated comic editions`() {
        val english = "The Beginning After the End"
        val arabic = "البداية بعد النهاية"
        val time = edition("215553151312092548", "Fuyuki23; MGK-Story").copy(
            id = "215553151312092548:/webtoon/the-beginning-after-the-end",
            title = arabic,
            aliases = setOf(english, "TBATE", arabic),
            author = "TurtleMe",
            format = "webtoon",
            chapters = SearchChapterEvidence(distinct = 265, complete = true, readable = true),
        )
        val dar = edition("3975276517041363504", "YOYO, Wudijiurenwang, Fuyuki23, 333335, Eginhardt").copy(
            id = "3975276517041363504:/manga/the-beginning-after-the-end/",
            title = english,
            aliases = setOf(english, "TBATE", arabic),
            author = "TurtleMe",
            format = "manhwa",
        )
        resolver.decide(time, dar).verdict shouldBe WorkIdentityVerdict.LIKELY_SAME_WORK
        val hijala = edition("917436262447415426", "Fuyuki23").copy(
            id = "917436262447415426:/the-beginning-after-the-end/",
            title = english,
            author = "TurtleMe",
            chapters = SearchChapterEvidence(distinct = 245, complete = false),
        )
        val groups = SearchResultPresentationFilter().present(0, listOf(time, dar, hijala))!!
        groups.single().members.map { it.id }.toSet() shouldBe setOf(time.id, dar.id, hijala.id)
        groups.single().winner.id shouldBe time.id
        groups.single().winner.chapters.distinct shouldBe 265
        val late = SearchResultPresentationFilter()
        late.present(0, listOf(time, hijala))!!.size shouldBe 1
        val enriched = late.present(0, listOf(time, hijala, dar))!!.single()
        enriched.members.map { it.id }.toSet() shouldBe setOf(time.id, dar.id, hijala.id)
        late.present(0, listOf(dar, hijala, time))!!.single().key shouldBe enriched.key
        resolver.resolve(
            listOf(
                time.copy(externalIds = mapOf("anilist" to "1")),
                hijala,
                dar.copy(externalIds = mapOf("anilist" to "2")),
            ),
        ).groups.size shouldBe 2
        resolver.resolve(listOf(time, dar.copy(kind = SearchWorkKind.NOVEL, format = "novel")))
            .groups.size shouldBe 2
    }

    @Test fun `incomplete enumerated credits require independent bilingual names and a shared named writer`() {
        val a = edition("a", "First Illustrator, Shared Illustrator, Another Illustrator").copy(
            title = "The Cartographers Final Voyage",
            author = "Public Writer",
            aliases = setOf("الرحلة الأخيرة لرسام الخرائط"),
        )
        val b = a.copy(id = "b:/edition", source = "b", artist = "Shared Illustrator; Later Studio")
        resolver.resolve(listOf(a, b)).groups.size shouldBe 1
        listOf(
            b.copy(aliases = emptySet()),
            b.copy(author = null),
            b.copy(author = "Different Writer"),
            b.copy(artist = "An Entirely Different Illustrator"),
            b.copy(title = b.title + " Side Story"),
            b.copy(kind = SearchWorkKind.NOVEL),
            b.copy(edition = "remake"),
        ).forEach { other ->
            resolver.resolve(listOf(a.copy(edition = "original"), other)).groups.size shouldBe 2
        }
        resolver.resolve(listOf(a.copy(artist = "Studio; Artist One"), b.copy(artist = "Studio; Artist Two")))
            .groups.size shouldBe 2
        resolver.resolve(listOf(a.copy(artist = "Lee, Jin"), b.copy(artist = "Lee, Other")))
            .groups.size shouldBe 2
        resolver.resolve(
            listOf(a.copy(externalIds = mapOf("anilist" to "1")), b.copy(externalIds = mapOf("anilist" to "2"))),
        )
            .groups.size shouldBe 2
    }

    @Test fun `real One Piece creator spelling and declared Japanese alias are handled by actual grouping`() {
        val a = edition("3975276517041363504", "Unknown").copy(title = "One Piece", author = "Oda, Eiichiro")
        val b = edition("215553151312092548", "Unknown").copy(
            title = "ONE PIECE",
            author = "Oda Eiichirou (尾田栄一郎)",
            format = "manga",
        )
        resolver.decide(a, b).verdict shouldBe WorkIdentityVerdict.LIKELY_SAME_WORK
        resolver.resolve(listOf(a, b)).groups.size shouldBe 1
    }

    @Test fun `a source declared cross-script creator alias can corroborate without fuzzy work titles`() {
        val a = edition("1", "Unknown").copy(author = "كاتب 추공 (Chugong)")
        val b = edition("2", "Unknown").copy(author = "Chugong")
        resolver.resolve(listOf(a, b)).groups.size shouldBe 1
        resolver.resolve(listOf(a, b.copy(title = "A different work"))).groups.size shouldBe 2
    }

    @Test fun `similar author spellings without explicit Japanese evidence remain separate`() {
        val a = edition("1", "Unknown").copy(author = "Oda, Eiichiro")
        val b = edition("2", "Unknown").copy(author = "Oda Eiichirou", format = "manga")
        resolver.resolve(listOf(a, b)).groups.size shouldBe 2
    }

    @Test fun `conflicting declared Japanese names veto equal romanization and role text is no alias`() {
        val a = edition("1", "Unknown").copy(author = "Oda Eiichirou (尾田栄一郎)", format = "manga")
        val b = edition("2", "Unknown").copy(author = "Oda Eiichirou (別の著者)", format = "manga")
        resolver.resolve(listOf(a, b)).groups.size shouldBe 2
        val role = edition("3", "Unknown").copy(author = "كاتب (Editor)")
        val other = edition("4", "Unknown").copy(author = "Editor")
        resolver.resolve(listOf(role, other)).groups.size shouldBe 2
    }

    @Test fun `actual source credit list and a partial credit are compatible without inventing canonical IDs`() {
        val a = edition("917436262447415426", "LICO")
        val b = edition("215553151312092548", "ARCHE; LICO")
        resolver.decide(a, b).verdict shouldBe WorkIdentityVerdict.LIKELY_SAME_WORK
        resolver.decide(b, a).verdict shouldBe WorkIdentityVerdict.LIKELY_SAME_WORK
        a.externalIds shouldBe emptyMap()
        b.externalIds shouldBe emptyMap()
    }

    @Test fun `overlap with contradictory named collaborators is not a complete credit subset`() {
        resolver.decide(edition("1", "Studio; Artist One"), edition("2", "Studio; Artist Two"))
            .verdict shouldBe WorkIdentityVerdict.DISTINCT_OR_INSUFFICIENT
    }

    @Test fun `commas in contributor names do not become independent surname evidence`() {
        resolver.decide(edition("1", "Lee, Jin"), edition("2", "Lee, Other"))
            .verdict shouldBe WorkIdentityVerdict.DISTINCT_OR_INSUFFICIENT
        for ((full, fragment) in listOf(
            "Tolkien, John, Ronald" to "John",
            "Tolkien, John Ronald, Jr" to "John Ronald",
            "Studio, Artist, Team" to "Studio",
        )) {
            resolver.decide(edition("1", full), edition("2", fragment))
                .verdict shouldBe WorkIdentityVerdict.DISTINCT_OR_INSUFFICIENT
        }
        val writerAndFullTitle = edition("1", "First Painter, Jane Painter, Another Painter")
        resolver.decide(writerAndFullTitle, edition("2", "Jane Painter"))
            .verdict shouldBe WorkIdentityVerdict.LIKELY_SAME_WORK
        resolver.decide(writerAndFullTitle.copy(author = null), edition("2", "Jane Painter"))
            .verdict shouldBe WorkIdentityVerdict.DISTINCT_OR_INSUFFICIENT
    }

    @Test fun `compatible credit lists never override a conflicting author medium sequel or authority`() {
        val a = edition("1", "LICO")
        val b = edition("2", "ARCHE; LICO")
        listOf(
            b.copy(author = "Other Writer"),
            b.copy(kind = SearchWorkKind.NOVEL),
            b.copy(title = "Return of the Mount Hua Sect Ragnarok"),
        ).forEach { resolver.decide(a, it).verdict shouldBe WorkIdentityVerdict.DISTINCT_OR_INSUFFICIENT }
        resolver.decide(
            a.copy(externalIds = mapOf("anilist" to "132144")),
            b.copy(externalIds = mapOf("anilist" to "999999")),
        ).verdict shouldBe WorkIdentityVerdict.DISTINCT_OR_INSUFFICIENT
    }
}
