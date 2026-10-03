package eu.kanade.tachiyomi.data.community

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import mihon.domain.account.*
import mihon.domain.community.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Instant

/** One cloud adapter, using the Account-owned client. No local reading/storage dependencies. */
class SupabaseCommunityRepository(private val client: SupabaseClient, private val account: AccountFoundation) : CommunityRepository {
    private class Entry {
        val state = MutableStateFlow(CommunitySnapshot())
        val lock = Mutex()
        var loadedAt = 0L
        var actor: String? = null
    }
    private val entries = ConcurrentHashMap<CommunityTarget, Entry>()
    private val requests = Semaphore(3)
    private val emitted = MutableSharedFlow<CommunityEvent>(extraBufferCapacity = 16)
    override val events = emitted.asSharedFlow()
    override val available = true
    private val json = Json { ignoreUnknownKeys = true }
    private fun entry(target: CommunityTarget) = entries.getOrPut(target) { Entry() }
    private fun actor() = (account.session.value as? AccountSession.Authenticated)?.profile?.userId
    override fun observe(target: CommunityTarget) = entry(target).state.asStateFlow()

    private fun params(target: CommunityTarget) = buildJsonObject {
        put("p_target_type", target.targetType.name.lowercase())
        put("p_manga_key", target.mangaKey.value)
        put("p_chapter_key", target.chapterKey?.value)
    }
    private suspend fun page(target: CommunityTarget, cursor: CommunityCursor? = null, parent: String? = null): Page {
        val before = cursor?.let { json.decodeFromString<Cursor>(it.value) }
        val args = JsonObject(params(target) + buildJsonObject {
            put("p_before_created", before?.created_at); put("p_before_id", before?.id)
            put("p_parent_id", parent); put("p_limit", 20)
        })
        return requests.withPermit { client.postgrest.rpc("community_comments_page", args).decodeAs<Page>() }
    }
    private suspend fun rating(target: CommunityTarget): Rating = requests.withPermit {
        client.postgrest.rpc("community_rating_summary", params(target)).decodeAs<Rating>()
    }
    private fun Page.cursor() = next_cursor?.let { CommunityCursor(json.encodeToString(it)) }
    private fun Row.comment(target: CommunityTarget, user: String?): CommunityComment {
        val custom = avatar_path?.takeIf { it == "$user_id/avatar.webp" }?.let {
            client.storage.from("avatars").publicUrl(it) + "?v=" + (author_updated_at?.let(::timestamp) ?: 0)
        }
        val avatar = custom ?: google_avatar_url?.takeIf { it.startsWith("https://") }
        return CommunityComment(id, target, AccountAuthor(user_id, display_name, username, avatar, level), body,
            timestamp(created_at), timestamp(updated_at), like_count, reply_count,
            timestamp(updated_at) > timestamp(created_at), user_id == user, parent_comment_id, liked_by_me)
    }
    private fun timestamp(value: String) = Instant.parse(value).toEpochMilliseconds()
    private suspend fun reload(target: CommunityTarget, item: Entry) {
        val user = actor()
        if (item.actor != user) item.state.value = item.state.value.copy(
            rating = item.state.value.rating?.copy(currentUserRating = null),
            comments = item.state.value.comments.map { it.copy(isOwnedByCurrentUser = false, isLikedByCurrentUser = false) },
            replies = emptyMap(), replyCursors = emptyMap(),
        )
        item.state.value = item.state.value.copy(loading = item.state.value.rating == null,
            refreshing = item.state.value.rating != null, error = null)
        val (comments, summary) = coroutineScope {
            val p = async { page(target) }; val r = async { rating(target) }; p.await() to r.await()
        }
        // A response authenticated as the previous user must not supply ownership/likes to the next session.
        if (actor() != user) { item.loadedAt = 0; item.state.value = item.state.value.copy(loading = false, refreshing = false); return }
        item.state.value = CommunitySnapshot(
            rating = CommunityRatingSummary(summary.average, summary.count, summary.current_user_rating),
            commentCount = comments.comment_count, comments = comments.items.map { it.comment(target, user) },
            hasMore = comments.has_more, nextCursor = comments.cursor(),
        )
        item.actor = user
        item.loadedAt = System.currentTimeMillis()
    }
    private suspend fun read(target: CommunityTarget, action: suspend (Entry) -> Unit): CommunityOperation = withContext(Dispatchers.IO) {
        val item = entry(target)
        item.lock.withLock {
            try { action(item); CommunityOperation.Completed }
            catch (cancelled: CancellationException) {
                item.state.value = item.state.value.copy(loading = false, refreshing = false, loadingMore = false, loadingReplies = emptySet())
                throw cancelled
            } catch (failure: Exception) {
                val error = error(failure)
                item.state.value = item.state.value.copy(loading = false, refreshing = false, loadingMore = false,
                    loadingReplies = emptySet(), error = error)
                CommunityOperation.Failed(error)
            }
        }
    }
    override suspend fun loadInitial(target: CommunityTarget) = read(target) {
        if (it.loadedAt == 0L || it.actor != actor() || System.currentTimeMillis() - it.loadedAt >= 60_000) reload(target, it)
    }
    override suspend fun refresh(target: CommunityTarget) = read(target) { reload(target, it) }
    override suspend fun getRatingSummary(target: CommunityTarget) = read(target) {
        val user = actor(); val summary = rating(target)
        if (user == actor()) it.state.value = it.state.value.copy(rating = CommunityRatingSummary(summary.average, summary.count, summary.current_user_rating))
    }
    override suspend fun loadMore(target: CommunityTarget, cursor: CommunityCursor) = read(target) {
        if (it.actor != actor()) { reload(target, it); return@read }
        if (it.state.value.nextCursor != cursor) return@read
        it.state.value = it.state.value.copy(loadingMore = true, error = null)
        val user = actor(); val p = page(target, cursor)
        if (user != actor()) { it.loadedAt = 0; it.state.value = it.state.value.copy(loadingMore = false); return@read }
        it.state.value = it.state.value.copy(comments = (it.state.value.comments + p.items.map { row -> row.comment(target, user) }).distinctBy { row -> row.id },
            hasMore = p.has_more, nextCursor = p.cursor(), commentCount = p.comment_count, loadingMore = false)
    }
    private suspend fun replies(target: CommunityTarget, item: Entry, parentId: String, cursor: CommunityCursor?) {
        item.state.value = item.state.value.copy(loadingReplies = item.state.value.loadingReplies + parentId, error = null)
        val user = actor(); val p = page(target, cursor, parentId)
        if (user != actor()) { item.loadedAt = 0; item.state.value = item.state.value.copy(loadingReplies = emptySet()); return }
        val old = if (cursor == null) emptyList() else item.state.value.replies[parentId].orEmpty()
        val cursors = item.state.value.replyCursors - parentId + (p.cursor()?.let { mapOf(parentId to it) } ?: emptyMap())
        item.state.value = item.state.value.copy(replies = item.state.value.replies + (parentId to (old + p.items.map { it.comment(target, user) }).distinctBy { it.id }),
            replyCursors = cursors, loadingReplies = item.state.value.loadingReplies - parentId)
    }
    override suspend fun loadReplies(target: CommunityTarget, parentId: String, cursor: CommunityCursor?) = read(target) {
        if (it.actor != actor()) reload(target, it)
        if (cursor != null && it.state.value.replyCursors[parentId] != cursor) return@read
        replies(target, it, parentId, cursor)
    }
    private fun feature(target: CommunityTarget) = if (target.targetType == CommunityTargetType.MANGA) AccountFeature.MANGA_RATINGS else AccountFeature.CHAPTER_RATINGS
    private suspend fun write(target: CommunityTarget, feature: AccountFeature, parent: String? = null, refreshProgression: Boolean = false, action: suspend (String) -> CommunityEvent?): CommunityOperation {
        val access = account.featureGate.access(feature)
        val user = when (access) {
            is AccountAccess.Allowed -> access.userId
            is AccountAccess.ProfileRequired -> return CommunityOperation.Failed(CommunityError(CommunityErrorKind.PROFILE_INCOMPLETE))
            else -> return CommunityOperation.Failed(CommunityError(CommunityErrorKind.AUTH_REQUIRED))
        }
        return withContext(Dispatchers.IO) {
            val item = entry(target)
            item.lock.withLock {
                try {
                    val event = requests.withPermit { action(user) }
                    if (refreshProgression) account.auth.refreshProgression()
                    event?.let { emitted.tryEmit(it) }
                    // The mutation is server-confirmed; a subsequent read failure must not encourage duplicate posting.
                    try { reload(target, item); if (parent != null) replies(target, item, parent, null) }
                    catch (cancelled: CancellationException) { item.loadedAt = 0; throw cancelled }
                    catch (failure: Exception) {
                        item.loadedAt = 0
                        item.state.value = item.state.value.copy(loading = false, refreshing = false, loadingReplies = emptySet(), error = error(failure))
                    }
                    CommunityOperation.Completed
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { CommunityOperation.Failed(error(failure)) }
            }
        }
    }
    override suspend fun rate(target: CommunityTarget, stars: Int): CommunityOperation {
        if (stars !in 1..5) return CommunityOperation.Failed(CommunityError(CommunityErrorKind.VALIDATION, CommunityValidationIssue.INVALID_RATING))
        return write(target, feature(target)) {
            client.postgrest.rpc("community_set_rating", JsonObject(params(target) + ("p_rating" to JsonPrimitive(stars))))
            if (target.targetType == CommunityTargetType.MANGA) CommunityEvent.MangaRated(target, stars) else CommunityEvent.ChapterRated(target, stars)
        }
    }
    override suspend fun post(target: CommunityTarget, body: String, parentCommentId: String?): CommunityOperation {
        val input = CommunityCommentInput.validate(body)
        if (input is CommentValidation.Invalid) return CommunityOperation.Failed(CommunityError(CommunityErrorKind.VALIDATION, input.issue))
        return write(target, if (parentCommentId == null) AccountFeature.COMMENTS else AccountFeature.REPLIES, parentCommentId, refreshProgression = true) { user ->
            val id = client.from("community_comments").insert(buildJsonObject {
                put("target_type", target.targetType.name.lowercase()); put("manga_key", target.mangaKey.value)
                put("chapter_key", target.chapterKey?.value); put("user_id", user)
                put("parent_comment_id", parentCommentId); put("body", (input as CommentValidation.Valid).body)
            }) { select() }.decodeSingle<Identity>().id
            if (parentCommentId == null) CommunityEvent.CommentCreated(target, id) else CommunityEvent.ReplyCreated(target, id, parentCommentId)
        }
    }
    override suspend fun setLiked(comment: CommunityComment, liked: Boolean) = write(comment.target, AccountFeature.REACTIONS, comment.parentCommentId) {
        client.postgrest.rpc("community_like_comment", buildJsonObject { put("p_comment_id", comment.id); put("p_liked", liked) }); null
    }
    override suspend fun report(comment: CommunityComment) = write(comment.target, AccountFeature.COMMENTS, comment.parentCommentId) {
        client.postgrest.rpc("community_report_comment", buildJsonObject { put("p_comment_id", comment.id) }); null
    }
    override suspend fun editOwned(comment: CommunityComment, body: String): CommunityOperation {
        val input = CommunityCommentInput.validate(body)
        if (input is CommentValidation.Invalid) return CommunityOperation.Failed(CommunityError(CommunityErrorKind.VALIDATION, input.issue))
        return write(comment.target, AccountFeature.COMMENTS, comment.parentCommentId) { user ->
            client.from("community_comments").update(buildJsonObject { put("body", (input as CommentValidation.Valid).body) }) {
                filter { eq("id", comment.id); eq("user_id", user) }; select()
            }.decodeSingle<Identity>(); null
        }
    }
    override suspend fun deleteOwned(comment: CommunityComment) = write(comment.target, AccountFeature.COMMENTS, comment.parentCommentId, refreshProgression = true) { user ->
        client.from("community_comments").delete { filter { eq("id", comment.id); eq("user_id", user) }; select() }.decodeSingle<Identity>(); null
    }
    private fun error(failure: Exception): CommunityError {
        val code = (failure as? PostgrestRestException)?.code
        val status = (failure as? RestException)?.statusCode
        val kind = when {
            status == 401 -> CommunityErrorKind.AUTH_REQUIRED
            status == 429 -> CommunityErrorKind.RATE_LIMITED
            code in setOf("23514", "22023", "23503", "23505") -> CommunityErrorKind.VALIDATION
            status == 403 || code == "42501" -> CommunityErrorKind.PERMISSION_DENIED
            status != null && status >= 500 -> CommunityErrorKind.BACKEND_UNAVAILABLE
            failure is java.io.IOException -> CommunityErrorKind.NETWORK
            else -> CommunityErrorKind.UNKNOWN
        }
        return CommunityError(kind)
    }
    @Serializable private data class Identity(val id: String)
    @Serializable private data class Cursor(val created_at: String, val id: String)
    @Serializable private data class Rating(val average: Double?, val count: Long, val current_user_rating: Int?)
    @Serializable private data class Page(val items: List<Row>, val has_more: Boolean, val next_cursor: Cursor?, val comment_count: Long)
    @Serializable private data class Row(
        val id: String, val user_id: String, val body: String, val created_at: String, val updated_at: String,
        val parent_comment_id: String?, val display_name: String?, val username: String?, val avatar_path: String?,
        val google_avatar_url: String?, val author_updated_at: String?, val like_count: Long, val reply_count: Long, val liked_by_me: Boolean, val level: Int = 1,
    )
}
