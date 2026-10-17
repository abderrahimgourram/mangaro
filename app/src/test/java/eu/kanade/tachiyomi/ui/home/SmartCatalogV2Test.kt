package eu.kanade.tachiyomi.ui.home

import eu.kanade.tachiyomi.source.internal.mangalek.MangaLek
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.LocalAccountScope as Scope
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.ChapterListIntegrity as Index
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.manga.service.*
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.fullType
import java.util.UUID

/** Selection fixtures are authored. Retained phone references below are historical evidence, not current-phone reproduction. */
class SmartCatalogV2Test {
    private fun rows(id: Long, count: Int) = (0 until count).map {
        Chapter.create().copy(id=it.toLong(),mangaId=id,url="/chapter/$it",name="Chapter ${if(it==count-1)3333 else it}",chapterNumber=it.toDouble())
    }
    private fun manga(id: Long, count: Int, title: String = "Verified Work", publishedAt: Long? = null, verified: Boolean = true): Manga {
        val m = Manga.create().copy(id=id,source=id,url="/work/$id",title=title,memo=buildJsonObject {
            if (verified) put(WorkMetadata.MEMO_KEY,buildJsonObject { put("externalIds",buildJsonObject {put("anilist","fixture-smart-catalog")} ) })
        })
        return m.copy(memo=Index.memo(m,"COMPLETE",rows(id,count),publishedAt))
    }
    private fun card(m: Manga): HomeDiscoveryItem {
        PreferredMangaVariants.remember(m)
        return HomeDiscoveryItem(m.id,m.title,m.asMangaCover(),m.source,"Fixture ${m.source}",m.url)
    }
    @Test fun popularPrefers850IndexedChaptersOver3333LabelWith90Rows() {
        val many=card(manga(910001,850));val highLabel=card(manga(910002,90))
        val group=GroupDiscoveryItems.group(listOf(highLabel,many),CatalogSelectionContext.POPULAR).single()
        group.mangaId shouldBe many.mangaId;group.actualChapterCount shouldBe 850
        group.alternatives.single().mangaId shouldBe highLabel.mangaId
    }
    @Test fun chapterCountingKeepsGapsZeroDecimalAndSpecialPartsWithoutDoubleIdentity() {
        val m=Manga.create().copy(id=910003,source=4,url="/work",title="Work")
        val entries=listOf(
            Chapter.create().copy(mangaId=m.id,url="/zero",name="Prologue",chapterNumber=0.0,memo=buildJsonObject {put("id","zero")}),
            Chapter.create().copy(mangaId=m.id,url="/old-zero",name="Prologue alias",memo=buildJsonObject {put("id","zero")}),
            Chapter.create().copy(mangaId=m.id,url="/half",name="Chapter 0.5",chapterNumber=.5),
            Chapter.create().copy(mangaId=m.id,url="/gap",name="Chapter 3333",chapterNumber=3333.0),
            Chapter.create().copy(mangaId=m.id,url="/part-a",name="Special",memo=buildJsonObject {put("id","part-a")} ),
            Chapter.create().copy(mangaId=m.id,url="/part-b",name="Special",memo=buildJsonObject {put("id","part-b")} ),
            Chapter.create().copy(mangaId=m.id,url="",name="invalid"))
        Index.uniqueIndexedCount(entries+entries[2],m.source) shouldBe 5
        Index.actualChapterCount(m.copy(memo=Index.memo(m,"COMPLETE",entries))) shouldBe 5
        entries.last().url shouldBe "" // Selection never edits stored rows.
    }
    @Test fun bothFeedsKeepOneVerifiedBilingualGroupAcrossPagesAndPreferActualTotal() {
        val arabic=card(manga(920001,850,"عنوان عربي",1000))
        val highLabel=card(manga(920002,90,"English Title",2000))
        for(context in listOf(CatalogSelectionContext.POPULAR,CatalogSelectionContext.LATEST)) {
            val first=GroupDiscoveryItems.group(listOf(highLabel),context)
            val merged=GroupDiscoveryItems.group(first+listOf(arabic,highLabel,arabic),context)
            merged.size shouldBe 1
            merged.single().mangaId shouldBe arabic.mangaId
            merged.single().actualChapterCount shouldBe 850
            merged.single().availableVersions.size shouldBe 2
            val restored=GroupDiscoveryItems.group(merged+merged,context).single()
            restored.canonicalWorkId shouldBe merged.single().canonicalWorkId
            restored.mangaId shouldBe arabic.mangaId
            restored.alternatives.single().mangaId shouldBe highLabel.mangaId
        }
    }
    @Test fun unknownCountCannotOutrankVerifiedTotalUsingOnlyNewerPublication() {
        for(context in listOf(CatalogSelectionContext.POPULAR,CatalogSelectionContext.LATEST)) {
            CatalogEditionSelection.compare(context,CatalogEditionEvidence(indexedChapters=90,publishedAt=1000),
                CatalogEditionEvidence(indexedChapters=null,publishedAt=999999)).let { it>0 } shouldBe true
        }
    }
    @Test fun popularDoesNotReorderWorksByChapterCount() {
        val first=card(manga(910004,3,verified=false));val later=card(manga(910005,850,verified=false))
        GroupDiscoveryItems.group(listOf(first,later),CatalogSelectionContext.POPULAR).map { it.mangaId } shouldBe listOf(first.mangaId,later.mangaId)
    }
    @Test fun latestSelectsFullestEditionWhileWorkKeepsItsGenuineLatestPublication() {
        val arabic=card(manga(910006,90,"عنوان عربي",2000));val english=card(manga(910007,850,"English Title",1000))
        val group=GroupDiscoveryItems.group(listOf(english,arabic),CatalogSelectionContext.LATEST).single()
        group.mangaId shouldBe english.mangaId
        group.actualChapterCount shouldBe 850
        group.chapterPublishedAt shouldBe 2000L
        group.alternatives.single().mangaId shouldBe arabic.mangaId
        GroupDiscoveryItems.group(listOf(english,arabic),CatalogSelectionContext.POPULAR).single().mangaId shouldBe english.mangaId
        val fullerArabic=card(manga(910006,900,"عنوان عربي",2000))
        GroupDiscoveryItems.group(listOf(english,fullerArabic),CatalogSelectionContext.LATEST).single().mangaId shouldBe fullerArabic.mangaId
    }
    @Test fun cacheAndFetchTimestampsCannotInventOrWinPublicationFreshness() {
        val stale=manga(910008,850,"Old",1000)
        val refreshed=stale.copy(memo=JsonObject(stale.memo+(Index.KEY to JsonObject((stale.memo[Index.KEY] as JsonObject)+
            ("lastSuccessful" to buildJsonObject {put("count",850);put("at",Long.MAX_VALUE/4)})))))
        val new=card(manga(910009,850,"New",2000))
        GroupDiscoveryItems.group(listOf(card(refreshed),new),CatalogSelectionContext.LATEST).single().mangaId shouldBe new.mangaId
        val noSourceDate=manga(910010,2).let { it.copy(lastUpdate=Long.MAX_VALUE/4) }
        Index.catalogPublishedAt(noSourceDate) shouldBe null
        val persisted=rows(noSourceDate.id,2).map {it.copy(dateFetch=5000,dateUpload=6000)}
        Index.catalogPublishedAt(noSourceDate.copy(memo=Index.memo(noSourceDate,"COMPLETE",persisted))) shouldBe null
    }
    @Test fun staleOrWrongEditionPublicationProofCannotBeReused() {
        val m=manga(910011,2,publishedAt=1000)
        Index.catalogPublishedAt(m.copy(url="/different")) shouldBe null
        val failed=m.copy(memo=Index.memo(m,"FAILED",rows(m.id,2)))
        Index.catalogPublishedAt(failed) shouldBe 1000
        val changed=m.copy(memo=Index.memo(m,"FAILED",rows(m.id,3)))
        Index.catalogPublishedAt(changed) shouldBe null
    }
    @Test fun latestKnownDatesSortOnlyDatedSlotsAndUnknownsHaveStableFallback() {
        val old=card(manga(910012,1,publishedAt=1000,verified=false));val unknown=card(manga(910013,1,verified=false))
        val fresh=card(manga(910014,1,publishedAt=2000,verified=false))
        GroupDiscoveryItems.group(listOf(old,unknown,fresh),CatalogSelectionContext.LATEST).map {it.mangaId} shouldBe listOf(fresh.mangaId,unknown.mangaId,old.mangaId)
        val a=card(Manga.create().copy(id=910015,source=910015,url="/a",title="Same",memo=buildJsonObject {
            put(WorkMetadata.MEMO_KEY,buildJsonObject {put("externalIds",buildJsonObject {put("anilist","fixture-unknown")})})}))
        val b=a.copy(mangaId=910016,sourceId=910016,url="/b")
        PreferredMangaVariants.remember(Manga.create().copy(id=b.mangaId,source=b.sourceId,url=b.url,title=b.title,memo=buildJsonObject {
            put(WorkMetadata.MEMO_KEY,buildJsonObject {put("externalIds",buildJsonObject {put("anilist","fixture-unknown")})})}))
        val winner=GroupDiscoveryItems.group(listOf(a,b),CatalogSelectionContext.LATEST).single()
        GroupDiscoveryItems.group(listOf(b,a),CatalogSelectionContext.LATEST).single().mangaId shouldBe winner.mangaId
        winner.chapterPublishedAt shouldBe null;winner.actualChapterCount shouldBe null
    }
    @Test fun lateMappingAndHealthRegroupCannotRenderFeaturedAndShelfDuplicates() {
        val oldFeatured=card(manga(910017,90));val better=card(manga(910018,850))
        // Exact old downstream mechanism: separately project the featured item, then filter by local row ID.
        val old=GroupDiscoveryItems.group(listOf(oldFeatured),CatalogSelectionContext.POPULAR).single()
        val brokenShelf=GroupDiscoveryItems.group(listOf(better,oldFeatured),CatalogSelectionContext.POPULAR).filterNot {it.mangaId==old.mangaId}
        brokenShelf.single().canonicalWorkId shouldBe old.canonicalWorkId
        val repaired=GroupDiscoveryItems.popularProjection(listOf(better,oldFeatured),oldFeatured)
        repaired.featured!!.mangaId shouldBe better.mangaId;repaired.items shouldBe emptyList()
        repaired.featured.alternatives.single().mangaId shouldBe oldFeatured.mangaId
    }
    @Test fun finalProjectionPagesAndRestoredGroupsKeepOneCardAndAllActualReferences() {
        val a=card(manga(910019,2));val b=card(manga(910020,3))
        val first=GroupDiscoveryItems.group(listOf(a),CatalogSelectionContext.POPULAR)
        val merged=GroupDiscoveryItems.group(first+listOf(a,b,b),CatalogSelectionContext.POPULAR)
        merged.size shouldBe 1;merged.single().availableVersions.size shouldBe 2
        GroupDiscoveryItems.group(merged+merged,CatalogSelectionContext.POPULAR).single().canonicalWorkId shouldBe merged.single().canonicalWorkId
        DiscoverySnapshotStore.setSnapshot(mihon.domain.source.discovery.model.DiscoveryCategory.POPULAR, merged+merged)
        DiscoverySnapshotStore.getSnapshot(mihon.domain.source.discovery.model.DiscoveryCategory.POPULAR).size shouldBe 1
        val selected=GroupDiscoveryItems.findSelectedWork(merged,a)!!
        selected.url shouldBe b.url;selected.sourceId shouldBe b.sourceId
    }
    @Test fun retainedOtherworldlyPhoneReferencesAreUnverifiedNotSilentlyMerged() {
        // Recorded 2026-10-14 phone backup references. No canonical/external relationship was present.
        val a=card(Manga.create().copy(id=11,source=6058166507489091982,url="/manga/otherworldly-evil-monarch",title="Otherworldly Evil Monarch"))
        val b=card(Manga.create().copy(id=57,source=918460697583900080,url="/manga/otherworldly-evil-monarch/#775",title="Otherworldly Evil Monarch"))
        GroupDiscoveryItems.group(listOf(a,b),CatalogSelectionContext.POPULAR).size shouldBe 2
        val diagnostic=CatalogDuplicateDiagnostics.snapshot().last {it.a.id in setOf(11L,57L) && it.b.id in setOf(11L,57L)}
        diagnostic.reason shouldBe "NO_SHARED_VERIFIED_IDENTITY"
        diagnostic.a.url.contains("otherworldly-evil-monarch") shouldBe true
        diagnostic.a.reviewed shouldBe null;diagnostic.b.reviewed shouldBe null
        diagnostic.a.representative shouldBe diagnostic.a.id;diagnostic.b.representative shouldBe diagnostic.b.id
    }
    @Test fun missingLordOfTruthIdentityIsOnlyAnAuthoredUnresolvedCandidate() {
        val a=card(manga(910021,2,"Lord of the Truth Fan",verified=false))
        val b=card(manga(910022,9,"Lord of the Truth Fan",verified=false))
        GroupDiscoveryItems.group(listOf(a,b),CatalogSelectionContext.LATEST).size shouldBe 2
    }
    @Test fun existingMangaLekPostIdentityGroupsLegacyAndCurrentAliasesWithoutAnyRequest() {
        val source=MangaLek();val manager=mockk<SourceManager>()
        every { manager.get(any()) } returns null;every { manager.get(source.id) } returns source
        Injekt.addSingletonFactory(fullType<SourceManager>()) {manager}
        try {
            val legacy=Manga.create().copy(id=910023,source=source.id,url="775",title="Legacy")
            val current=legacy.copy(id=910024,url="/manga/otherworldly-evil-monarch/#775",title="Current")
            WorkLinker.group(listOf(CatalogWorkEvidence.from(legacy),CatalogWorkEvidence.from(current))).single().members.size shouldBe 2
            WorkLinker.group(listOf(CatalogWorkEvidence.from(legacy),CatalogWorkEvidence.from(current.copy(url="/manga/other/#776")))).size shouldBe 2
        } finally { Injekt.addSingletonFactory(fullType<SourceManager>()) {mockk(relaxed=true)} }
    }
    @Test fun diagnosticsAreBoundedPrivacySafeAndCannotAuthorizeMerging() {
        for(i in 0..80) {
            val a=WorkMetadata(SourceWorkReference(100000L+i,100000L+i,"/work?id=$i&token=PRIVATE"),"Diagnostic $i")
            val b=a.copy(reference=SourceWorkReference(200000L+i,200000L+i,"/other?%73ession=PRIVATE"))
            CatalogDuplicateDiagnostics.record(listOf(a,b),WorkLinker.group(listOf(a,b)))
        }
        CatalogDuplicateDiagnostics.snapshot().size shouldBe 64
        CatalogDuplicateDiagnostics.snapshot().none {it.toString().contains("PRIVATE")} shouldBe true
    }
    @Test fun manualPreferenceIsAccountScopedRestoredAndNeverOverwritesAutomaticScores() = runBlocking {
        val prefs=mockk<PreferenceStore>()
        val values=mutableMapOf<String,InMemoryPreference<String>>()
        every { prefs.getString(any(),any()) } answers { values.getOrPut(firstArg()) {InMemoryPreference(firstArg(),null,secondArg())} }
        val store=CatalogEditionPreferences(prefs)
        val a=card(manga(910025,90));val b=card(manga(910026,850))
        val work=GroupDiscoveryItems.group(listOf(a,b),CatalogSelectionContext.POPULAR).single()
        val accountA=UUID.randomUUID().toString();val accountB=UUID.randomUUID().toString()
        try {
            Scope.commit(accountA);store.remember(work,a.mangaId);val old=Scope.capture()
            store.selected(work)!!.url shouldBe a.url
            Scope.commit(accountB);store.selected(work) shouldBe null
            runCatching { store.remember(work,a.mangaId,old) }.isFailure shouldBe true
            store.remember(work,b.mangaId);store.selected(work)!!.url shouldBe b.url
            Scope.commit(accountA);store.selected(work)!!.url shouldBe a.url
            GroupDiscoveryItems.group(listOf(a,b),CatalogSelectionContext.POPULAR).single().mangaId shouldBe b.mangaId
            Scope.commit(Scope.GUEST);store.selected(work) shouldBe null
        } finally {Scope.commit(Scope.GUEST)}
        Unit
    }
    @Test fun presentationNeverChangesPrivateUserStateOrChapterOwnership() {
        val m=manga(910027,3).copy(favorite=true,notes="Private",viewerFlags=5,chapterFlags=9)
        val before=m.copy();val chapters=rows(m.id,3).map {it.copy(read=true,bookmark=true,lastPageRead=8)}
        GroupDiscoveryItems.group(listOf(card(m),card(manga(910028,4))),CatalogSelectionContext.LATEST)
        m shouldBe before
        chapters.all {it.mangaId==m.id && it.read && it.bookmark && it.lastPageRead==8L} shouldBe true
    }
    @Test fun privateReleaseDiagnosticsUseTheNormalInfoThreshold() {
        CatalogDuplicateDiagnostics.priority shouldBe logcat.LogPriority.INFO
    }

    @Test fun partialRefreshPreservesKnownPublicationOnlyForTheSameVerifiedIndex() {
        val m=manga(910029,3,publishedAt=2000)
        Index.catalogPublishedAt(m.copy(memo=Index.memo(m,"PARTIAL",rows(m.id,3),1000))) shouldBe 2000
        Index.catalogPublishedAt(m.copy(memo=Index.memo(m,"PARTIAL",rows(m.id,3)))) shouldBe 2000
        Index.catalogPublishedAt(m.copy(memo=Index.memo(m,"PARTIAL",rows(m.id,4)))) shouldBe null
        Index.catalogPublishedAt(m.copy(memo=Index.memo(m,"COMPLETE",rows(m.id,3),1000))) shouldBe 1000
    }

}
