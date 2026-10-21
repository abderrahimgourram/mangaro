package eu.kanade.tachiyomi.novels

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

class NovelCompletionEvidenceTest {
    private val novel = Novel("novel.mknov", "https://mknov.com/novel/96", "Synthetic")
    private fun position(chapter: Int = 20716, at: Long = 1, completed: Boolean? = true) =
        NovelReadingPosition(
            NovelChapter("${novel.url}/chapter/$chapter", "Synthetic", 0),
            paragraph = at.toInt(),
            updatedAt = at,
            completed = completed,
        )

    @Test
    fun `stale library pointer cannot overwrite a newer persisted chapter position`() {
        val disk = position(at = 100)
        val pointer = position(at = 10, completed = null)
        val row = NovelCloudState.rows(NovelLibraryItem(novel, position = pointer), listOf(disk)).values.last()
        NovelCloudState.decode(NovelCloudState.PROGRESS, row).position!!.paragraph shouldBe 100
        // Legacy production still rejects this field. Never enable a new server contract implicitly.
        row["state"]!!.jsonObject["position"]!!.jsonObject.containsKey("completed") shouldBe false
    }

    @Test
    fun `retries lost acknowledgements and both device delivery orders retain one completion`() {
        val done = position(at = 10)
        val reread = position(at = 20, completed = false)
        for (records in listOf(listOf(done, reread), listOf(reread, done), List(100) { done } + reread)) {
            val merged = coalesceNovelReadingPositions(records).values.single()
            merged.completed shouldBe true
            merged.updatedAt shouldBe 20
            novelReadStatistics(records.map { novel to it }).count shouldBe 1
        }
    }

    @Test
    fun `newer unknown remote position preserves genuine local completion without moving backwards`() {
        val merged = coalesceNovelReadingPositions(
            listOf(position(at = 10), position(at = 20, completed = null)),
        ).values.single()
        merged.completed shouldBe true
        merged.paragraph shouldBe 20
    }

    @Test
    fun `opening downloading and a high chapter number cannot create completion evidence`() {
        val opened = position(chapter = 999999, completed = false)
        novelReadStatistics(listOf(novel to opened)).count shouldBe 0
        novelReadStatistics(listOf(novel to opened.copy(completed = null))).count shouldBe null
        novelReadStatistics(emptyList()).completed shouldBe 0
    }

    @Test
    fun `duplicate work records cannot count the exact same source chapter twice`() {
        val done = position()
        novelReadStatistics(
            listOf(novel to done, novel.copy(url = "https://mknov.com/novel/096") to done),
        ).count shouldBe
            1
        novelReadStatistics(listOf(novel to done, novel.copy(sourceId = "novel.other") to done)).count shouldBe 2
        novelReadStatistics(listOf(novel to done, novel to position(chapter = 20967))).count shouldBe 2
    }

    @Test
    fun `different accounts must be projected independently rather than folded together`() {
        val a = List(62) { novel to position(chapter = it + 1) }
        val b = List(5) { novel to position(chapter = it + 1) }
        listOf(a, b, a).map { novelReadStatistics(it).count } shouldBe listOf(62L, 5L, 62L)
        NovelReadStatistics(0, 0, authoritative = false).count shouldBe null
    }

    @Test
    fun `restored snapshots do not mutate their inputs or add XP state`() {
        val done = position()
        val raw = NovelCloudState.rows(NovelLibraryItem(novel, position = done), listOf(done)).values.last()
        val restored = NovelCloudState.decode(NovelCloudState.PROGRESS, raw).position!!
        restored.completed shouldBe null
        preserveNovelCompletion(done, restored).completed shouldBe true
        done.completed shouldBe true
        raw["source_id"]!!.jsonPrimitive.content shouldBe novel.sourceId
        raw.keys.any { it.contains("xp", ignoreCase = true) } shouldBe false
    }
}
