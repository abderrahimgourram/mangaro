package eu.kanade.tachiyomi.data.stories

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.util.lang.LocalAccountScope as Local

class ConnectedStoryResolverTest {
    private val token = Local.Token("fixture",1)
    private val registry get() = StoryFixtures.registry
    private fun resolve(origin: StoryCandidate, rows: List<StoryCandidate>) = ConnectedStoryResolver.resolve(registry,origin,rows,token)

    @Test fun `actual enrolled Solo manhwa to novel keeps real metadata and distinct work identities`() {
        val origin=StoryFixtures.comics().first();val novel=StoryFixtures.novels().single()
        val result=resolve(origin,listOf(novel))!!
        result.heading shouldBe "إصدار رواية"
        result.selected shouldBe novel.destination
        result.targetWork shouldBe StoryFixtures.solo.original.work
        (result.targetWork == StoryFixtures.solo.adaptation.work) shouldBe false
        result.selected.cover shouldBe novel.destination.cover
        result.selected.title shouldBe novel.destination.title
    }
    @Test fun `three original Solo comic editions produce exactly one reverse card with three routes`() {
        val comics=StoryFixtures.comics();val result=resolve(StoryFixtures.novels().single(),comics+comics)!!
        result.heading shouldBe "إصدار مانهوا"
        result.alternatives.size shouldBe 3
        result.alternatives.map { it.edition }.toSet() shouldBe comics.map { it.destination.edition }.toSet()
    }
    @Test fun `TBATE two novel editions and two manhwa editions remain separate within one relation`() {
        val record=StoryFixtures.tbate
        val comics=StoryFixtures.comics(record);val novels=StoryFixtures.novels(record)
        resolve(comics.first(),novels)!!.alternatives.size shouldBe 2
        resolve(novels.first(),comics)!!.alternatives.size shouldBe 2
        record.adaptation.editions.map { it.url } shouldBe listOf("/manga/the-beginning-after-the-end","/series/BATE")
    }
    @Test fun `similar titles same author and guessed route never authorize a card`() {
        val similar=StoryFixtures.comics().first().let { it.copy(destination=StoryDestination.Comic(
            (it.destination as StoryDestination.Comic).manga.copy(url="/manga/unreviewed",author="Chugong"))) }
        resolve(similar,StoryFixtures.novels()) shouldBe null
    }
    @Test fun `Ragnarok ARISE and Jasmine are not original story endpoints`() {
        for(route in listOf("/manga/solo-leveling-ragnarok","/manga/solo-leveling-arise","/manga/the-beginning-after-the-end-jasmine")) {
            val base=(StoryFixtures.comics().first().destination as StoryDestination.Comic).manga
            resolve(StoryCandidate(StoryDestination.Comic(base.copy(url=route))),StoryFixtures.novels()) shouldBe null
        }
    }
    @Test fun `absent target and absent real cover cannot produce broken card`() {
        val origin=StoryFixtures.comics().first()
        resolve(origin,emptyList()) shouldBe null
        val novel=(StoryFixtures.novels().single().destination as StoryDestination.Prose).novel
        resolve(origin,listOf(StoryCandidate(StoryDestination.Prose(novel.copy(cover=null))))) shouldBe null
    }
    @Test fun `revoked pending and conflicted links disappear`() {
        for(status in listOf("REVOKED","PENDING","CONFLICTED")) {
            ConnectedStoryResolver.resolve(StoryFixtures.state(status),StoryFixtures.comics().first(),StoryFixtures.novels(),token) shouldBe null
        }
    }
    @Test fun `conflicting canonical reviewed work vetoes an exact route`() {
        resolve(StoryFixtures.comics().first().copy(reviewedComicKey="f".repeat(64)),StoryFixtures.novels()) shouldBe null
    }
    @Test fun `conflicting trusted identities between same-format editions veto grouping`() {
        val comics=StoryFixtures.comics().take(2).mapIndexed { index,c -> c.copy(externalIds=mapOf("anilist" to "$index")) }
        resolve(StoryFixtures.novels().single(),comics) shouldBe null
    }
    @Test fun `conflicting snapshots of the same source edition cannot be hidden by deduplication`() {
        val novel=StoryFixtures.novels().single()
        resolve(StoryFixtures.comics().first(),listOf(novel.copy(externalIds=mapOf("anilist" to "1")),novel.copy(externalIds=mapOf("anilist" to "2")))) shouldBe null
    }
    @Test fun `novel and adaptation may legitimately have different external IDs`() {
        val comic=StoryFixtures.comics().first().copy(externalIds=mapOf("anilist" to "comic-record"))
        val novel=StoryFixtures.novels().single().copy(externalIds=mapOf("anilist" to "novel-record"))
        (resolve(comic,listOf(novel)) != null) shouldBe true
    }
    @Test fun `confirmed wrong content format excludes both forward and reverse relations`() {
        resolve(StoryFixtures.comics().first(),StoryFixtures.novels().map { it.copy(formatCompatible=false) }) shouldBe null
        resolve(StoryFixtures.novels().single().copy(formatCompatible=false),StoryFixtures.comics()) shouldBe null
    }
    @Test fun `usable reader outranks large indexed count and unavailable edition is never chosen`() {
        val comics=StoryFixtures.comics()
        val candidates=listOf(comics[0].copy(readable=false,verifiedCount=900),comics[1].copy(readable=true,verifiedCount=700),comics[2].copy(verifiedCount=1000))
        val result=resolve(StoryFixtures.novels().single(),candidates)!!
        result.selected shouldBe comics[1].destination
        result.alternatives.size shouldBe 2
    }
    @Test fun `unavailable sources yield no target without discarding registry records`() {
        resolve(StoryFixtures.novels().single(),StoryFixtures.comics().map { it.copy(eligible=false) }) shouldBe null
        registry.records.size shouldBe 3
    }
    @Test fun `projection is deterministic and account-generation safe`() {
        val origin=StoryFixtures.novels().single();val rows=StoryFixtures.comics()
        val first=resolve(origin,rows)!!
        repeat(10) { resolve(origin,rows.reversed()) shouldBe first }
        first.current(Local.State(token)) shouldBe true
        first.current(Local.State(Local.Token("b",2))) shouldBe false
        first.current(Local.State(Local.Token("fixture",3))) shouldBe false
        first.current(Local.State(token,ready=false)) shouldBe false
    }
    @Test fun `all ten enrolled edition origins resolve only to the opposite exact work`() {
        var checked=0
        for(record in registry.records) {
            val comics=StoryFixtures.comics(record);val novels=StoryFixtures.novels(record)
            for(origin in comics+novels) {
                val result=resolve(origin,comics+novels)!!
                (result.selected.edition.format != origin.destination.edition.format) shouldBe true
                val endpoint=if(origin.destination.edition.format==StoryFormat.MANHWA) record.original else record.adaptation
                result.targetWork shouldBe endpoint.work
                (result.selected.edition in endpoint.editions) shouldBe true
                checked++
            }
        }
        checked shouldBe 10
    }

}
