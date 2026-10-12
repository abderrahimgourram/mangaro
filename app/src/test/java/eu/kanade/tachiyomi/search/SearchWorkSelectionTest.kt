package eu.kanade.tachiyomi.search

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SearchWorkSelectionTest {
    private fun c(id:String,source:String=id,title:String="Solo Leveling",count:Int?=null,
        complete:Boolean=count!=null,readable:Boolean?=null,kind:SearchWorkKind=SearchWorkKind.COMIC,
        author:String?=null,aliases:Set<String> = emptySet(),priority:Int=0) = SearchWorkCandidate(id,source,kind,title,
        aliases,author=author,priority=priority,chapters=SearchChapterEvidence(count,complete,readable,10))
    @Test fun sameMangaAcrossSourcesProducesOneCard(){SearchWorkSelection().select(listOf(c("a"),c("b"),c("c"))).size shouldBe 1}
    @Test fun sameNovelAcrossSourcesProducesOneCard(){SearchWorkSelection().select(listOf(c("a",kind=SearchWorkKind.NOVEL),c("b",kind=SearchWorkKind.NOVEL))).size shouldBe 1}
    @Test fun arabicEnglishRequiresAnActualAlias(){
        val a=c("a",title="سولو ليفلينج",aliases=setOf("Solo Leveling"))
        SearchWorkIdentity.matches(a,c("b")) shouldBe true
        SearchWorkIdentity.matches(a.copy(aliases=emptySet()),c("b")) shouldBe false
    }
    @Test fun arabicDiacriticsAlefAndMaqsuraAreComparisonOnly(){
        val a=c("a",title="أَمِير الـفتيان الأعلى")
        SearchWorkIdentity.matches(a,c("b",title="امير الفتيان الاعلي")) shouldBe true
        a.title shouldBe "أَمِير الـفتيان الأعلى"
        SearchWorkIdentity.matches(c("a",title="حياة الفتاة"),c("b",title="حياه الفتاه")) shouldBe false
    }
    @Test fun realCountWinsOverArtificialNumberAndPaginationOverlap(){
        // Source A's 84 actual entries can display chapter 3333; the ordinal is not a counting input.
        val rows=(1..84).map{DistinctSearchChapters.Identity("source-a/chapter/${if(it==84)3333 else it}")}
        val a=c("a",count=DistinctSearchChapters.count(rows+rows.take(12)))
        val groups=SearchWorkSelection().select(listOf(a,c("b",count=205),c("c",count=199)))
        groups.single().winner.id shouldBe "b"
        groups.single().winner.chapters.distinct shouldBe 205
    }
    @Test fun duplicateRoutesAndProvenRemoteAliasesCountOnce(){
        DistinctSearchChapters.count(listOf(DistinctSearchChapters.Identity("/old",setOf("remote-7")),
            DistinctSearchChapters.Identity("/new",setOf("remote-7")),DistinctSearchChapters.Identity("/new"),
            DistinctSearchChapters.Identity("/different",setOf("remote-8")))) shouldBe 2
    }
    @Test fun sameNumbersWithDifferentActualIdsStayDistinct(){
        DistinctSearchChapters.count(listOf(DistinctSearchChapters.Identity("/part-a"),DistinctSearchChapters.Identity("/part-b"))) shouldBe 2
    }
    @Test fun equalCountsPreferReadingThenStablePriority(){
        val session=SearchWorkSelection()
        session.select(listOf(c("a",count=200,priority=0),c("b",count=200,readable=true,priority=2))).single().winner.id shouldBe "b"
        SearchWorkSelection().select(listOf(c("b",count=200,priority=0),c("a",count=200,priority=0))).single().winner.id shouldBe "a"
    }
    @Test fun partialCountsAreLowerBoundsNotFinalRanking(){
        val group=SearchWorkSelection().select(listOf(c("a",count=84),c("b",count=400,complete=false))).single()
        group.winner.id shouldBe "b";group.provisional shouldBe true
        c("b",count=400,complete=false).chapters.label() shouldBe "400 فصل مفهرس على الأقل"
    }
    @Test fun unknownSelectionStaysStableUntilCompleteEvidenceArrives(){
        val s=SearchWorkSelection();val a=c("a",priority=3);val b=c("b",priority=0)
        val first=s.select(listOf(a)).single()
        val provisional=s.select(listOf(a,b)).single()
        provisional.winner.id shouldBe "a";provisional.key shouldBe first.key
        val final=s.select(listOf(a.copy(chapters=SearchChapterEvidence(84,true)),b.copy(chapters=SearchChapterEvidence(205,true)))).single()
        final.winner.id shouldBe "b";final.key shouldBe first.key;final.provisional shouldBe false
    }
    @Test fun similarTitlesAndCreatorConflictsDoNotMerge(){
        SearchWorkIdentity.matches(c("a"),c("b",title="Solo Swordmaster")) shouldBe false
        SearchWorkIdentity.matches(c("a",author="Author One"),c("b",author="Author Two")) shouldBe false
        SearchWorkIdentity.matches(c("a",title="The Dark King"),c("b",title="The Dark King")) shouldBe false
    }
    @Test fun sequelsRemakesAndEditionsRemainSeparate(){
        listOf("Solo Leveling 2","Solo Leveling Ragnarok","Solo Leveling (Remake)","Solo Leveling WN").forEach {
            SearchWorkIdentity.matches(c("a"),c("b",title=it)) shouldBe false
        }
        SearchWorkIdentity.matches(c("a").copy(edition="First edition"),c("b").copy(edition="Revised edition")) shouldBe false
    }
    @Test fun adaptationsNeverMergeAcrossMedia(){SearchWorkIdentity.matches(c("a"),c("b",kind=SearchWorkKind.NOVEL)) shouldBe false}
    @Test fun shortCreatorNamesAndFormatCodesAreStillContradictions(){
        SearchWorkIdentity.matches(c("a",author="Li"),c("b",author="Wu")) shouldBe false
        SearchWorkIdentity.matches(c("a").copy(format="LN"),c("b").copy(format="WN")) shouldBe false
        SearchWorkIdentity.matches(c("a").copy(edition="1"),c("b").copy(edition="2")) shouldBe false
    }
    @Test fun franchiseAliasCannotEraseASequelSubtitle(){
        SearchWorkIdentity.matches(c("a"),c("b",title="Solo Leveling Ragnarok",aliases=setOf("Solo Leveling"))) shouldBe false
    }
    @Test fun partialIndexesDoNotSwitchWinnerOnEveryNewPage(){
        val s=SearchWorkSelection();val a=c("a",count=400,complete=false);val b=c("b",count=390,complete=false)
        s.select(listOf(a,b)).single().winner.id shouldBe "a"
        s.select(listOf(a,b.copy(chapters=SearchChapterEvidence(450,false)))).single().winner.id shouldBe "a"
        s.select(listOf(a.copy(chapters=SearchChapterEvidence(400,true)),b.copy(chapters=SearchChapterEvidence(450,true)))).single().winner.id shouldBe "b"
    }
    @Test fun restoringWarmSearchKeepsItsCardKey(){
        val s=SearchWorkSelection();val initial=s.select(listOf(c("z",priority=9)))
        val restored=SearchWorkSelection();restored.restore(initial)
        restored.select(listOf(c("a",priority=0),c("z",priority=9))).single().key shouldBe initial.single().key
    }
    @Test fun aliasesCannotCreateTransitiveClusters(){
        val a=c("a",title="Alpha Original",aliases=setOf("Shared Alpha"))
        val b=c("b",title="Shared Alpha",aliases=setOf("Shared Beta"))
        val c=c("c",title="Shared Beta")
        val groups=SearchWorkSelection().select(listOf(a,b,c));groups.size shouldBe 2
        groups.forEach{g->g.members.forEach{x->g.members.forEach{y->if(x.id!=y.id)SearchWorkIdentity.matches(x,y) shouldBe true}}}
    }
    @Test fun repeatedSearchPagesKeepOneStableCard(){
        val s=SearchWorkSelection();val a=c("a",count=84);val initial=s.select(listOf(a)).single()
        val updated=s.select(listOf(a,a,c("b",count=205))).single()
        updated.key shouldBe initial.key;updated.members.size shouldBe 2;updated.winner.id shouldBe "b"
    }
    @Test fun knownUnreadableHighestCountUsesVerifiedFallback(){
        SearchWorkSelection().select(listOf(c("a",count=500,readable=false),c("b",count=205,readable=true))).single().winner.id shouldBe "b"
    }
    @Test fun newMetadataCanSplitAnOldGroupWithoutDuplicateUiKeys(){
        val s=SearchWorkSelection();s.select(listOf(c("a"),c("b")))
        val split=s.select(listOf(c("a",author="One Author"),c("b",author="Other Author")))
        split.size shouldBe 2;split.map{it.key}.distinct().size shouldBe 2
    }
    @Test fun lateHigherPriorityVariantCanSplitWithoutStealingAnotherCardKey(){
        val s=SearchWorkSelection();val a=c("a",priority=9);val b=c("b",priority=0)
        s.select(listOf(a));s.select(listOf(a,b))
        val split=s.select(listOf(a.copy(author="One Author"),b.copy(author="Other Author")))
        split.size shouldBe 2;split.map{it.key}.distinct().size shouldBe 2
    }
    @Test fun trustedIdsWinAndConflictingIdsReject(){
        val a=c("a").copy(externalIds=mapOf("anilist" to "123"))
        SearchWorkIdentity.matches(a,c("b",title="اسم موثّق آخر").copy(externalIds=mapOf("anilist" to "123"))) shouldBe true
        SearchWorkIdentity.matches(a,c("b").copy(externalIds=mapOf("anilist" to "321"))) shouldBe false
        SearchWorkIdentity.matches(c("a",title="Alpha" ).copy(externalIds=mapOf("sourceLocalId" to "1")),c("b",title="Beta").copy(externalIds=mapOf("sourceLocalId" to "1"))) shouldBe false
    }
}
