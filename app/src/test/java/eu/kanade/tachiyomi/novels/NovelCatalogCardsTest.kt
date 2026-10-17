package eu.kanade.tachiyomi.novels

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Authored canonical mappings; no live source crawling or title-based approval. */
class NovelCatalogCardsTest {
    private val arabic = Novel("novel.a", "https://a.example/work", "عنوان عربي")
    private val english = Novel("novel.b", "https://b.example/work", "English title")
    private fun work(key: String, vararg editions: Novel) = UnifiedNovelWork(key, editions.toList(), editions.first().id)
    private fun project(state: NovelCatalogState, requested: List<Novel>? = null) = NovelCatalogCards.project(state, requested) { true }

    @Test fun existingCanonicalWorkAppearsOnceAcrossSourcesPagesAndLanguages() {
        val state = NovelCatalogState(listOf(work("root", arabic, english)))
        val cards = project(state, listOf(arabic, english, arabic, english))
        cards.size shouldBe 1
        cards.single().editions.map { it.id }.toSet() shouldBe setOf(arabic.id, english.id)
        project(state).map { it.id } shouldBe cards.map { it.id }
    }
    @Test fun currentAliasesDeduplicateRestoredOldRootsWithoutMigratingRecords() {
        val state = NovelCatalogState(listOf(work("old", arabic), work("root", english)), aliases=mapOf("old" to "root"))
        project(state).single().id shouldBe "root"
        project(state).single().editions.size shouldBe 2
        state.works.size shouldBe 2
    }
    @Test fun unverifiedSameTitlesAndSequelsStaySeparate() {
        val other = english.copy(title=arabic.title)
        val sequel = english.copy(url=english.url+"-part-2", title=arabic.title+" 2")
        val state=NovelCatalogState(listOf(work("a",arabic),work("b",other),work("c",sequel)))
        project(state).size shouldBe 3
    }
    @Test fun recentRowsDoNotRepeatInDiscoveryAndUnknownCardsAreNotDropped() {
        val new = arabic.copy(url=arabic.url+"-new")
        val state=NovelCatalogState(listOf(work("root",arabic,english)))
        val recent=project(state,listOf(english,english))
        val grid=project(state,listOf(arabic,new))
        NovelCatalogCards.excluding(grid,recent).single().primary.id shouldBe new.id
        NovelCatalogCards.excluding(grid,emptyList()).size shouldBe 2
    }
    @Test fun actualVerifiedCountsChooseEditionAndSavedPositionsRemainUnchanged() {
        val saved=NovelLibraryItem(arabic,true,NovelReadingPosition(NovelChapter("https://a.example/c/1","1",0),7,9))
        val state=NovelCatalogState(listOf(work("root",arabic.copy(chapterCount=3333),english)),evidence=mapOf(
            arabic.id to NovelEditionEvidence(true,90,true),english.id to NovelEditionEvidence(true,850,true)))
        project(state).single().primary.id shouldBe english.id
        saved.position!!.paragraph shouldBe 7
        saved.novel.id shouldBe arabic.id
        project(state).single().id shouldBe project(state.copy(evidence=emptyMap())).single().id
    }
    @Test fun revokedRelationshipSplitsPresentationAndInactiveSourcesStayHidden() {
        val grouped=NovelCatalogState(listOf(work("root",arabic,english)))
        project(grouped).size shouldBe 1
        val split=grouped.copy(works=listOf(work("a",arabic),work("b",english)))
        project(split).size shouldBe 2
        NovelCatalogCards.project(split, active={it==english.sourceId}).single().primary.id shouldBe english.id
    }
}
