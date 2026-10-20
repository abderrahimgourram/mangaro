package eu.kanade.tachiyomi.novels

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test

class NovelReadStatisticsTest {
    private val a=Novel("novel.mknov","https://mknov.com/novel/96","fixture")
    private fun position(id: Int,completed: Boolean?)=NovelReadingPosition(NovelChapter(a.url+"/chapter/$id","chapter",id),completed=completed)
    @Test fun `read means observed end not opened downloaded or largest chapter number`() {
        novelReadStatistics(listOf(a to position(3000,false))).count shouldBe 0
        novelReadStatistics(listOf(a to position(1,true),a to position(2,false))).count shouldBe 1
        novelReadStatistics(listOf(a to position(2,null))).count shouldBe null
    }
    @Test fun `legacy missing completion never turns into a confirmed zero or exact total`() {
        val stats=novelReadStatistics(listOf(a to position(1,true),a to position(2,null)))
        stats.count shouldBe 1
        stats.partial shouldBe true
        val decoded=Json.decodeFromString<NovelReadingPosition>("""{"chapter":{"url":"${a.url}/chapter/9","title":"old","order":9},"paragraph":62}""")
        decoded.completed shouldBe null
    }
    @Test fun `sync retries restoration and repeated source rows are not increments`() {
        val records=List(100) { a to position(1,true) }
        novelReadStatistics(records).count shouldBe 1
        novelReadStatistics(records+ (a.copy(sourceId="novel.other") to position(1,true))).count shouldBe 2
    }
    @Test fun `moving backwards and old server payloads preserve local completed evidence and positions`() {
        val old=position(1,true)
        val back=position(1,false).copy(paragraph=2,offset=14)
        preserveNovelCompletion(old,back) shouldBe back.copy(completed=true)
        preserveNovelCompletion(old,back.copy(completed=null)).completed shouldBe true
        preserveNovelCompletion(old,position(2,false)).completed shouldBe false
        preserveNovelCompletion(position(1,null),back).completed shouldBe null
    }
    @Test fun `mknov exact public identities reach cloud serialization without unsupported completion keys`() {
        val rows=NovelCloudState.rows(NovelLibraryItem(a,position=position(20716,true)),emptyList())
        rows.size shouldBe 2
        rows.values.forEach { row ->
            row["source_id"]!!.jsonPrimitive.content shouldBe "novel.mknov"
            val state=row["state"]!!.jsonObject
            val pos=(state["lastPosition"] ?: state["position"])!!.jsonObject
            ("completed" in pos) shouldBe false
        }
        NovelCloudState.decode(NovelCloudState.PROGRESS,rows.values.last()).chapterUrl shouldBe a.url+"/chapter/20716"
    }
    @Test fun `cloud source host checks remain enforced`() {
        runCatching { NovelCloudState.rows(NovelLibraryItem(a.copy(url="https://evil.test/novel/96")),emptyList()) }.isFailure shouldBe true
    }
}
