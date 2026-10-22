package eu.kanade.tachiyomi.novels

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

class NovelCompletionWireTest {
    private val novel = Novel("novel.cenele", "https://cenele.com/series/synthetic/", "Synthetic")
    private val chapter = NovelChapter("${novel.url}chapter-1/", "Synthetic", 1)
    private fun rows(done: Boolean? = true, at: Long = 1, v2: Boolean = true) = NovelCloudState.rows(
        NovelLibraryItem(
            novel,
            saved = true,
            position = NovelReadingPosition(chapter, updatedAt = at, completed = done),
        ),
        emptyList(),
        v2,
    )
    private fun progress(done: Boolean? = true, at: Long = 1) = rows(done, at).values.last()
    private fun done(row: JsonObject) = row.getValue("state").jsonObject.getValue("position").jsonObject["completed"]

    @Test fun `default wire remains byte compatible with schema one`() {
        rows(v2 = false).values.forEach { NovelCloudState.hasCompletion(it) shouldBe false }
        done(progress()) shouldBe JsonPrimitive(true)
        done(progress(false)) shouldBe JsonPrimitive(false)
        done(progress(null)) shouldBe null
    }

    @Test fun `wire downgrade never destroys the durable true evidence`() {
        rows().values.forEach {
            NovelCloudState.hasCompletion(it) shouldBe true
            NovelCloudState.hasCompletion(NovelCloudState.forServer(it, false)) shouldBe false
            NovelCloudState.forServer(it, true) shouldBe it
            NovelCloudState.hasCompletion(it) shouldBe true
        }
    }

    @Test fun `restored true and local true union once by exact source chapter`() {
        val restored = NovelCloudState.decode(NovelCloudState.PROGRESS, progress()).position!!
        val newerUnknown = restored.copy(updatedAt = 2, completed = null)
        novelReadStatistics(listOf(novel to restored, novel to restored, novel to newerUnknown)).completed shouldBe 1
        novelReadStatistics(
            listOf(novel to restored, novel.copy(sourceId = "novel.seanovel") to restored),
        ).completed shouldBe
            2
    }

    @Test fun `newer durable disk evidence survives a stale library pointer on v2`() {
        val pointer = NovelReadingPosition(chapter, paragraph = 1, updatedAt = 1, completed = null)
        val disk = pointer.copy(paragraph = 20, updatedAt = 2, completed = true)
        val row = NovelCloudState.rows(
            NovelLibraryItem(novel, position = pointer),
            List(5) {
                disk
            },
            true,
        ).values.last()
        done(row) shouldBe JsonPrimitive(true)
        NovelCloudState.decode(NovelCloudState.PROGRESS, row).position!!.paragraph shouldBe 20
    }

    @Test fun `completion and independent bookmark edit commute without a fabricated cursor`() {
        val base = progress(false)
        val completed = progress()
        val remote =
            JsonObject(
                base + (
                    "state" to JsonObject(
                        base.getValue("state").jsonObject + ("bookmarked" to JsonPrimitive(true)),
                    )
                    ),
            )
        val merged = NovelCloudState.merge(base, completed, remote)!!
        done(merged) shouldBe JsonPrimitive(true)
        merged.getValue("state").jsonObject["bookmarked"] shouldBe JsonPrimitive(true)
    }

    @Test fun `established true cannot become false during compatible cursor merge`() {
        done(NovelCloudState.merge(progress(), progress(), progress(false))!!) shouldBe JsonPrimitive(true)
    }

    @Test fun `changing the parent chapter never inherits completion from the previous chapter`() {
        val before = rows().values.first()
        val next = NovelCloudState.rows(
            NovelLibraryItem(
                novel,
                saved = true,
                position = NovelReadingPosition(
                    chapter.copy(url = "${novel.url}chapter-2/"),
                    updatedAt = 2,
                    completed = false,
                ),
            ),
            emptyList(),
            true,
        ).values.first()
        val merged = NovelCloudState.merge(before, next, next)!!
        merged.getValue("state").jsonObject.getValue("lastPosition").jsonObject["completed"] shouldBe
            JsonPrimitive(false)
    }

    @Test fun `advancing to another chapter preserves existing one-sided conflict resolution`() {
        val before = rows().values.first()
        val next = NovelCloudState.rows(
            NovelLibraryItem(
                novel,
                saved = true,
                position = NovelReadingPosition(
                    chapter.copy(url = "${novel.url}chapter-2/"),
                    updatedAt = 2,
                    completed = false,
                ),
            ),
            emptyList(),
            true,
        ).values.first()
        NovelCloudState.merge(before, before, next)!!["state"] shouldBe next["state"]
        NovelCloudState.merge(before, next, before)!!["state"] shouldBe next["state"]
    }

    @Test fun `concurrent competing cursor changes keep explicit conflict semantics`() {
        NovelCloudState.merge(progress(at = 1), progress(at = 2), progress(at = 3)) shouldBe null
    }

    @Test fun `a remote tombstone is never silently resurrected by completion`() {
        val base = progress(false)
        val remote = JsonObject(base + ("deleted_at" to JsonPrimitive("2026-10-21T00:00:00Z")))
        NovelCloudState.merge(base, progress(), remote) shouldBe null
        remote.getValue("state").jsonObject.getValue("position").jsonObject["completed"] shouldBe JsonPrimitive(false)
        progress(null).getValue("state").jsonObject.getValue("position").jsonObject["completed"] shouldBe null
    }
}
