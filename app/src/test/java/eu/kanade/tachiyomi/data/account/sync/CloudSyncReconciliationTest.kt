package eu.kanade.tachiyomi.data.account.sync

import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test

class CloudSyncReconciliationTest {
    @Test fun `initial progress seed respects a newer explicit cloud reset`() {
        val sync=SupabaseCloudSync(mockk(),mockk(),mockk(),mockk(),mockk())
        val local=buildJsonObject {put("last_page_index",20);put("is_read",true);put("state_changed_at",stamp(1_700_000_000_000))}
        val remote=buildJsonObject {put("last_page_index",0);put("is_read",false);put("state_changed_at",stamp(1_700_000_100_000))}
        sync.initialBody("cloud_chapter_progress",local,remote) shouldBe remote
        sync.initialBody("cloud_chapter_progress",remote,local) shouldBe remote
    }
    @Test fun `old device initial seed cannot recreate a deleted cloud library item`() {
        val sync=SupabaseCloudSync(mockk(),mockk(),mockk(),mockk(),mockk())
        val guest=buildJsonObject {put("manga_key","work");put("deleted_at",JsonNull)}
        val remote=JsonObject(guest+mapOf("deleted_at" to JsonPrimitive("2026-10-15T00:00:00Z"),"revision" to JsonPrimitive(12),"user_id" to JsonPrimitive("A")))
        sync.initialBody("cloud_library_entries",guest,remote)["deleted_at"] shouldBe remote["deleted_at"]
        sync.initialBody("cloud_library_entries",guest,null) shouldBe guest
    }
}
