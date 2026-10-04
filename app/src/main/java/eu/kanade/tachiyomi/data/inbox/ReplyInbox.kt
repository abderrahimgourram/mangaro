package eu.kanade.tachiyomi.data.inbox

import eu.kanade.tachiyomi.data.account.SupabaseAccountAuth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import mihon.domain.community.*
import kotlin.time.Instant

/** Own inbox only, using the existing app client and serialized Account identity boundary. */
class ReplyInbox(private val auth: SupabaseAccountAuth?) {
    private val counts = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Long>>(emptyMap())
    val unreadCounts = counts.asStateFlow()

    suspend fun unread(user: String): Long = request {
        val count = auth?.withSession(user) { auth.communityClient.postgrest.rpc("reply_inbox_unread_count").decodeAs<Long>() } ?: 0
        counts.update { it + (user to count) }
        count
    }
    suspend fun page(user: String, cursor: ReplyNotice? = null): ReplyPage = request {
        val backend = auth ?: error("Unavailable")
        backend.withSession(user) {
            val response = backend.communityClient.postgrest.rpc("reply_inbox_page", buildJsonObject {
                put("p_before_created", cursor?.createdAt); put("p_before_id", cursor?.id)
            }).decodeAs<Page>()
            ReplyPage(response.items.map { row ->
                val custom = row.avatar_path?.takeIf { it == "${row.actor_id}/avatar.webp" }?.let {
                    backend.communityClient.storage.from("avatars").publicUrl(it) + "?v=" + row.author_updated_at.orEmpty()
                }
                val manga = CommunityMangaKey.fromOpaque(row.manga_key)
                val chapter = row.chapter_key?.let { CommunityChapterKey.fromOpaque(manga, it) }
                ReplyNotice(row.id, row.comment_id, row.created_at, row.read_at, row.display_name?.takeIf { it.isNotBlank() } ?: row.username?.takeIf { it.isNotBlank() } ?: "قارئ",
                    custom ?: row.google_avatar_url?.takeIf { it.startsWith("https://") },
                    if (row.spoiler) "رد يحتوي على حرق" else row.preview.orEmpty(),
                    CommunityTarget(if (chapter == null) CommunityTargetType.MANGA else CommunityTargetType.CHAPTER, manga, chapter),
                    row.google_avatar_url?.takeIf { it.startsWith("https://") })
            }, response.has_more)
        }
    }
    suspend fun markRead(user: String, id: String? = null) = request {
        val backend = auth ?: error("Unavailable")
        backend.withSession(user) {
            backend.communityClient.from("community_reply_notifications").update(buildJsonObject { put("read_at", Instant.fromEpochMilliseconds(System.currentTimeMillis()).toString()) }) {
                filter { eq("recipient_user_id", user); if (id != null) eq("id", id) }
            }
            // A confirmed read mutation remains successful if its optional count refresh fails.
            try {
                val count = backend.communityClient.postgrest.rpc("reply_inbox_unread_count").decodeAs<Long>()
                counts.update { it + (user to count) }
            } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (_: Exception) { }
        }
    }
    private suspend fun <T> request(action: suspend () -> T): T = try {
        withTimeout(15_000) { action() }
    } catch (_: TimeoutCancellationException) {
        // A bounded backend timeout is a recoverable failure, not lifecycle cancellation.
        throw IllegalStateException("Inbox unavailable")
    }
    @Serializable private data class Page(val items: List<Row>, val has_more: Boolean)
    @Serializable private data class Row(val id: String, val comment_id: String, val created_at: String, val read_at: String?,
        val manga_key: String, val chapter_key: String?, val actor_id: String?, val display_name: String?, val username: String?,
        val avatar_path: String?, val google_avatar_url: String?, val author_updated_at: String?, val spoiler: Boolean, val preview: String?)
}
data class ReplyPage(val items: List<ReplyNotice>, val hasMore: Boolean)
data class ReplyNotice(val id: String, val parentId: String, val createdAt: String, val readAt: String?,
    val author: String, val avatar: String?, val preview: String, val target: CommunityTarget, val googleAvatar: String? = null)
