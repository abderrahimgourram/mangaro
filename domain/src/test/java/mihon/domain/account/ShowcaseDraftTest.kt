package mihon.domain.account

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ShowcaseDraftTest {
    @Test fun `ordered selection uses exact keys and enforces capacity`() {
        val a="a".repeat(64);val b="b".repeat(64);val c="c".repeat(64)
        val selected=ShowcaseDraft().toggle(a,2).toggle(b,2)
        selected.toggle(c,2) shouldBe selected
        selected.keys shouldBe listOf(a,b)
        selected.move(b,-1).keys shouldBe listOf(b,a)
        selected.move(a,-1) shouldBe selected
        selected.move("missing",1) shouldBe selected
    }
    @Test fun `feature is a bounded subset and removal preserves other ordering`() {
        val draft=ShowcaseDraft(listOf("a","b","c","d")).feature("a").feature("b").feature("c")
        draft.feature("d") shouldBe draft
        draft.toggle("b",5).apply {keys shouldBe listOf("a","c","d");featured shouldBe setOf("a","c")}
        draft.feature("missing") shouldBe draft
        draft.feature("b").feature("d").featured shouldBe setOf("a","c","d")
        ShowcaseDraft().keys shouldBe emptyList()
    }
}
