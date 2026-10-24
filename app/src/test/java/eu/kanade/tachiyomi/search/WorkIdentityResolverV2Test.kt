package eu.kanade.tachiyomi.search

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.service.CatalogIdentity
import java.nio.file.Files
import java.nio.file.Path

class WorkIdentityResolverV2Test {
    private fun c(
        id: String,
        title: String = "Distinctive original story",
        author: String? = null,
        aliases: Set<String> = emptySet(),
    ) =
        SearchWorkCandidate(id, "provider-$id", SearchWorkKind.COMIC, title, aliases, author = author)
    private fun cards(vararg rows: SearchWorkCandidate) = SearchResultPresentationFilter().present(0, rows.toList())!!
    private val engine = WorkIdentityResolverV2()

    @Test fun sourceDeclaredTranslationAnnotationDoesNotBecomeASequelButNeedsCorroboration() {
        val a = c("a", "A Distinctive Public Story", "Fixture Writer")
        val marked = c("b", "A Distinctive Public Story (مترجم)", "Fixture Writer", setOf(a.title))
        cards(a, marked).size shouldBe 1
        cards(a, marked.copy(author = null)).size shouldBe 2
        cards(a, marked.copy(title = "A Distinctive Public Story (Sequel)")).size shouldBe 2
        cards(
            a,
            marked.copy(externalIds = mapOf("anilist" to "987")),
            a.copy(
                id = "c",
                externalIds = mapOf("anilist" to "654"),
            ),
        ).size shouldBe 2
    }

    @Test fun noAuthorityOrCreatorCannotBeInvented() {
        cards(c("a"), c("b")).size shouldBe 2
    }

    @Test fun independentlyCorroboratedTitlesAreReversiblePresentationOnly() {
        val a = c("a", author = "Fixture creator")
        val b = c("b", author = "Fixture creator")
        engine.decide(a, b).verdict shouldBe WorkIdentityVerdict.LIKELY_SAME_WORK
        cards(a, b).single().members.size shouldBe 2
        cards(a, b.copy(author = null)).size shouldBe 2
    }

    @Test fun arabicSpellingAndVerifiedAliasRetainTheirRealEditions() {
        val a = c("a", "أَمِير الـفتيان الأعلى", "Creator", setOf("Original Korean Story"))
        val b = c("b", "امير الفتيان الاعلي", "Creator")
        cards(a, b).size shouldBe 1
        cards(a, c("c", "Original Korean Story", "Creator")).size shouldBe 1
    }

    @Test fun originalRomanizedIdentityPlusCreatorCanJoinDifferentTranslations() {
        val a = c("a", "العنوان المترجم أ", "Author").copy(originalTitle = "Dokja ui Iyagi")
        val b = c("b", "A Different Translation", "Author").copy(originalTitle = "Dokja ui Iyagi")
        cards(a, b).size shouldBe 1
    }

    @Test fun recordedShadowSlaveMetadataUsesTwoNamesWithoutInventingMissingAuthor() {
        val a = SearchWorkCandidate(
            "novel.cenele:https://cenele.com/cont/shadow-novel/",
            "novel.cenele",
            SearchWorkKind.NOVEL,
            "عبد الظل",
            author = "guiltythree",
            originalTitle = "shadow slave",
        )
        val b = SearchWorkCandidate(
            "novel.kolnovel:https://kolnovel.com/series/عبد-الظل/",
            "novel.kolnovel",
            SearchWorkKind.NOVEL,
            "عبد الظل",
            originalTitle = "shadow slave",
        )
        engine.decide(a, b).verdict shouldBe WorkIdentityVerdict.LIKELY_SAME_WORK
        SearchResultPresentationFilter().present(0, listOf(a, b))!!.single().members.size shouldBe 2
        cards(
            c("a").copy(originalTitle = "Distinctive original story"),
            c("b").copy(originalTitle = "Distinctive original story"),
        ).size shouldBe 2
    }

    @Test fun contradictoryOriginalsAuthorsAndQualifiersRemainSeparate() {
        val a = c("a", author = "Creator").copy(originalTitle = "Original One")
        listOf(
            a.copy(id = "b", source = "other", originalTitle = "Original Two"),
            c("b", author = "Other creator"),
            c("b", "Distinctive original story II", "Creator"),
            c("b", "Distinctive original story Side Story", "Creator"),
            c("b", "Distinctive original story Remake", "Creator"),
        ).forEach { cards(a, it).size shouldBe 2 }
    }

    @Test fun trustedIdConflictVetoesCorroboratingNames() {
        cards(
            c("a", author = "Creator").copy(externalIds = mapOf("anilist" to "1")),
            c("b", author = "Creator").copy(externalIds = mapOf("anilist" to "2")),
        ).size shouldBe 2
    }

    @Test fun aliasesCannotBridgeUnrelatedWorks() {
        val a = c("a", "Distinctive story alpha", "Creator", setOf("Shared story name"))
        val b = c("b", "Shared story name", "Creator", setOf("Distinctive story beta"))
        val d = c("d", "Distinctive story beta", "Creator")
        cards(a, b, d).map { it.members.size }.sorted() shouldBe listOf(1, 2)
    }

    @Test fun namedAndRomanVolumesCannotMergeThroughASharedSeriesAlias() {
        for ((left, right) in listOf(
            "Volume VII" to "Volume VIII",
            "Part Dawn" to "Part Dusk",
            "المجلد السابع" to "المجلد الثامن",
        )) {
            val a = c("a", "Distinctive story $left", "Creator", setOf("Distinctive story"))
            val b = c("b", "Distinctive story $right", "Creator", setOf("Distinctive story"))
            cards(a, b).size shouldBe 2
        }
    }

