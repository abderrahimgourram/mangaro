package mihon.domain.sigils

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RealmSigilsTest {
    private fun chapter(id: Int,work: String="work",tags: Set<String> = emptySet())=SigilFact("chapter$id","chapter",work,genres=tags,read=true)
    @Test fun `exactly thirty stable original achievements in six worlds`() {
        RealmSigils.all.size shouldBe 30
        RealmSigils.all.map {it.id}.distinct().size shouldBe 30
        SigilWorld.entries.forEach {w -> RealmSigils.all.count {it.world==w} shouldBe 5}
        RealmSigils.all.map {it.motif}.distinct().size shouldBe 30
        RealmSigils.all.map {it.description}.distinct().size shouldBe 30
    }
    @Test fun `distinct completion bookmark and download facts remain idempotent across rereads`() {
        val c=chapter(1).copy(bookmarked=true,downloaded=true)
        val progress=SigilProgress.calculate(listOf(c,c,c))
        progress.getValue("gates_awakened") shouldBe 1
        progress.getValue("murim_keeper") shouldBe 1
        progress.getValue("archive_volumes") shouldBe 1
        SigilProgress.calculate(listOf(c.copy(read=false,downloaded=false))).getValue("archive_volumes") shouldBe 0
    }
    @Test fun `works need independent ten and twenty chapter thresholds`() {
        val facts=(1..10).map {chapter(it,"A")}+(11..20).map {chapter(it,"B")}+(21..30).map {chapter(it,"C")}
        val p=SigilProgress.calculate(facts)
        p.getValue("tower_survivor") shouldBe 3
        p.getValue("tower_fates") shouldBe 0
        p.getValue("tower_visitor") shouldBe 3
        SigilProgress.calculate(listOf(SigilFact("A","work",started=true))).getValue("gates_awakened") shouldBe 0
    }
    @Test fun `verified genres use exact aliases not titles substrings or duplicated translated tags`() {
        SigilGenres.verified(listOf("Martial Arts","الفنون القتالية","رومانسي","Romance","Historical","تاريخي")) shouldBe setOf("martial_arts","romance","historical")
        SigilGenres.verified(listOf("not romance","historical fiction maybe","tower martial arts hero")) shouldBe emptySet()
        val f=(1..100).map {chapter(it,tags=setOf("romance","historical"))}
        SigilProgress.calculate(f).getValue("court_historian") shouldBe 100
        SigilProgress.calculate(f).getValue("archive_dimensions") shouldBe 2
    }
    @Test fun `personal categories and organized works both required for sect master`() {
        val works=(1..10).map {SigilFact("w$it","work",organized=true)}
        SigilProgress.calculate(works).getValue("murim_master") shouldBe 10
        val categories=(1..3).map {SigilFact("category$it","category")}
        SigilProgress.calculate(categories+works).getValue("murim_master") shouldBe 13
        SigilProgress.calculate(categories).getValue("archive_keeper") shouldBe 0
    }
    @Test fun `three ordered slots permit remove but reject duplicates locked unknown and revoked`() {
        val unlocks=listOf(SigilUnlock("gates_awakened"),SigilUnlock("archive_gem"))
        SigilProgress.slotsValid(listOf("archive_gem",null,"gates_awakened"),unlocks) shouldBe true
        SigilProgress.slotsValid(listOf(null,null,null),unlocks) shouldBe true
        SigilProgress.slotsValid(listOf("gates_awakened","gates_awakened",null),unlocks) shouldBe false
        SigilProgress.slotsValid(listOf("gates_guardian",null,null),unlocks) shouldBe false
        SigilProgress.slotsValid(listOf("gates_awakened",null,null),listOf(SigilUnlock("gates_awakened",revoked=true))) shouldBe false
        SigilProgress.slotsValid(listOf(null,null),unlocks) shouldBe false
    }
    @Test fun `community awards cannot come from local reading or forged generic facts`() {
        SigilProgress.calculate((1..1000).map(::chapter)).getValue("social_revered") shouldBe 0
        SigilProgress.calculate(emptyList(),mapOf("social_revered" to 199)).getValue("social_revered") shouldBe 199
    }
    @Test fun `source-scoped identity conflicts never overwrite another work`() {
        val original=chapter(1,"A")
        runCatching { original.merge(original.copy(work="B")) }.isFailure shouldBe true
        original.merge(original.copy(read=false)).read shouldBe true
        original.copy(occurredAt=null).occurredAt shouldBe null
    }
    @Test fun `all thirty requirements are reachable through their real fact types without XP`() {
        val tags=setOf("martial_arts","romance","historical","regression","fantasy")
        val chapters=(1..1000).map {i->chapter(i,"work${(i-1)/100}",tags).copy(bookmarked=i<=20,downloaded=i<=100)}
        val works=(0..19).map {i->SigilFact("work$i","work",genres=tags,started=i<10,library=true,organized=i<10)}
        val categories=(0..2).map {SigilFact("cat$it","category")}
        val verifiedServerCounts=mapOf("social_voice" to 50,"social_pen" to 50,"social_council" to 30,"social_witness" to 20,"social_revered" to 200)
        val progress=SigilProgress.calculate(chapters+works+categories,verifiedServerCounts)
        RealmSigils.all.forEach { (progress.getValue(it.id)>=it.required) shouldBe true }
        RealmSigils.all.map {it.accent}.distinct().size shouldBe 30
    }
    @Test fun `chapter evidence reuses existing remote IDs and never collapses null IDs`() {
        val empty=kotlinx.serialization.json.JsonObject(emptyMap())
        val nullId=kotlinx.serialization.json.JsonObject(mapOf("id" to kotlinx.serialization.json.JsonPrimitive("null")))
        val first=SigilEvidence.chapter(1,"/work","/special",empty,listOf("Romance"),read=true)
        val withNull=SigilEvidence.chapter(1,"/work","/special",nullId,listOf("Romance"),read=true)
        first.key shouldBe withNull.key
        (first.key!=SigilEvidence.chapter(1,"/work","/prologue",nullId,null,read=true).key) shouldBe true
        (first.key!=SigilEvidence.chapter(2,"/work","/special",empty,null,read=true).key) shouldBe true
        first.genres shouldBe setOf("romance")
        first.occurredAt shouldBe null
    }
    @Test fun `ready selection counts single page and final page but not loading opening or backing away`() {
        SigilReadObservation.completed(false,0,0,0) shouldBe false
        SigilReadObservation.completed(true,0,0,0) shouldBe true
        SigilReadObservation.completed(true,0,9,0) shouldBe false
        SigilReadObservation.completed(true,9,9,9) shouldBe true
        SigilReadObservation.completed(true,9,9,8) shouldBe false
        SigilReadObservation.completed(true,0,-1,0) shouldBe false
    }
}
