package eu.kanade.tachiyomi.data.community

import eu.kanade.tachiyomi.data.account.SupabaseAccountAuth
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
        var order = CommunityCommentOrder.NEWEST
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
        return requests.withPermit {
            if (parent == null && entry(target).order == CommunityCommentOrder.MOST_LIKED) {
                client.postgrest.rpc("community_comments_popular_page", JsonObject(args + ("p_before_likes" to (before?.like_count?.let(::JsonPrimitive) ?: JsonNull)))).decodeAs<Page>()
            } else client.postgrest.rpc("community_comments_page", args).decodeAs<Page>()
        }
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
        return CommunityComment(id, target, AccountAuthor(user_id, display_name, username, avatar, level, AccountRole.fromServer(role)), body,
            timestamp(created_at), timestamp(updated_at), like_count, reply_count,
            timestamp(updated_at) > timestamp(created_at), user_id == user, parent_comment_id, user != null && liked_by_me, spoiler)
    }
    private fun timestamp(value: String) = Instant.parse(value).toEpochMilliseconds()
    private fun clearPreviousAccount(item: Entry) {
        if (item.actor == actor()) return
        item.loadedAt = 0
        item.state.value = item.state.value.copy(
            rating = item.state.value.rating?.copy(currentUserRating = null),
            comments = item.state.value.comments.map { it.copy(isOwnedByCurrentUser = false, isLikedByCurrentUser = false) },
            replies = emptyMap(), replyCursors = emptyMap(), loadingReplies = emptySet(),
        )
    }
    private suspend fun reload(target: CommunityTarget, item: Entry, preserveReplies: Boolean = false) {
        val user = actor()
        clearPreviousAccount(item)
        item.state.value = item.state.value.copy(loading = item.state.value.rating == null,
            refreshing = item.state.value.rating != null, error = null)
        val (comments, summary) = coroutineScope {
            val p = async { page(target) }; val r = async { rating(target) }; p.await() to r.await()
        }
        // A response authenticated as the previous user must not supply ownership/likes to the next session.
        if (actor() != user) { item.loadedAt = 0; item.state.value = item.state.value.copy(loading = false, refreshing = false); return }
        item.state.value = CommunitySnapshot(
            order = item.order, rating = CommunityRatingSummary(summary.average, summary.count, summary.current_user_rating.takeIf { user != null }),
            commentCount = comments.comment_count, comments = comments.items.map { it.comment(target, user) },
            hasMore = comments.has_more, nextCursor = comments.cursor(),
            // A rating/top-level action must not erase a conversation already open in the UI.
            replies = if (preserveReplies) item.state.value.replies else emptyMap(),
            replyCursors = if (preserveReplies) item.state.value.replyCursors else emptyMap(),
        )
        item.actor = user
        item.loadedAt = System.currentTimeMillis()
    }
    private suspend fun read(target: CommunityTarget, action: suspend (Entry) -> Unit): CommunityOperation = withContext(Dispatchers.IO) {
        val item = entry(target)
        item.lock.withLock {
            clearPreviousAccount(item)
            try { action(item); CommunityOperation.Completed }
            catch (cancelled: CancellationException) {
                item.state.value = item.state.value.copy(loading = false, refreshing = false, loadingMore = false, loadingReplies = emptySet())
                throw cancelled
            } catch (failure: Exception) {
                val error = error(failure)
                item.state.value = item.state.value.copy(loading = false, refreshing = false, loadingMore = false,
                    loadingReplies = emptySet(), error = error)
                CommunityOperation.Failed(error)
            } finally {
                // Also discard personal flags when the account changes during a failed/late read.
                clearPreviousAccount(item)
            }
        }
    }
    override suspend fun loadInitial(target: CommunityTarget) = read(target) {
        if (it.loadedAt == 0L || it.actor != actor() || System.currentTimeMillis() - it.loadedAt >= 60_000) reload(target, it)
    }
    override suspend fun setCommentOrder(target: CommunityTarget, order: CommunityCommentOrder) = read(target) {
        if (it.order != order) {
            it.order = order
            it.loadedAt = 0
            it.state.value = it.state.value.copy(order = order, comments = emptyList(), nextCursor = null, hasMore = false)
            reload(target, it)
        }
    }
    override suspend fun commentById(target: CommunityTarget, id: String): CommunityComment? {
        val user = actor()
        val row = client.postgrest.rpc("community_comment_context", buildJsonObject { put("p_id", id); put("p_manga_key", target.mangaKey.value); put("p_chapter_key", target.chapterKey?.value) }).decodeAs<Row?>()
        return row?.takeIf { user == actor() }?.comment(target, user)
    }
    override suspend fun publicProfile(userId: String): CommunityProfileResult = withContext(Dispatchers.IO) {
        try {
            val id = java.util.UUID.fromString(userId).toString()
            val row = requests.withPermit {
                client.postgrest.rpc("community_public_profile", buildJsonObject { put("p_user_id", id) }).decodeAs<PublicProfile?>()
            } ?: return@withContext CommunityProfileResult.NotFound
            val version = "?v=" + timestamp(row.updated_at)
            val custom = row.avatar_path?.takeIf { it == "${row.user_id}/avatar.webp" }?.let { client.storage.from("avatars").publicUrl(it) + version }
            val google = row.google_avatar_url?.takeIf { it.startsWith("https://") }
            val cover = row.cover_path?.takeIf { it == "${row.user_id}/cover.webp" }?.let { client.storage.from("profile-media").publicUrl(it) + version }
            val safeFavorites = row.favorites.take(20).filter { it.manga_key.matches(Regex("[0-9a-f]{64}")) }
            val paths = safeFavorites.mapNotNull { it.cover_path?.takeIf { path -> path == "${row.user_id}/${it.manga_key}.webp" } }
            val covers = try {
                if (paths.isEmpty()) emptyMap() else client.storage.from("showcase-covers").createSignedUrls(kotlin.time.Duration.parse("2h"), paths)
                    .filter { it.error == null }.associate { it.path to it.signedURL }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { emptyMap() }
            CommunityProfileResult.Loaded(CommunityPublicProfile(
                AccountAuthor(row.user_id, row.display_name, row.username, custom ?: google, row.level, AccountRole.fromServer(row.role)),
                row.bio, cover, google, row.comment_count, row.rating_count, row.chapters_read,
                safeFavorites.map { PublicFavorite(it.manga_key, it.title, covers[it.cover_path], it.featured, isNovel = it.is_novel, chapterCount = it.chapter_count) }, row.showcase_enabled,
            ))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { CommunityProfileResult.Failed(error(failure)) }
    }
    override suspend fun refresh(target: CommunityTarget) = read(target) { reload(target, it) }
    override suspend fun getRatingSummary(target: CommunityTarget) = read(target) {
        val user = actor(); val summary = rating(target)
        if (user == actor()) {
            it.state.value = it.state.value.copy(rating = CommunityRatingSummary(summary.average, summary.count, summary.current_user_rating.takeIf { user != null }))
            it.actor = user
        }
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
    private suspend fun write(target: CommunityTarget, feature: AccountFeature, parent: String? = null, refreshProgression: Boolean = false, onConfirmed: (Entry) -> Unit = {}, action: suspend (String) -> CommunityEvent?): CommunityOperation {
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
                    val event = requests.withPermit {
                        if (actor() != user) throw SupabaseAccountAuth.SessionChangedException()
                        val auth = account.auth as? SupabaseAccountAuth
                        if (auth != null) auth.withSession(user) { action(user) } else action(user)
                    }
                    if (actor() == user) {
                        onConfirmed(item)
                        mihon.domain.sigils.SigilEvents.emit(mihon.domain.sigils.SigilEvents.Kind.COMMUNITY, 0, owner=user)
                        if (refreshProgression) try { account.auth.refreshProgression() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { /* Confirmed social action stays successful; next targeted refresh can retry. */ }
                    }
                    event?.let { emitted.tryEmit(it) }
                    // The mutation is server-confirmed; a subsequent read failure must not encourage duplicate posting.
                    try { reload(target, item, preserveReplies = true); if (parent != null) replies(target, item, parent, null) }
                    catch (cancelled: CancellationException) {
                        item.loadedAt = 0
                        item.state.value = item.state.value.copy(loading = false, refreshing = false, loadingMore = false, loadingReplies = emptySet())
                        throw cancelled
                    }
                    catch (failure: Exception) {
                        item.loadedAt = 0
                        item.state.value = item.state.value.copy(loading = false, refreshing = false, loadingReplies = emptySet(), error = error(failure))
                    }
                    CommunityOperation.Completed
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { CommunityOperation.Failed(error(failure)) }
                finally { clearPreviousAccount(item) }
            }
        }
    }
    override suspend fun rate(target: CommunityTarget, stars: Int): CommunityOperation {
        if (stars !in 1..5) return CommunityOperation.Failed(CommunityError(CommunityErrorKind.VALIDATION, CommunityValidationIssue.INVALID_RATING))
        return write(target, feature(target), onConfirmed = { item ->
            item.state.value = item.state.value.copy(rating = item.state.value.rating?.copy(currentUserRating = stars))
        }) {
            client.postgrest.rpc("community_set_rating", JsonObject(params(target) + ("p_rating" to JsonPrimitive(stars))))
            if (target.targetType == CommunityTargetType.MANGA) CommunityEvent.MangaRated(target, stars) else CommunityEvent.ChapterRated(target, stars)
        }
    }
    override suspend fun post(target: CommunityTarget, body: String, parentCommentId: String?, requestId: String, spoiler: Boolean): CommunityOperation {
        val input = CommunityCommentInput.validate(body)
        if (input is CommentValidation.Invalid) return CommunityOperation.Failed(CommunityError(CommunityErrorKind.VALIDATION, input.issue))
        return write(target, if (parentCommentId == null) AccountFeature.COMMENTS else AccountFeature.REPLIES, parentCommentId, refreshProgression = true) { user ->
            val id = java.util.UUID.fromString(requestId).toString()
            val validatedBody = (input as CommentValidation.Valid).body
            try {
                client.from("community_comments").insert(buildJsonObject {
                    put("id", id); put("spoiler", spoiler)
                    put("target_type", target.targetType.name.lowercase()); put("manga_key", target.mangaKey.value)
                    put("chapter_key", target.chapterKey?.value); put("user_id", user)
                    put("parent_comment_id", parentCommentId); put("body", validatedBody)
                }) { select() }.decodeSingle<Identity>()
            } catch (duplicate: PostgrestRestException) {
                if (duplicate.code != "23505") throw duplicate
                // The same saved request ID may have committed before its response was lost.
                // Confirm full ownership/context/body; never upsert or overwrite someone else's ID.
                val existing = client.from("community_comments").select {
                    filter { eq("id", id); eq("user_id", user) }
                }.decodeSingle<PostedComment>()
                check(existing.id == id && existing.user_id == user && existing.target_type == target.targetType.name.lowercase() &&
                    existing.manga_key == target.mangaKey.value && existing.chapter_key == target.chapterKey?.value &&
                    existing.parent_comment_id == parentCommentId && existing.body == validatedBody && existing.spoiler == spoiler)
            }
            if (parentCommentId == null) CommunityEvent.CommentCreated(target, id) else CommunityEvent.ReplyCreated(target, id, parentCommentId)
        }
    }
    override suspend fun setLiked(comment: CommunityComment, liked: Boolean) = write(comment.target, AccountFeature.REACTIONS, comment.parentCommentId,
        onConfirmed = { item -> updateComment(item, comment.id) {
            it.copy(isLikedByCurrentUser = liked, likeCount = (it.likeCount + if (it.isLikedByCurrentUser == liked) 0 else if (liked) 1 else -1).coerceAtLeast(0))
        } }) {
        client.postgrest.rpc("community_like_comment", buildJsonObject { put("p_comment_id", comment.id); put("p_liked", liked) }); null
    }
    override suspend fun report(comment: CommunityComment) = write(comment.target, AccountFeature.COMMENTS, comment.parentCommentId) {
        client.postgrest.rpc("community_report_comment", buildJsonObject { put("p_comment_id", comment.id) }); null
    }
    override suspend fun editOwned(comment: CommunityComment, body: String, spoiler: Boolean): CommunityOperation {
        val input = CommunityCommentInput.validate(body)
        if (input is CommentValidation.Invalid) return CommunityOperation.Failed(CommunityError(CommunityErrorKind.VALIDATION, input.issue))
        return write(comment.target, AccountFeature.COMMENTS, comment.parentCommentId) { user ->
            client.from("community_comments").update(buildJsonObject { put("body", (input as CommentValidation.Valid).body); put("spoiler", spoiler) }) {
                filter { eq("id", comment.id); eq("user_id", user) }; select()
            }.decodeSingle<EditedComment>().also { edited ->
                updateComment(entry(comment.target), edited.id) {it.copy(body = edited.body, spoiler = edited.spoiler, updatedAt = timestamp(edited.updated_at), isEdited = true)}
            }; null
        }
    }
    override suspend fun deleteOwned(comment: CommunityComment) = write(comment.target, AccountFeature.COMMENTS, comment.parentCommentId, refreshProgression = true,
        onConfirmed = { item ->
            item.state.value = item.state.value.copy(comments = item.state.value.comments.filterNot {it.id == comment.id},
                replies = (item.state.value.replies - comment.id).mapValues { (_, rows) -> rows.filterNot {it.id == comment.id} },
                replyCursors = item.state.value.replyCursors - comment.id, commentCount = null)
        }) { user ->
        check(comment.userId == user)
        // An empty result is also success for a retry of this owner's already-deleted comment.
        client.from("community_comments").delete { filter { eq("id", comment.id); eq("user_id", user) }; select() }.decodeList<Identity>(); null
    }
    private fun updateComment(item: Entry, id: String, transform: (CommunityComment) -> CommunityComment) {
        item.state.value = item.state.value.copy(comments = item.state.value.comments.map {if(it.id == id) transform(it) else it},
            replies = item.state.value.replies.mapValues { (_, rows) -> rows.map {if(it.id == id) transform(it) else it} })
    }
    private fun error(failure: Exception): CommunityError {
        val code = (failure as? PostgrestRestException)?.code
        val status = (failure as? RestException)?.statusCode
        val kind = when {
            failure is SupabaseAccountAuth.SessionChangedException || status == 401 -> CommunityErrorKind.AUTH_REQUIRED
            status == 429 -> CommunityErrorKind.RATE_LIMITED
            code in setOf("23514", "22023", "23503", "23505") -> CommunityErrorKind.VALIDATION
            status == 403 || code == "42501" -> CommunityErrorKind.PERMISSION_DENIED
            status != null && status >= 500 -> CommunityErrorKind.BACKEND_UNAVAILABLE
            failure is java.io.IOException -> CommunityErrorKind.NETWORK
            else -> CommunityErrorKind.UNKNOWN
        }
        return CommunityError(kind)
    }
    @Serializable private data class PublicProfile(val user_id: String, val display_name: String?, val username: String?,
        val avatar_path: String?, val google_avatar_url: String?, val cover_path: String?, val bio: String?,
        val updated_at: String, val level: Int, val comment_count: Long, val rating_count: Long,
        val role: String = "user", val chapters_read: Long? = null, val favorites: List<FavoriteRow> = emptyList(), val showcase_enabled: Boolean = false)
    @Serializable private data class FavoriteRow(val manga_key: String, val title: String, val cover_path: String? = null, val featured: Boolean = false, val is_novel: Boolean = false, val chapter_count: Int? = null)
    @Serializable private data class PostedComment(val id: String, val user_id: String, val target_type: String, val manga_key: String,
        val chapter_key: String?, val parent_comment_id: String?, val body: String, val spoiler: Boolean = false)
    @Serializable private data class EditedComment(val id: String, val body: String, val updated_at: String, val spoiler: Boolean = false)
    @Serializable private data class Identity(val id: String)
    @Serializable private data class Cursor(val created_at: String, val id: String, val like_count: Long? = null)
    @Serializable private data class Rating(val average: Double?, val count: Long, val current_user_rating: Int?)
    @Serializable private data class Page(val items: List<Row>, val has_more: Boolean, val next_cursor: Cursor?, val comment_count: Long)
    @Serializable private data class Row(
        val id: String, val user_id: String, val body: String, val created_at: String, val updated_at: String,
        val parent_comment_id: String?, val display_name: String?, val username: String?, val avatar_path: String?,
        val google_avatar_url: String?, val author_updated_at: String?, val like_count: Long, val reply_count: Long, val liked_by_me: Boolean, val level: Int = 1, val spoiler: Boolean = false, val role: String = "user",
    )
}
