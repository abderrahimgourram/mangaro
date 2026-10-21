package eu.kanade.tachiyomi.novels

import eu.kanade.presentation.novels.*
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class NovelReaderEndReachabilityTest {
    @Test fun `62 segments do not end at first visible segment 58`() {
        val viewport = (58..62).map { NovelVisibleBlock(it, (it-58)*100, 100) }
        val result = novelChapterProgress(62,63,viewport,800,false)
        result.segment shouldBe 62
        result.percent shouldBe 100
        result.atEnd shouldBe true
    }
    @Test fun `every deterministic length has a real last segment without dropping text`() {
        listOf(1,2,10,58,60,62,100,240).forEach { count ->
            val paragraphs=(1..count).map { "الفقرة $it — English $it" }
            val text=NovelText(paragraphs)
            val rendered=text.orderedBlocks().map { paragraphs[it.paragraph!!] }
            rendered shouldBe paragraphs
            for (last in 1..count) {
                val result=novelChapterProgress(count,count+1,listOf(NovelVisibleBlock(last,0,100)),100,last<count)
                result.segment shouldBe last
                result.atEnd shouldBe (last==count)
                (result.percent==100) shouldBe (last==count)
            }
            rendered.last() shouldBe paragraphs.last()
        }
    }
    @Test fun `partly visible final paragraph never yields 100 even with stale scroll flag`() {
        novelChapterProgress(62,63,listOf(NovelVisibleBlock(62,400,700)),800,false).atEnd shouldBe false
        novelChapterProgress(62,63,listOf(NovelVisibleBlock(62,400,700)),800,false).percent shouldBe 98
    }
    @Test fun `font reflow and viewport change require new measured end evidence`() {
        novelChapterProgress(100,101,listOf(NovelVisibleBlock(100,0,100)),800,false).percent shouldBe 100
        val reflow=novelChapterProgress(100,101,listOf(NovelVisibleBlock(100,0,1200)),800,true)
        reflow.percent shouldBe 99
        reflow.atEnd shouldBe false
        novelChapterProgress(100,101,listOf(NovelVisibleBlock(100,-400,1200)),800,false).percent shouldBe 100
    }
    @Test fun `unmeasured empty and old chapter layouts cannot become completed`() {
        novelChapterProgress(0,0,emptyList(),0,false).percent shouldBe null
        novelChapterProgress(62,61,listOf(NovelVisibleBlock(60,0,100)),800,false).percent shouldBe null
        novelChapterProgress(62,63,listOf(NovelVisibleBlock(0,0,100)),800,false).atEnd shouldBe false
    }
    @Test fun `illustrations retain their sequence and final text in offline model`() {
        val text=NovelText(listOf("الأولى","النهاية"),blocks=listOf(NovelContentBlock(NovelBlockKind.TEXT,paragraph=0),
            NovelContentBlock(NovelBlockKind.IMAGE,imageUrl="https://cenele.com/a.jpg"),NovelContentBlock(NovelBlockKind.TEXT,paragraph=1)))
        text.orderedBlocks().map { it.kind } shouldBe listOf(NovelBlockKind.TEXT,NovelBlockKind.IMAGE,NovelBlockKind.TEXT)
        text.orderedBlocks().last().paragraph shouldBe 1
    }
}
