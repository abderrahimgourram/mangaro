package mihon.domain.community

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class WeeklyCommunityPicksTest {
    private val keys=(1..30).map {it.toString(16).padStart(64,'0')}
    private fun rated(i:Int,v:Long=20,r:Double=4.5)=CommunityRankedWork(keys[i],r,v,WeeklyCommunityPicks.weighted(r,v,4.0,2.0))
    @Test fun `weighted score and minimum signal protect against lone five star`() {
        val lone=rated(0,1,5.0);val confident=rated(1,400,4.8)
        (confident.score>lone.score) shouldBe true
        val picks=WeeklyCommunityPicks.select(listOf(lone,confident),keys,"2026-W40")
        picks.first().key shouldBe confident.mangaKey
        picks.count {it.rating!=null} shouldBe 1
    }
    @Test fun `five four two and zero ranked works fill exactly five without duplicates`() {
        listOf(5,4,2,0).forEach {n ->
            val picks=WeeklyCommunityPicks.select((0 until n).map {rated(it)},keys+keys,"2026-W40")
            picks.size shouldBe 5;picks.distinctBy {it.key}.size shouldBe 5
            picks.count {it.rating!=null} shouldBe n
            picks.drop(n).all {it.rating==null} shouldBe true
        }
    }
    @Test fun `ties are deterministic by count average and exact key`() {
        val a=rated(0).copy(score=4.0);val b=rated(1,30).copy(score=4.0);val c=rated(2,30,4.7).copy(score=4.0)
        val works=listOf(a,b,c,rated(3).copy(score=4.0))
        WeeklyCommunityPicks.select(works.reversed(),keys,"2026-W40").take(4).map {it.key} shouldBe listOf(c.mangaKey,b.mangaKey,a.mangaKey,keys[3])
    }
    @Test fun `ISO year week handles boundary and freezes within week then rotates`() {
        WeeklyCommunityPicks.week(LocalDate.of(2021,1,1)) shouldBe "2020-W53"
        WeeklyCommunityPicks.week(LocalDate.of(2026,10,4)) shouldBe "2026-W40"
        WeeklyCommunityPicks.week(LocalDate.of(2026,10,5)) shouldBe "2026-W41"
        val picks=WeeklyCommunityPicks.select(emptyList(),keys,"2026-W40")
        WeeklyCommunityPicks.select(emptyList(),keys.reversed(),"2026-W40") shouldBe picks
        (WeeklyCommunityPicks.select(emptyList(),keys,"2026-W41")!=picks) shouldBe true
    }
    @Test fun `five ranked works ignore weekly rotation and preserve exact source keys`() {
        val ranked=(0..4).map {rated(it)}
        WeeklyCommunityPicks.select(ranked,keys,"2026-W40") shouldBe WeeklyCommunityPicks.select(ranked,keys,"2026-W41")
        val result=WeeklyCommunityPicks.select(ranked,keys+"provider title","2026-W40")
        result.all {it.key in keys} shouldBe true
        result.size shouldBe 5
    }
}
