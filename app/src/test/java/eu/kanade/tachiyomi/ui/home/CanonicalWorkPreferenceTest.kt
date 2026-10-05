package eu.kanade.tachiyomi.ui.home

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.ChapterListIntegrity
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import mihon.domain.source.health.SourceHealthMonitor

class CanonicalWorkPreferenceTest {
    private fun manga(id: Long, count: Int, state: String = "COMPLETE"): Manga {
        val m = Manga.create().copy(id=id,source=id,url="/work/$id",title="Linked Work $id",author="Creator",initialized=true,
            memo=buildJsonObject { put("claimedChapterCount", 230) })
        return m.copy(memo=ChapterListIntegrity.memo(m,state,rows(m,count)))
    }
    private fun rows(m: Manga, count: Int) = (1..count).map { Chapter.create().copy(id=it.toLong(),mangaId=m.id,
        url="/chapter/$it",name="Chapter $it",chapterNumber=it.toDouble()) }
    private fun item(m: Manga) = HomeDiscoveryItem(m.id,m.title,m.asMangaCover(),m.source,"",m.url)

    @Test fun `count comes from actual stored entries not claimed total or chapter number`() {
        val m = manga(921001, 200, "PARTIAL")
        ChapterListIntegrity.actualChapterCount(m) shouldBe 200
        val gaps = listOf(1.0, 2.0, 4.0, 5.0).mapIndexed { i, n -> Chapter.create().copy(id=i.toLong(),mangaId=m.id,url="/$i",name="Chapter $n",chapterNumber=n) }
        ChapterListIntegrity.actualChapterCount(m.copy(memo=ChapterListIntegrity.memo(m,"COMPLETE",gaps))) shouldBe 4
    }
    @Test fun `decimal zero and unnumbered specials survive count and duplicate URLs do not inflate it`() {
        val m = manga(921002, 0)
        val chapters = listOf(0.0,0.5,12.5,-1.0,-1.0).mapIndexed { i,n -> Chapter.create().copy(id=i.toLong(),mangaId=m.id,
            url="/$i",name=listOf("Chapter 0","Chapter 0.5","Chapter 12.5","Prologue","Special")[i],chapterNumber=n) }
        ChapterListIntegrity.actualChapterCount(m.copy(memo=ChapterListIntegrity.memo(m,"COMPLETE",chapters+chapters.last()))) shouldBe 5
        assertThrows<IllegalArgumentException> { ChapterListIntegrity.memo(m,"COMPLETE",listOf(chapters.first().copy(mangaId=1))) }
    }
    @Test fun `230 actual chapters wins over 200 despite metadata quality and temporary source failure`() {
        val a=manga(921003,200).copy(title="The Work",thumbnailUrl="https://site/cover.jpg")
        val b=manga(921004,230).copy(title="The Work")
        PreferredMangaVariants.remember(a); PreferredMangaVariants.remember(b)
        SourceHealthMonitor.shared.success(a.source); SourceHealthMonitor.shared.success(b.source)
        val before=GroupDiscoveryItems.group(listOf(item(a),item(b))).single()
        before.mangaId shouldBe b.id
        before.actualChapterCount shouldBe 230
        before.alternatives.single().mangaId shouldBe a.id
        val failed=b.copy(memo=ChapterListIntegrity.memo(b,"FAILED",rows(b,230)))
        ChapterListIntegrity.actualChapterCount(failed) shouldBe 230
        ChapterListIntegrity.lastSuccessfulAt(failed) shouldBe ChapterListIntegrity.lastSuccessfulAt(b)
        PreferredMangaVariants.remember(failed)
        repeat(4) { SourceHealthMonitor.shared.failure(b.source,semantic=true) }
        try { GroupDiscoveryItems.group(listOf(item(a),item(failed))).single().mangaId shouldBe b.id }
        finally { SourceHealthMonitor.shared.success(b.source) }
    }
    @Test fun `deterministic ties and relationship ID independent of candidate order`() {
        val a=manga(921005,10).copy(title="Same Work")
        // Identical evidence times allow the stable source-ID tie-break to be tested.
        val b=manga(921006,10).copy(title="Same Work",memo=JsonObject(a.memo + (ChapterListIntegrity.KEY to
            JsonObject((a.memo[ChapterListIntegrity.KEY] as JsonObject) + mapOf("source" to JsonPrimitive(921006),"url" to JsonPrimitive("/work/921006"))))))
        PreferredMangaVariants.remember(a); PreferredMangaVariants.remember(b)
        val first=GroupDiscoveryItems.group(listOf(item(a),item(b))).single()
        val reversed=GroupDiscoveryItems.group(listOf(item(b),item(a))).single()
        first.mangaId shouldBe a.id
        reversed.mangaId shouldBe first.mangaId
        reversed.canonicalWorkId shouldBe first.canonicalWorkId
        PreferredMangaVariants.preferred(listOf(a,b)) shouldBe setOf(a.id)
    }
    @Test fun `failed first empty fetch is unknown and migration cannot inherit another source count`() {
        val m=manga(921007,0,"FAILED")
        ChapterListIntegrity.actualChapterCount(m) shouldBe null
        val successful=manga(921008,230)
        ChapterListIntegrity.actualChapterCount(successful.copy(source=1)) shouldBe null
        ChapterListIntegrity.actualChapterCount(successful.copy(url="/changed")) shouldBe null
        ChapterListIntegrity.actualChapterCount(manga(921009,0)) shouldBe 0
    }
    @Test fun `selected featured work survives alias winner change without selecting unrelated identical title`() {
        val original=manga(921012,200).copy(title="Solo Leveling")
        val translated=manga(921013,230).copy(title="التسوية المنفردة",memo=JsonObject(manga(921013,230).memo +
            (tachiyomi.domain.manga.service.WorkMetadata.MEMO_KEY to buildJsonObject {
                put("aliases",JsonArray(listOf(JsonPrimitive("Solo Leveling"))))
            })))
        val unrelated=manga(921014,240).copy(title="Solo Leveling",author="Different Creator")
        listOf(original,translated,unrelated).forEach(PreferredMangaVariants::remember)
        val grouped=GroupDiscoveryItems.group(listOf(item(unrelated),item(original),item(translated)))
        grouped.size shouldBe 2
        GroupDiscoveryItems.findSelectedWork(grouped,item(original))?.mangaId shouldBe translated.id
    }

    @Test fun `older discovery snapshot cannot regress latest reconciled preference count`() {
        val old=manga(921011,200)
        val proof=old.memo[ChapterListIntegrity.KEY] as JsonObject
        val successful=proof["lastSuccessful"] as JsonObject
        val newerProof=ChapterListIntegrity.memo(old,"COMPLETE",rows(old,230))[ChapterListIntegrity.KEY] as JsonObject
        val newerTime=(successful["at"] as JsonPrimitive).content.toLong()+60_000
        val newer=old.copy(memo=JsonObject(old.memo + (ChapterListIntegrity.KEY to JsonObject(newerProof +
            ("lastSuccessful" to buildJsonObject { put("count",230); put("at",newerTime) })))))
        PreferredMangaVariants.remember(newer)
        PreferredMangaVariants.remember(old)
        PreferredMangaVariants.actualChapterCount(item(newer)) shouldBe 230
    }

    @Test fun `successful later reconciliation updates selected count instead of keeping stale maximum`() {
        val m=manga(921010,230)
        val changed=m.copy(memo=ChapterListIntegrity.memo(m,"COMPLETE",rows(m,200)))
        ChapterListIntegrity.actualChapterCount(changed) shouldBe 200
    }
}
