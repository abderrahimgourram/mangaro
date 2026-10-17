package eu.kanade.tachiyomi.ui.home

import eu.kanade.tachiyomi.search.*
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.LocalAccountScope as Scope
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.ChapterListIntegrity
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.manga.service.*
import java.io.File
import java.util.UUID

/** Exact source pair is maintainer-confirmed. Counts/lifecycle data below are authored fixtures. */
class ReviewedCatalogRegistryTest {
    private val aSource=6058166507489091982L
    private val bSource=918460697583900080L
    private val aRoute="/manga/otherworldly-evil-monarch"
    private val bRoute="/manga/otherworldly-evil-monarch/#775"
    private fun bundle(): String {
        val root=generateSequence(File(System.getProperty("user.dir"))) {it.parentFile}
            .first {File(it,"app/src/main").isDirectory}
        return File(root,"app/src/main/res/raw/catalog_reviewed_links.json").readText()
    }
    private fun record()=Json.parseToJsonElement(bundle()).jsonObject["records"]!!.jsonArray.first().jsonObject
    private fun work(source: Long,route: String)=WorkMetadata(SourceWorkReference(source,1,route),"Title")
    private fun card(source: Long,id: Long,route: String,title: String,count: Int): HomeDiscoveryItem {
        val m=Manga.create().copy(id=id,source=source,url=route,title=title,favorite=true,notes="private")
        val rows=(0 until count).map {Chapter.create().copy(id=it.toLong(),mangaId=id,url="/c/$it",name="Chapter $it",read=true,bookmark=true,lastPageRead=8)}
        val indexed=m.copy(memo=ChapterListIntegrity.memo(m,"COMPLETE",rows))
        PreferredMangaVariants.remember(indexed)
        return HomeDiscoveryItem(id,title,indexed.asMangaCover(),source,"Source $source",route)
    }
    @AfterEach fun clearRegistry() { CatalogWorkEvidence.replaceReviewedRegistry("""{"schema":1,"records":[]}""") }

