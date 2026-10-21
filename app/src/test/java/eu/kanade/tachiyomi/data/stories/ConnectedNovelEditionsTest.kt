package eu.kanade.tachiyomi.data.stories

import eu.kanade.tachiyomi.novels.*
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ConnectedNovelEditionsTest {
    private val registry get()=StoryFixtures.registry
    private val novels get()=StoryFixtures.tbate.original.editions.map(StoryFixtures::novel)
    private fun catalog(rows: List<Novel>)=NovelCatalogState(rows.map { UnifiedNovelWork(NovelIdentity.initialWorkId(it.id),listOf(it),it.id) })

    @Test fun `two enrolled prose editions reach native chooser even before native catalog reconciliation`() {
        val rows=novels;val catalog=catalog(rows);val base=catalog.work(rows[0].id)!!
        val projected=ConnectedNovelEditions.project(registry,rows[0],base,catalog) {true}
        projected.editions.map {it.id} shouldBe rows.map {it.id}
        projected.id shouldBe base.id
        projected.primaryEditionId shouldBe base.primaryEditionId
        catalog.works.size shouldBe 2 // No canonical database/catalog merge.
        catalog.works.all {it.editions.size==1} shouldBe true
        projected.editions.map {it.url} shouldBe rows.map {it.url}
    }
    @Test fun `reverse novel entry preserves initially selected edition and all existing valid alternatives`() {
        val rows=novels;val catalog=catalog(rows);val base=catalog.work(rows[1].id)!!
        val projected=ConnectedNovelEditions.project(registry,rows[1],base,catalog) {true}
        projected.primaryEditionId shouldBe rows[1].id
        projected.editions.first() shouldBe rows[1]
        projected.editions.size shouldBe 2
    }
    @Test fun `comic and novel adaptations cannot enter prose chooser via relation`() {
        val rows=novels;val comic=rows[1].copy(contentFormat=NovelContentFormat(NovelContentType.COMIC,NovelFormatProvenance.SOURCE_TAXONOMY,"manhwa"))
        val catalog=catalog(listOf(rows[0],comic));val base=catalog.work(rows[0].id)!!
        ConnectedNovelEditions.project(registry,rows[0],base,catalog) {true} shouldBe base
    }
    @Test fun `unapproved missing inactive and revoked editions are not injected`() {
        val rows=novels;val unknown=rows[0].copy(url="https://mknov.com/novel/99999")
        val catalog=catalog(rows+unknown);val base=catalog.work(rows[0].id)!!
        ConnectedNovelEditions.project(registry,rows[0],base,catalog) {it!=rows[1].sourceId}.editions shouldBe base.editions
        ConnectedNovelEditions.project(StoryFixtures.state("REVOKED"),rows[0],base,catalog) {true} shouldBe base
        val other=catalog.work(unknown.id)!!
        ConnectedNovelEditions.project(registry,unknown,other,catalog) {true} shouldBe other
        ConnectedNovelEditions.project(registry,rows[0],base,catalog(listOf(rows[0]))) {true} shouldBe base
    }
    @Test fun `conflicting external identities veto cross-edition chooser injection`() {
        val rows=novels.mapIndexed {i,n -> n.copy(workIdentifiers=listOf("anilist:${i+1}")) }
        val catalog=catalog(rows);val base=catalog.work(rows[0].id)!!
        ConnectedNovelEditions.project(registry,rows[0],base,catalog) {true} shouldBe base
    }
}
