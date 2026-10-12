package eu.kanade.tachiyomi.search

import eu.kanade.tachiyomi.novels.*
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class NovelSearchSelectionTest {
    private val a=Novel("novel.cenele","https://cenele.com/cont/fixture-only/","Fixture Shared Novel",chapterCount=3333)
    private val b=Novel("novel.seanovel","https://seanovel.org/novels/fixture-only","Fixture Shared Novel",chapterCount=199)
    private fun index(n:Novel,count:Int,complete:Boolean=true)=NovelChapterIndex(n.id,
        (1..count).map{NovelChapter(n.url+"/chapter/$it","Chapter ${if(n==a&&it==count)3333 else it}",it)},
        nextPage=if(complete)null else 2,complete=complete,fetchedPages=setOf(1),updatedAt=42)
    private fun repo(ai:NovelChapterIndex?,bi:NovelChapterIndex?):NovelRepository=mockk<NovelRepository>().also {r->
        every{r.activeSources} returns listOf(mockk<HtmlNovelSource>().apply{every{id} returns a.sourceId},mockk<HtmlNovelSource>().apply{every{id} returns b.sourceId})
        every{r.catalog} returns MutableStateFlow(NovelCatalogState())
        coEvery{r.indexSnapshot(a)} returns ai;coEvery{r.indexSnapshot(b)} returns bi
        coEvery{r.detail(any())} answers{firstArg()}
    }
    @Test fun sourceAdvertisedTotalsAndChapterLabelsCannotRankAboveActualIndex()=runTest {
        val profile=NovelSearchSelection(repo(index(a,84),index(b,205)));profile.cached(listOf(a,b))
        val group=profile.groups(listOf(a,b)).single();group.winner.id shouldBe b.id
        group.winner.chapters.distinct shouldBe 205;group.provisional shouldBe false
    }
    @Test fun incompleteIndexIsALowerBoundAndEnrichmentNeverIndexesOrDownloadsText()=runTest {
        val r=repo(index(a,400,false),index(b,205));val profile=NovelSearchSelection(r)
        profile.enrich(listOf(a,b));val group=profile.groups(listOf(a,b)).single()
        group.winner.id shouldBe a.id;group.provisional shouldBe true
        coVerify(exactly=0){r.completeIndex(any(),any(),any<Int>(),any())}
        coVerify(exactly=0){r.chapterText(any(),any())}
    }
    @Test fun anotherEditionOrMismatchedOfficialTotalCannotBecomeACompleteCount()=runTest {
        val p=NovelSearchSelection(repo(index(a,400).copy(editionId="different-work"),index(b,205).copy(declaredTotal=300)))
        p.cached(listOf(a,b));p.evidence(a.id).distinct shouldBe null;p.evidence(b.id).complete shouldBe false
    }
    @Test fun selectionCannotRewriteSavedSourceOrReadingPosition()=runTest {
        val saved=NovelLibraryItem(a,saved=true,position=NovelReadingPosition(NovelChapter(a.url+"/chapter/84","Chapter 3333",84),7,14))
        val p=NovelSearchSelection(repo(index(a,84),index(b,205)));p.cached(listOf(a,b));p.groups(listOf(a,b))
        saved.novel.id shouldBe a.id;saved.position!!.paragraph shouldBe 7;saved.position!!.offset shouldBe 14
    }
}