    @Test fun sameProviderDifferentWorksRequireProof() {
        val a = c("a", author = "Creator")
        cards(a, c("b", author = "Creator").copy(source = a.source)).size shouldBe 2
    }

    @Test fun placeholdersAreNotCreatorCorroboration() {
        listOf("unknown", "غير معروف", "مجهول", "anonymous").forEach {
            cards(c("a", author = it), c("b", author = it)).size shouldBe
                2
        }
    }

    @Test fun novelupdatesAndSeparateNativeGroupsCanConnectWithoutCanonicalWrites() {
        val a = c("a").copy(kind = SearchWorkKind.NOVEL, externalIds = mapOf("novelupdates" to "fixture-work"))
        val b = c("b", "العنوان الأصلي").copy(kind = SearchWorkKind.NOVEL, externalIds = a.externalIds)
        val filter = SearchResultPresentationFilter()
        filter.present(
            0,
            listOf(a, b),
            mapOf("native-a" to listOf(a), "native-b" to listOf(b)),
        )!!.single().members.size shouldBe
            2
        filter.present(
            0,
            listOf(a, b.copy(externalIds = emptyMap())),
            mapOf("native-a" to listOf(a), "native-b" to listOf(b)),
        )!!.size shouldBe
            2
    }

    @Test fun newCreatorEvidenceConsolidatesWithoutLosingAnyEdition() {
        val filter = SearchResultPresentationFilter(7)
        filter.present(7, listOf(c("a"), c("b")))!!.size shouldBe 2
        val rows = listOf(c("a", author = "Creator"), c("b", author = "Creator"))
        val first = filter.present(7, rows)!!.single()
        filter.present(6, rows) shouldBe null
        filter.present(7, rows.reversed() + rows)!!.single().key shouldBe first.key
        filter.present(7, rows.map { it.copy(author = null) })!!.size shouldBe 2
    }

    @Test fun hundredVerifiedEditionsRetainEveryMember() {
        val rows = (1..100).map { c("$it").copy(externalIds = mapOf("anilist" to "123")) }
        SearchResultPresentationFilter().present(0, rows)!!.single().members.size shouldBe 100
    }

    @Test fun arrivingNativeIdentityDoesNotReplaceTheExistingCardKey() {
        val a = c("z").copy(kind = SearchWorkKind.NOVEL, externalIds = mapOf("novelupdates" to "fixture-work"))
        val b = c("a").copy(kind = SearchWorkKind.NOVEL, externalIds = a.externalIds)
        val filter = SearchResultPresentationFilter()
        val key = filter.present(0, listOf(a), mapOf("native-z" to listOf(a)))!!.single().key
        filter.present(
            0,
            listOf(a, b),
            mapOf("native-z" to listOf(a), "native-a" to listOf(b)),
        )!!.single().key shouldBe key
    }

    @Test fun unchangedSnapshotReusesGroupsWhileRevocationStillRecomputes() {
        val rows = listOf(c("a", author = "Creator"), c("b", author = "Creator"))
        val filter = SearchResultPresentationFilter()
        val first = filter.present(0, rows)!!
        val again = filter.present(0, rows)!!
        (first === again) shouldBe true
        filter.present(0, rows.map { it.copy(author = null) })!!.size shouldBe 2
    }

    @Test fun publicReviewedCorpusUsesActualProviderRoutes() {
        val path = listOf(
            Path.of("src/main/res/raw/catalog_reviewed_links.json"),
            Path.of("app/src/main/res/raw/catalog_reviewed_links.json"),
        ).first(Files::exists)
        val registry = tachiyomi.domain.manga.service.ReviewedCatalogRegistry.parse(Files.readString(path))
        val data = listOf(
            Triple(6058166507489091982L, "/manga/otherworldly-evil-monarch", null),
            Triple(918460697583900080L, "/manga/otherworldly-evil-monarch/#775", "775"),
        )
        val rows = data.mapIndexed { i, (source, url, provider) ->
            val work = tachiyomi.domain.manga.service.WorkMetadata(
                tachiyomi.domain.manga.service.SourceWorkReference(source, i.toLong(), url),
                "Otherworldly Evil Monarch",
                providerId = provider,
            )
            c("$source:$url", work.title).copy(
                source = source.toString(),
                catalogIdentity = CatalogIdentity(
                    source.toString(),
                    url,
                    "$source:$url",
                    reviewedWorkKey = registry.resolve(work),
                    providerId = provider,
                ),
            )
        }
        cards(*rows.toTypedArray()).single().members.size shouldBe 2
        engine.decide(rows[0], rows[1]).verdict shouldBe WorkIdentityVerdict.VERIFIED_SAME_WORK
    }

    @Test fun diagnosticOutputHasNoDescriptionCredentialsOrQuerySecrets() {
        val row = c("https://example.org/work?token=private-token", author = "Creator")
        val text = WorkIdentityDiagnostics.snapshot("fixture", listOf(row), cards(row))
        text.contains("private-token") shouldBe false
        val sensitive = c("https://user:password@example.org/work")
        WorkIdentityDiagnostics.snapshot("fixture", listOf(sensitive), cards(sensitive)).contains("password") shouldBe
            false
    }
}
