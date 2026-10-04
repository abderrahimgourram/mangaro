package mihon.domain.account

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test

class CloudSyncPolicyTest {
    @Test fun `initial merge preserves both libraries and furthest completed progress`() {
        CloudSyncPolicy.initialLibrary(true,false) shouldBe true
        CloudSyncPolicy.initialLibrary(false,true) shouldBe true
        CloudSyncPolicy.initialRead(false,true) shouldBe true
        CloudSyncPolicy.initialRead(true,false) shouldBe true
        CloudSyncPolicy.initialPage(20,3) shouldBe 20
        CloudSyncPolicy.initialPage(2,18) shouldBe 18
    }
    @Test fun `guest disabled signout and another account cannot send owner queue`() {
        CloudSyncPolicy.canSend("A","A",true) shouldBe true
        CloudSyncPolicy.canSend("A","B",true) shouldBe false
        CloudSyncPolicy.canSend("A",null,true) shouldBe false
        CloudSyncPolicy.canSend("A","A",false) shouldBe false
        CloudSyncPolicy.mergeHigherPage(true,false,12,0) shouldBe false // New remote explicit unread wins.
        CloudSyncPolicy.mergeHigherPage(false,false,12,3) shouldBe true
        CloudSyncPolicy.mergeHigherPage(false,true,12,3) shouldBe false
        CloudSyncPolicy.mergeHigherPage(false,false,3,12) shouldBe false
    }
    @Test fun `remote application across suspension cannot echo or award local action hooks`() = runTest {
        val changes=mutableListOf<LocalCloudChanges.Change>()
        LocalCloudChanges.capture={changes.add(it)}
        try {
            LocalCloudChanges.applyRemote {
                withContext(Dispatchers.Default) {LocalCloudChanges.changed(LocalCloudChanges.Kind.CHAPTER,42)}
                LocalCloudChanges.changed(LocalCloudChanges.Kind.MANGA,1)
            }
            changes.size shouldBe 0
            LocalCloudChanges.changed(LocalCloudChanges.Kind.CHAPTER,42)
            changes.size shouldBe 1
        } finally {LocalCloudChanges.capture=null}
    }
    @Test fun `unavailable sync journal never fails local reading mutation`() {
        LocalCloudChanges.capture={error("disk unavailable")}
        try {LocalCloudChanges.changed(LocalCloudChanges.Kind.CHAPTER,42)} finally {LocalCloudChanges.capture=null}
    }
}
