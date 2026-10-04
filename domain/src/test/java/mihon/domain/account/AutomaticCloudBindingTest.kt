package mihon.domain.account

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AutomaticCloudBindingTest {
    @Test fun `fresh sign in and restored same account start without enable decision`() {
        AutomaticCloudBinding.requiresMerge("A",null,emptySet()) shouldBe false
        AutomaticCloudBinding.requiresMerge("A","A",setOf("A")) shouldBe false
        AutomaticCloudBinding.requiresMerge("A",null,setOf("A")) shouldBe false
    }
    @Test fun `account switch and legacy multiple accounts require explicit merge`() {
        AutomaticCloudBinding.requiresMerge("B","A",setOf("A")) shouldBe true
        AutomaticCloudBinding.requiresMerge("B",null,setOf("A")) shouldBe true
        AutomaticCloudBinding.requiresMerge("A",null,setOf("A","B")) shouldBe true
        AutomaticCloudBinding.requiresMerge("B","B",setOf("A","B")) shouldBe false
    }
    @Test fun `pending worker cannot run for guest or another account`() {
        CloudSyncPolicy.canSend("A",null,true) shouldBe false
        CloudSyncPolicy.canSend("A","B",true) shouldBe false
        CloudSyncPolicy.canSend("A","A",true) shouldBe true
    }
}