    @Test fun confirmedPairResolvesOfflineWithoutTitleOrLocalDatabaseIdMatching() {
        val registry=ReviewedCatalogRegistry.parse(bundle())
        val a=work(aSource,aRoute).copy(title="عنوان عربي")
        val b=work(bSource,bRoute).copy(title="Completely different translation title",reference=SourceWorkReference(bSource,99,bRoute))
        registry.resolve(a) shouldBe registry.resolve(b)
        (registry.resolve(a)!=null) shouldBe true
        registry.resolve(work(999,aRoute)) shouldBe null
        registry.resolve(work(aSource,"/manga/otherworldly-evil-monarch-part-2")) shouldBe null
    }
    @Test fun popularLatestAndSearchAgreeAndKeepEveryEditionAcrossPagination() {
        CatalogWorkEvidence.replaceReviewedRegistry(bundle())
        val a=card(aSource,780025,aRoute,"عنوان عربي",850)
        val b=card(bSource,780047,bRoute,"Otherworldly Evil Monarch",90)
        for(context in listOf(CatalogSelectionContext.POPULAR,CatalogSelectionContext.LATEST)) {
            val first=GroupDiscoveryItems.group(listOf(b),context)
            val grouped=GroupDiscoveryItems.group(first+listOf(a,b,a),context).single()
            grouped.mangaId shouldBe a.mangaId
            grouped.actualChapterCount shouldBe 850
            grouped.availableVersions.size shouldBe 2
            grouped.alternatives.single().url shouldBe bRoute
            GroupDiscoveryItems.group(listOf(grouped,grouped),context).single().canonicalWorkId shouldBe grouped.canonicalWorkId
            val search=SearchWorkSelection().select(listOf(a,b).map { row ->
                val identity=PreferredMangaVariants.work(row)
                SearchWorkCandidate(row.mangaId.toString(),row.sourceId.toString(),SearchWorkKind.COMIC,row.title,
                    catalogIdentity=VerifiedCatalogIdentity.from(identity))
            }).single()
            search.key shouldBe grouped.canonicalWorkId
            search.members.size shouldBe 2
        }
    }
    @Test fun titleCoverAndSimilarRouteCannotApproveAnotherWork() {
        CatalogWorkEvidence.replaceReviewedRegistry(bundle())
        val a=card(aSource,780026,aRoute,"Otherworldly Evil Monarch",2)
        val unrelated=card(777777,780048,aRoute,"Otherworldly Evil Monarch",999)
        GroupDiscoveryItems.group(listOf(a,unrelated)).size shouldBe 2
        ReviewedCatalogRegistry.parse(bundle()).resolve(work(bSource,bRoute+"?edition=other")) shouldBe null
    }
    @Test fun revocationSplitsAlreadyGroupedCachedCardsWithoutLosingTheirReferences() {
        CatalogWorkEvidence.replaceReviewedRegistry(bundle())
        val a=card(aSource,780027,aRoute,"Title",2);val b=card(bSource,780049,bRoute,"عنوان",3)
        val grouped=GroupDiscoveryItems.group(listOf(a,b))
        grouped.size shouldBe 1
        CatalogWorkEvidence.replaceReviewedRegistry(bundle().replace("\"state\": \"ACTIVE\"","\"state\": \"REVOKED\""))
        val split=GroupDiscoveryItems.group(grouped)
        split.size shouldBe 2
        split.map {it.mangaId}.toSet() shouldBe setOf(a.mangaId,b.mangaId)
    }
    @Test fun pendingRecordsAndUnsupportedSchemasDoNotAuthorizeGrouping() {
        val pending=ReviewedCatalogRegistry.parse(bundle().replace("\"state\": \"ACTIVE\"","\"state\": \"PENDING\""))
        pending.resolve(work(aSource,aRoute)) shouldBe null
        assertThrows(IllegalArgumentException::class.java) {ReviewedCatalogRegistry.parse(bundle().replace("\"schema\": 1","\"schema\": 2"))}
        val noReview=record().filterKeys {it!="reviewer"}
        assertThrows(IllegalArgumentException::class.java) {
            ReviewedCatalogRegistry.parse(buildJsonObject {put("schema",1);put("records",JsonArray(listOf(JsonObject(noReview))))}.toString())
        }
    }
    @Test fun conflictsRejectWholeUpdateAndRetainLastGoodRegistry() {
        CatalogWorkEvidence.replaceReviewedRegistry(bundle())
        val duplicate=JsonObject(record()+("key" to JsonPrimitive("f".repeat(64))))
        val conflict=buildJsonObject {put("schema",1);put("records",JsonArray(listOf(record(),duplicate)))}
        assertThrows(IllegalArgumentException::class.java) {CatalogWorkEvidence.replaceReviewedRegistry(conflict.toString())}
        CatalogWorkEvidence.resolve(work(aSource,aRoute)).reviewedWorkKey shouldBe record()["key"]!!.jsonPrimitive.content
    }
    @Test fun contradictoryExternalAuthoritiesStillCannotMergeDespiteEditorialLink() {
        val registry=ReviewedCatalogRegistry.parse(bundle())
        val a=work(aSource,aRoute).copy(externalIds=mapOf("anilist" to "1"))
        val b=work(bSource,bRoute).copy(externalIds=mapOf("anilist" to "2"))
        WorkLinker.match(a.copy(reviewedWorkKey=registry.resolve(a)),b.copy(reviewedWorkKey=registry.resolve(b))) shouldBe null
    }
    @Test fun providerAliasNeedsExactSourceAndValidatedProviderIdentity() {
        val registry=ReviewedCatalogRegistry.parse(bundle())
        registry.resolve(work(bSource,"775").copy(providerId="775")) shouldBe registry.resolve(work(bSource,bRoute))
        registry.resolve(work(bSource,bRoute).copy(providerId="999")) shouldBe null
        registry.resolve(work(aSource,"775").copy(providerId="775")) shouldBe null
    }
    @Test fun restartedRegistryKeepsGroupIdentityWhenOnlyLocalRowIdsChange() {
        val first=ReviewedCatalogRegistry.parse(bundle());val restarted=ReviewedCatalogRegistry.parse(bundle())
        first.resolve(work(aSource,aRoute)) shouldBe restarted.resolve(work(aSource,aRoute).copy(reference=SourceWorkReference(aSource,99999,aRoute)))
    }
    @Test fun oldExplicitEditionPreferenceSurvivesNewGroupingAndAccountSwitching() = runBlocking {
        val prefs=mockk<PreferenceStore>();val values=mutableMapOf<String,InMemoryPreference<String>>()
        every {prefs.getString(any(),any())} answers {values.getOrPut(firstArg()) {InMemoryPreference(firstArg(),null,secondArg())}}
        val store=CatalogEditionPreferences(prefs)
        val a=card(aSource,780028,aRoute,"Title",90);val b=card(bSource,780050,bRoute,"عنوان",850)
        val old=GroupDiscoveryItems.group(listOf(a)).single()
        val accountA=UUID.randomUUID().toString();val accountB=UUID.randomUUID().toString()
        try {
            Scope.commit(accountA);store.remember(old,a.mangaId)
            CatalogWorkEvidence.replaceReviewedRegistry(bundle())
            val grouped=GroupDiscoveryItems.group(listOf(a,b),CatalogSelectionContext.POPULAR).single()
            grouped.mangaId shouldBe b.mangaId
            store.selected(grouped)!!.mangaId shouldBe a.mangaId
            Scope.commit(accountB);store.selected(grouped) shouldBe null
            Scope.commit(accountA);store.selected(grouped)!!.mangaId shouldBe a.mangaId
            store.remember(grouped,b.mangaId);store.selected(grouped)!!.mangaId shouldBe b.mangaId
        } finally {Scope.commit(Scope.GUEST)}
    }
}
