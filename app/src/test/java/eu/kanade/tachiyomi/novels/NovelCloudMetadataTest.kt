package eu.kanade.tachiyomi.novels

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test

class NovelCloudMetadataTest {
    private val novel=Novel("novel.sunovels","https://sunovels.com/novel/i-have-an-apocalypse-city","City")
    private val chapter=NovelChapter(novel.url+"/1","1",0,publishedRows=listOf(NovelPublishedChapterRow(novel.url+"/1","one")),
        unavailableReason="old lock",sunovelsState=SunovelsChapterState.EXPLICITLY_LOCKED,sunovelsEvidence=SunovelsChapterEvidence.PUBLIC_READER)
    private fun row(at:Long)=NovelCloudState.rows(NovelLibraryItem(novel,saved=true,position=NovelReadingPosition(chapter,3,17,at)),emptyList()).values.first()
    @Test fun `cloud position keeps exact identity and offset but never carries source access evidence`() {
        val row=row(100)
        val c=row["state"]!!.jsonObject["lastPosition"]!!.jsonObject["chapter"]!!.jsonObject
        c.keys.intersect(setOf("publishedRows","unavailableReason","sunovelsState","sunovelsEvidence")) shouldBe emptySet()
        val restored=NovelCloudState.decode(NovelCloudState.LIBRARY,row)
        restored.position!!.chapter.id shouldBe chapter.id
        restored.position!!.paragraph shouldBe 3
        restored.position!!.offset shouldBe 17
        chapter.sunovelsState shouldBe SunovelsChapterState.EXPLICITLY_LOCKED // Original reader evidence is untouched.
    }
    @Test fun `late offline position conflicts instead of silently moving a different device backwards`() {
        val base=row(100); val offline=row(110); val newer=row(200)
        NovelCloudState.merge(base,offline,newer) shouldBe null
        val deleted=JsonObject(newer+("deleted_at" to JsonPrimitive("2026-10-15T00:00:00Z")))
        NovelCloudState.merge(base,offline,deleted) shouldBe null
    }
}
