package eu.kanade.tachiyomi.search

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.service.CatalogIdentity
import java.nio.file.Files
import java.nio.file.Path

/** Authored identity fixtures, not claims about unknown live provider pairs. */
class SearchResultPresentationFilterTest {
    private fun c(id:String, title:String="Shared original", authority:String?="1", count:Int?=null,
        kind:SearchWorkKind=SearchWorkKind.COMIC) = SearchWorkCandidate(id,"source-$id",kind,title,
        externalIds=authority?.let {mapOf("anilist" to it)}.orEmpty(),
        chapters=SearchChapterEvidence(count,count!=null))
    private fun cards(rows:List<SearchWorkCandidate>)=SearchResultPresentationFilter().present(0,rows)!!
    @Test fun hundredVerifiedEditionsProduceOneCardAndKeepEveryAlternative() {
        val rows=(1..100).map { c("edition-$it", count=it) }
        val group=cards(rows).single()
        group.members.size shouldBe 100;group.winner.id shouldBe "edition-100"
    }
    @Test fun identicalTitlesWithoutAuthorityRemainSeparate(){cards(listOf(c("a",authority=null),c("b",authority=null))).size shouldBe 2}
    @Test fun verifiedArabicVariantsGroupWithoutRewritingTitles(){
        val rows=listOf(c("a",title="أَمِير الـفتيان الأعلى"),c("b",title="امير الفتيان الاعلي"))
        cards(rows).single().members.map{it.title} shouldBe rows.map{it.title}
    }
    @Test fun verifiedArabicEnglishAliasesGroup(){cards(listOf(c("a",title="العمل الأصلي"),c("b",title="Original Work"))).size shouldBe 1}
    @Test fun sequelHasItsOwnVerifiedIdentity(){cards(listOf(c("a",authority="1"),c("b",title="Shared original II",authority="2"))).size shouldBe 2}
    @Test fun remakeAndSideStoryKeepSeparateIdentities(){cards(listOf(c("a"),c("b",title="Remake",authority="2"),c("c",title="Side story",authority="3"))).size shouldBe 3}
    @Test fun comicAndNovelSessionsNeverShareSelection(){
        val comic=cards(listOf(c("a"))).single()
        val novel=cards(listOf(c("b",kind=SearchWorkKind.NOVEL))).single()
        (comic.key!=novel.key) shouldBe true
    }
    @Test fun contradictoryAuthoritiesCannotMerge(){
        val a=c("a").copy(externalIds=mapOf("anilist" to "1","mangadex" to "x"))
        val b=c("b").copy(externalIds=mapOf("anilist" to "1","mangadex" to "y"))
        cards(listOf(a,b)).size shouldBe 2
    }
    @Test fun aliasBridgeCannotMergeWithoutPairwiseVerifiedRelationship(){
        val a=c("a");val b=c("b").copy(externalIds=mapOf("anilist" to "1","mangadex" to "x"))
        val d=c("c").copy(externalIds=mapOf("mangadex" to "x"))
        cards(listOf(a,b,d)).map {it.members.size}.sorted() shouldBe listOf(1,2)
    }
    @Test fun incrementalCompletionAndPaginationKeepTheSameWorkKey(){
        val f=SearchResultPresentationFilter();val first=f.present(0,listOf(c("a")))!!.single().key
        f.present(0,listOf(c("a"),c("b"),c("b")))!!.single().let {it.key shouldBe first;it.members.size shouldBe 2}
    }
    @Test fun staleQueryEpochCannotPublishIntoARevisitedQuery(){
        val f=SearchResultPresentationFilter(3)
        f.present(1,listOf(c("old"))) shouldBe null
        f.present(2,listOf(c("other"))) shouldBe null
        f.present(3,listOf(c("current")))!!.single().winner.id shouldBe "current"
        f.present(1,listOf(c("late"))) shouldBe null
        f.snapshot().single().winner.id shouldBe "current"
    }
    @Test fun strongerMetadataOnALaterPageDoesNotChurnAnExistingValidKey(){
        val a=c("a").copy(externalIds=mapOf("mangadex" to "x"))
        val b=c("b").copy(externalIds=mapOf("mangadex" to "x","anilist" to "1"))
        val f=SearchResultPresentationFilter();val first=f.present(0,listOf(a))!!.single().key
        f.present(0,listOf(a,b))!!.single().key shouldBe first
    }
    @Test fun cacheRestorationPreservesStableKey(){
        val f=SearchResultPresentationFilter();val rows=listOf(c("a"),c("b"));val old=f.present(0,rows)!!
        val restored=SearchResultPresentationFilter(7);restored.restore(old)
        restored.present(7,rows)!!.single().key shouldBe old.single().key
    }
    @Test fun enrichmentReplacesUnverifiedCardsWithVerifiedGroup(){
        val f=SearchResultPresentationFilter()
        f.present(0,listOf(c("a",authority=null),c("b",authority=null)))!!.size shouldBe 2
        f.present(0,listOf(c("a"),c("b")))!!.size shouldBe 1
    }
    @Test fun repeatedResponsesDoNotDuplicateCards(){cards(List(100){c("a")}).single().members.size shouldBe 1}
    @Test fun failingAlternativeDoesNotHideReadableMember(){
        val bad=c("a",count=999).copy(chapters=SearchChapterEvidence(999,true,readable=false))
        val good=c("b",count=85).copy(chapters=SearchChapterEvidence(85,true,readable=true))
        cards(listOf(bad,good)).single().let{it.winner.id shouldBe "b";it.members.size shouldBe 2}
    }
    @Test fun uniqueWorksNeverDisappear(){cards((1..150).map{c("$it",authority="$it")}).size shouldBe 150}
    @Test fun actualCountBeatsHighestChapterLabel(){
        // Chapter labels are intentionally not inputs to either candidate.
        cards(listOf(c("chapter33333-only10",count=10),c("850-real-entries",count=850))).single().winner.id shouldBe "850-real-entries"
    }
    @Test fun unknownCountIsNeverAdvertisedAsVerified(){cards(listOf(c("a"))).single().winner.chapters.label() shouldBe null}
    @Test fun incompleteCountIsClearlyALowerBound(){
        cards(listOf(c("a").copy(chapters=SearchChapterEvidence(50,false)))).single().winner.chapters.label() shouldBe "50 فصل مفهرس على الأقل"
    }
    @Test fun knownReaderAccessWinsOverUnknownLargerIndex(){
        val good=c("a",count=80).copy(chapters=SearchChapterEvidence(80,true,readable=true))
        cards(listOf(good,c("b",count=800))).single().winner.id shouldBe "a"
    }
    @Test fun returnedWinnerRetainsItsRealSourceAndEditionId(){
        val rows=listOf(c("a",count=10),c("b",count=90))
        cards(rows).single().winner shouldBe rows[1]
    }
    @Test fun mappingRevocationSplitsSafely(){
        val f=SearchResultPresentationFilter();f.present(0,listOf(c("a"),c("b")))!!.size shouldBe 1
        f.present(0,listOf(c("a",authority=null),c("b",authority=null)))!!.size shouldBe 2
    }
    @Test fun novelOnlyUsesExistingCatalogRelationships(){
        val rows=(1..100).map { c("novel-$it",authority=null,count=it,kind=SearchWorkKind.NOVEL) }
        val f=SearchResultPresentationFilter()
        f.present(0,rows)!!.size shouldBe 100
        f.present(0,rows,mapOf("existing-canonical-work" to rows))!!.single().let {
            it.key shouldBe "existing-canonical-work";it.members.size shouldBe 100;it.winner.id shouldBe "novel-100"
        }
    }
    @Test fun contradictoryNovelEvidenceVetoesAnOldCatalogGroup(){
        val rows=listOf(c("a",authority="1",kind=SearchWorkKind.NOVEL),c("b",authority="2",kind=SearchWorkKind.NOVEL))
        SearchResultPresentationFilter().present(0,rows,mapOf("old-group" to rows))!!.size shouldBe 2
    }
    @Test fun suppliedEditionModelsRemainUnchanged(){
        val rows=listOf(c("a",count=10),c("b",count=20));val before=rows.toList()
        cards(rows);rows shouldBe before
    }
    @Test fun productionSearchScreensConsumeTheSelectedGroupsAndKeepChooser(){
        fun source(path:String):String {
            val roots=listOf(Path.of("."),Path.of("app"))
            return roots.map{it.resolve("src/main/java/eu/kanade/$path")}.first{Files.exists(it)}.let(Files::readString)
        }
        val model=source("tachiyomi/ui/browse/source/globalsearch/SearchViewModel.kt")
        model.contains("selector.present(") shouldBe true
        val manga=source("presentation/home/MangaroSearchScreen.kt")
        manga.contains("val results = state.rankedResults") shouldBe true
        manga.contains("onLongClick = if (initial.alternatives.isNotEmpty())") shouldBe true
        val novel=source("presentation/novels/NovelScreens.kt")
        novel.contains("selection.presentation(searchEditions.values.toList(), matched)") shouldBe true
        novel.contains("if (search != null) search.works") shouldBe true
        novel.contains("items(works, key = { it.id })") shouldBe true
        novel.contains("val group = searchGroupsByKey[work.id]") shouldBe true
        val primaryManga=manga.substringAfter("private fun SearchMangaCard")
        primaryManga.contains("source.name") shouldBe false
        primaryManga.contains("alternatives.size") shouldBe false
        novel.substringAfter("private fun NovelCard").substringBefore("@Composable").contains("source.name") shouldBe false
    }
    @Test fun repeatedSparseAndDenseWorkloadsStayDeterministic(){
        for(n in listOf(100,1000,5000)) {
            val rows=(1..n).map {c("row-$it",authority="$it")}
            val f=SearchResultPresentationFilter();f.present(0,rows+rows)!!.size shouldBe n
            f.present(0,rows)!!.map{it.key}.toSet().size shouldBe n
        }
    }
}
