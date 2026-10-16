package eu.kanade.tachiyomi.search

import eu.kanade.tachiyomi.ui.home.GroupDiscoveryItems
import eu.kanade.tachiyomi.ui.home.HomeDiscoveryItem
import eu.kanade.tachiyomi.ui.home.PreferredMangaVariants
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.manga.service.*

/** Authored identity fixtures, not a claim that a particular live duplicate pair was verified. */
class VerifiedCatalogIdentityTest {
    private fun work(source: Long, id: Long = source, url: String = "/work/$id", external: String? = null) =
        WorkMetadata(SourceWorkReference(source, id, url), "Same title", externalIds = external?.let { mapOf("anilist" to it) }.orEmpty())
    private fun search(work: WorkMetadata) = SearchWorkCandidate("${work.reference.sourceId}:${work.reference.url}", work.reference.sourceId.toString(),
        SearchWorkKind.COMIC, work.title, externalIds = work.externalIds, catalogIdentity = VerifiedCatalogIdentity.from(work))
    private fun home(work: WorkMetadata): HomeDiscoveryItem {
        PreferredMangaVariants.remember(Manga.create().copy(id = work.reference.mangaId, source = work.reference.sourceId,
            url = work.reference.url, title = work.title, memo = buildJsonObject {
                put(WorkMetadata.MEMO_KEY, buildJsonObject { put("externalIds", JsonObject(work.externalIds.mapValues { JsonPrimitive(it.value) })) })
            }))
        return HomeDiscoveryItem(work.reference.mangaId, work.title, MangaCover(work.reference.mangaId, work.reference.sourceId, false, null, 0),
            work.reference.sourceId, "Source ${work.reference.sourceId}", work.reference.url)
    }
    @Test fun repeatedExactEditionAndOverlappingPagesHaveOneCard() {
        val a = work(80001, url = "/route?variant=1#series-1")
        val duplicateLocalRow = a.copy(reference = a.reference.copy(mangaId = 80002))
        val first = GroupDiscoveryItems.group(listOf(home(a), home(a)))
        val next = GroupDiscoveryItems.group(first + home(duplicateLocalRow))
        next.size shouldBe 1
        next.single().canonicalWorkId shouldBe first.single().canonicalWorkId
        (listOf(next.single()) + next.single().alternatives).map { it.mangaId }.toSet() shouldBe setOf(80001L, 80002L)
    }
    @Test fun reviewedCrossSourceWorkRetainsAlternativesAndStableKey() {
        val root = "a".repeat(64)
        val a = work(1).copy(reviewedWorkKey = root)
        val b = work(2).copy(reviewedWorkKey = root, title = "عنوان آخر")
        val selector = SearchWorkSelection()
        val key = selector.select(listOf(search(a))).single().key
        val group = selector.select(listOf(search(a), search(b))).single()
        group.key shouldBe key
        group.key shouldBe "work:$root"
        group.members.map { it.catalogIdentity!!.route }.toSet() shouldBe setOf("/work/1", "/work/2")
        WorkLinker.group(listOf(b, a)).single().id shouldBe key
    }
    @Test fun titleCreatorAliasCoverAndCountAreNotAuthority() {
        val a = search(work(1)).copy(author = "Shared creator", aliases = setOf("Other title"), coverDigest = "b".repeat(64),
            chapters = SearchChapterEvidence(900, true))
        val b = search(work(2)).copy(author = a.author, aliases = a.aliases, coverDigest = a.coverDigest, chapters = a.chapters)
        SearchWorkSelection().select(listOf(a, b)).size shouldBe 2
        WorkLinker.group(listOf(work(1).copy(author = a.author), work(2).copy(author = a.author))).size shouldBe 2
    }
    @Test fun contradictoryExternalAndReviewedIdentitiesCannotMerge() {
        val a = work(1, external = "1").copy(externalIds = mapOf("anilist" to "1", "kitsu" to "2"), reviewedWorkKey = "a".repeat(64))
        val b = work(2, external = "1").copy(externalIds = mapOf("anilist" to "1", "kitsu" to "3"), reviewedWorkKey = "a".repeat(64))
        WorkLinker.group(listOf(a, b)).size shouldBe 2
        val searchGroups = SearchWorkSelection().select(listOf(search(b), search(a)))
        searchGroups.size shouldBe 2
        searchGroups.map { it.key }.toSet() shouldBe WorkLinker.group(listOf(a, b)).map { it.id }.toSet()
        WorkLinker.match(a, a.copy(reference = b.reference, reviewedWorkKey = "b".repeat(64))) shouldBe null
    }
    @Test fun onePieceAndStrongWorldZeroStaySeparate() {
        val a = work(1).copy(title = "One Piece")
        val b = work(2).copy(title = "One Piece Strong World 0", aliases = setOf("One Piece"))
        WorkLinker.group(listOf(a, b)).size shouldBe 2
        SearchWorkSelection().select(listOf(search(a), search(b))).size shouldBe 2
    }
    @Test fun homeSearchAndRestoredCacheAgreeWithIdenticalExternalEvidence() {
        val a = work(81001, external = "fixture-verified-1")
        val b = work(81002, external = "fixture-verified-1").copy(title = "ترجمة أخرى")
        val home = GroupDiscoveryItems.group(listOf(home(a), home(b))).single()
        val selector = SearchWorkSelection()
        val original = selector.select(listOf(search(a), search(b)))
        home.canonicalWorkId shouldBe original.single().key
        home.availableVersions.size shouldBe 2
        val restored = SearchWorkSelection().also { it.restore(original) }
        restored.select(listOf(search(b), search(a))).single().key shouldBe home.canonicalWorkId
        GroupDiscoveryItems.group(listOf(home)).single().canonicalWorkId shouldBe home.canonicalWorkId
    }
    @Test fun mappingCacheRestartExpiryAndRevocationAreNonDestructive() {
        var clock = 1_000L
        val a = work(1); val b = work(2)
        val ka = VerifiedCatalogIdentity.from(a).editionKey; val kb = VerifiedCatalogIdentity.from(b).editionKey
        val mappings = ReviewedCatalogMappings { clock }
        mappings.replace(setOf(ka, kb), mapOf(ka to "c".repeat(64), kb to "c".repeat(64)))
        val restored = ReviewedCatalogMappings { clock }.also { it.restore(mappings.snapshot()) }
        fun resolved() = listOf(a.copy(reviewedWorkKey = restored.resolve(ka)), b.copy(reviewedWorkKey = restored.resolve(kb)))
        val selection = SearchWorkSelection()
        selection.select(resolved().map(::search)).size shouldBe 1
        restored.replace(setOf(kb), emptyMap())
        val split = selection.select(resolved().map(::search))
        split.size shouldBe 2; split.map { it.key }.distinct().size shouldBe 2
        WorkLinker.group(resolved()).size shouldBe 2
        clock += ReviewedCatalogMappings.TTL
        restored.resolve(ka) shouldBe null
        restored.pending(setOf(ka, kb)).size shouldBe 2
        a.reference.url shouldBe "/work/1"; b.reference.url shouldBe "/work/2"
    }
    @Test fun queryAndFragmentAreMeaningfulWithoutProviderProof() {
        val a = work(1, url = "/work?id=1#first")
        val b = work(1, id = 2, url = "/work?id=1#second")
        WorkLinker.group(listOf(a, b)).size shouldBe 2
        WorkLinker.group(listOf(a, b.copy(providerId = "server-1"), a.copy(providerId = "server-2"))).size shouldBe 2
        WorkLinker.group(listOf(a.copy(providerId = "server-1"), b.copy(providerId = "server-1"))).size shouldBe 1
        WorkLinker.match(a.copy(providerId = "server-1"), b.copy(reference = b.reference.copy(sourceId = 2), providerId = "server-1")) shouldBe null
    }
    @Test fun presentationPreservesPrivateLibraryReaderAndDownloadIdentities() {
        val a = Manga.create().copy(id = 82001, source = 82001, url = "/a?edition=1#id", title = "Work", favorite = true,
            viewerFlags = 7, chapterFlags = 3, notes = "private", dateAdded = 1234)
        val b = a.copy(id = 82002, source = 82002, url = "/b", favorite = false, notes = "")
        val before = listOf(a, b)
        val grouped = WorkLinker.group(before.map { WorkMetadata.from(it).copy(externalIds = mapOf("anilist" to "12")) })
        grouped.single().members.map { it.reference } shouldBe before.map { SourceWorkReference(it.source, it.id, it.url) }
        listOf(a, b) shouldBe before
        // No persistence API is involved; existing user-owned records remain the exact input objects.
    }
    @Test fun incompleteOrRevokedMappingCannotBridgeExternalConflicts() {
        val a = work(1, external = "1")
        val bridge = work(2)
        val b = work(3, external = "2")
        WorkLinker.group(listOf(a, bridge, b)).size shouldBe 3
        val unsupported = a.copy(externalIds = mapOf("sourceLocal" to "same"))
        WorkLinker.match(unsupported, b.copy(externalIds = unsupported.externalIds)) shouldBe null
    }
    @Test fun completeLinkIdentityBridgesAgreeAcrossHomeSearchAndArrivalOrders() {
        val a = work(1, external = "1")
        val b = work(2, external = "1").copy(externalIds = mapOf("anilist" to "1", "kitsu" to "2"))
        val c = work(3).copy(externalIds = mapOf("kitsu" to "2"))
        val expected = WorkLinker.group(listOf(a, b, c)).associate { it.id to it.members.map { m -> VerifiedCatalogIdentity.from(m).editionKey }.toSet() }
        listOf(listOf(a,b,c),listOf(c,b,a),listOf(b,a,c),listOf(b,c,a)).forEach { rows ->
            SearchWorkSelection().select(rows.mapIndexed { priority, row -> search(row).copy(priority = priority) })
                .associate { it.key to it.members.map { m -> m.catalogIdentity!!.editionKey }.toSet() } shouldBe expected
        }
        val key = WorkLinker.group(listOf(b)).single().id
        WorkLinker.group(listOf(b,c)).single().id shouldBe key
    }

}
