package mihon.domain.community

import java.io.Serializable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import mihon.domain.account.AccountAuthor
import java.net.URI
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale

enum class CommunityTargetType { MANGA, CHAPTER }
/** Versioned opaque backend keys; source identifiers never become visible UI metadata. */
data class CommunityMangaKey private constructor(val value: String) : Serializable {
    companion object {
        fun fromOpaque(value: String): CommunityMangaKey {
            require(value.matches(Regex("[0-9a-f]{64}")))
            return CommunityMangaKey(value)
        }
        fun fromSource(sourceId: Long, mangaUrl: String, stableSourceId: String? = null): CommunityMangaKey {
            val identity = stableSourceId?.takeIf { it.isNotBlank() }
            return CommunityMangaKey(communityHash("manga-v1", sourceId.toString(),
                if (identity == null) "url" else "remote-id", identity ?: normalizeCommunityUrl(mangaUrl)))
        }
    }
}
data class CommunityChapterKey private constructor(
    val value: String,
    val mangaKey: CommunityMangaKey,
) : Serializable {
    companion object {
        fun fromOpaque(mangaKey: CommunityMangaKey, value: String): CommunityChapterKey {
            require(value.matches(Regex("[0-9a-f]{64}")))
            return CommunityChapterKey(value, mangaKey)
        }
        fun fromSource(mangaKey: CommunityMangaKey, chapterUrl: String, stableSourceId: String? = null): CommunityChapterKey {
            val identity = stableSourceId?.takeIf { it.isNotBlank() }
            return CommunityChapterKey(communityHash("chapter-v1", mangaKey.value,
                if (identity == null) "url" else "remote-id", identity ?: normalizeCommunityUrl(chapterUrl)), mangaKey)
        }
    }
}

/** Preserve path case, queries, fragments, encoding and trailing slashes; no guessed aliases. */
private fun normalizeCommunityUrl(input: String): String {
    val value = input.trim()
    require(value.isNotEmpty())
    val uri = try { URI(value) } catch (_: java.net.URISyntaxException) { return value }
    if (!uri.isAbsolute || uri.rawAuthority == null || uri.host == null) return value
    val authority = uri.rawAuthority
    val hostStart = authority.lastIndexOf('@') + 1
    val normalizedAuthority = authority.substring(0, hostStart) + authority.substring(hostStart).lowercase(Locale.ROOT)
    val authorityStart = uri.scheme.length + 3
    return uri.scheme.lowercase(Locale.ROOT) + "://" + normalizedAuthority + value.substring(authorityStart + authority.length)
}

// Length-prefixed UTF-8 fields avoid ambiguous concatenation. Namespaces/version prevent type collisions.
private fun communityHash(namespace: String, vararg parts: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for (part in listOf(namespace) + parts) {
        val bytes = part.toByteArray(Charsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }
    val hex = "0123456789abcdef"
    return digest.digest().joinToString("") { byte ->
        val value = byte.toInt() and 255
        "${hex[value shr 4]}${hex[value and 15]}"
    }
}

/** Chapter keys are always scoped to their manga and its source, never to chapter number. */
data class CommunityTarget(
    val targetType: CommunityTargetType,
    val mangaKey: CommunityMangaKey,
    val chapterKey: CommunityChapterKey? = null,
) : Serializable {
    init {
        require((targetType == CommunityTargetType.CHAPTER) == (chapterKey != null))
        require(chapterKey == null || chapterKey.mangaKey == mangaKey)
    }
    // A future backend may explicitly map source-scoped keys to canonical works; never by title here.
}

data class CommunityComment(
    val id: String,
    val target: CommunityTarget,
    val author: AccountAuthor,
    val body: String,
    val createdAt: Long,
    val updatedAt: Long?,
    val likeCount: Long,
    val replyCount: Long,
    val isEdited: Boolean,
    val isOwnedByCurrentUser: Boolean,
    val parentCommentId: String? = null,
    val isLikedByCurrentUser: Boolean = false,
    val spoiler: Boolean = false,
) {
    fun visibleBody(revealed: Boolean = false): String? = body.takeIf { !spoiler || revealed }

    val targetType get() = target.targetType
    val mangaKey get() = target.mangaKey
    val chapterKey get() = target.chapterKey
    val userId get() = author.userId
    val displayName get() = author.displayName ?: author.username.orEmpty()
    val username get() = author.username
    val avatarUrl get() = author.avatarUrl
    val level get() = author.level
    val rankTitle get() = author.rankTitle
}

enum class CommunityCommentOrder { NEWEST, MOST_LIKED }
/** Public profile contract reuses the canonical author/rank model and cannot contain private account data. */
data class CommunityPublicProfile(
    val author: AccountAuthor,
    val bio: String?,
    val coverUrl: String?,
    val googleAvatarUrl: String?,
    val commentCount: Long,
    val ratingCount: Long,
)
sealed interface CommunityProfileResult {
    data class Loaded(val profile: CommunityPublicProfile) : CommunityProfileResult
    data object NotFound : CommunityProfileResult
    data class Failed(val error: CommunityError) : CommunityProfileResult
}

data class CommunityRatingSummary(val average: Double?, val count: Long, val currentUserRating: Int?) {
    init {
        require(count >= 0)
        require(if (count == 0L) average == null else average != null && average in 1.0..5.0)
        require(currentUserRating == null || currentUserRating in 1..5)
    }
}
/** Null totals mean unknown/unconnected, not fabricated zero activity. */
data class CommunitySnapshot(
    val order: CommunityCommentOrder = CommunityCommentOrder.NEWEST,
    val rating: CommunityRatingSummary? = null,
    val commentCount: Long? = null,
    val comments: List<CommunityComment> = emptyList(),
    val loading: Boolean = false,
    val error: CommunityError? = null,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val nextCursor: CommunityCursor? = null,
    val replies: Map<String, List<CommunityComment>> = emptyMap(),
    val replyCursors: Map<String, CommunityCursor> = emptyMap(),
    val loadingReplies: Set<String> = emptySet(),
) {
    init { require(!hasMore || nextCursor != null) }
}

/** Opaque server cursor, not a local chapter/manga row ID or client guessed offset. */
data class CommunityCursor(val value: String) { init { require(value.isNotBlank()) } }
enum class CommunityErrorKind { AUTH_REQUIRED, PROFILE_INCOMPLETE, VALIDATION, RATE_LIMITED, PERMISSION_DENIED, NETWORK, BACKEND_UNAVAILABLE, UNKNOWN }
enum class CommunityValidationIssue { BLANK_COMMENT, COMMENT_TOO_LONG, INVALID_RATING }
data class CommunityError(
    val kind: CommunityErrorKind,
    val validation: CommunityValidationIssue? = null,
    val retryAfterMillis: Long? = null,
) {
    val userMessage: String get() = when (kind) {
        CommunityErrorKind.AUTH_REQUIRED -> "سجّل دخولك للمشاركة"
        CommunityErrorKind.PROFILE_INCOMPLETE -> "أكمل اسم المستخدم للمشاركة"
        CommunityErrorKind.VALIDATION -> when (validation) {
            CommunityValidationIssue.BLANK_COMMENT -> "اكتب تعليقًا أولًا"
            CommunityValidationIssue.COMMENT_TOO_LONG -> "الحد الأقصى للتعليق ${CommunityCommentInput.MAX_LENGTH} حرف"
            CommunityValidationIssue.INVALID_RATING -> "اختر تقييمًا من 1 إلى 5"
            null -> "تحقق من البيانات المدخلة"
        }
        CommunityErrorKind.RATE_LIMITED -> "يرجى الانتظار قبل المحاولة مجددًا"
        CommunityErrorKind.PERMISSION_DENIED -> "لا تملك صلاحية تنفيذ هذا الإجراء"
        CommunityErrorKind.NETWORK -> "تعذّر الاتصال، حاول مجددًا"
        CommunityErrorKind.BACKEND_UNAVAILABLE -> "المشاركة ستتوفر لاحقًا"
        CommunityErrorKind.UNKNOWN -> "تعذّر تنفيذ الإجراء، حاول مجددًا"
    }
}
sealed interface CommentValidation {
    data class Valid(val body: String) : CommentValidation
    data class Invalid(val issue: CommunityValidationIssue) : CommentValidation
}
object CommunityCommentInput {
    const val MAX_LENGTH = 2000
    fun validate(input: String): CommentValidation {
        val body = input.trim()
        return when {
            body.isBlank() -> CommentValidation.Invalid(CommunityValidationIssue.BLANK_COMMENT)
            body.length > MAX_LENGTH -> CommentValidation.Invalid(CommunityValidationIssue.COMMENT_TOO_LONG)
            else -> CommentValidation.Valid(body)
        }
    }
}
sealed interface CommunityOperation {
    data object NotConfigured : CommunityOperation
    data object Completed : CommunityOperation
    data class Failed(val error: CommunityError) : CommunityOperation
}
/** Emit only after backend confirmation. These events never award or increment XP on Android. */
sealed interface CommunityEvent {
    data class CommentCreated(val target: CommunityTarget, val commentId: String) : CommunityEvent
    data class ReplyCreated(val target: CommunityTarget, val commentId: String, val parentId: String) : CommunityEvent
    data class MangaRated(val target: CommunityTarget, val stars: Int) : CommunityEvent
    data class ChapterRated(val target: CommunityTarget, val stars: Int) : CommunityEvent
}
interface CommunityRepository {
    suspend fun commentById(target: CommunityTarget, id: String): CommunityComment? = null
    val available: Boolean
    val events: Flow<CommunityEvent>
    fun observe(target: CommunityTarget): StateFlow<CommunitySnapshot>
    // Read operations publish flags/results/errors through observe(target), in bounded cursor pages.
    // loadInitial is idempotent/cache-aware; refresh forces a reload without discarding usable cached data.
    // Implementations coalesce in-flight requests and deduplicate pages by comment ID.
    suspend fun loadInitial(target: CommunityTarget): CommunityOperation
    suspend fun setCommentOrder(target: CommunityTarget, order: CommunityCommentOrder): CommunityOperation
    suspend fun publicProfile(userId: String): CommunityProfileResult
    suspend fun refresh(target: CommunityTarget): CommunityOperation
    suspend fun loadMore(target: CommunityTarget, cursor: CommunityCursor): CommunityOperation
    suspend fun loadReplies(target: CommunityTarget, parentId: String, cursor: CommunityCursor? = null): CommunityOperation
    suspend fun getRatingSummary(target: CommunityTarget): CommunityOperation
    // Future implementation upserts one rating per authenticated user + target.
    suspend fun rate(target: CommunityTarget, stars: Int): CommunityOperation
    suspend fun post(target: CommunityTarget, body: String, parentCommentId: String? = null, requestId: String = java.util.UUID.randomUUID().toString(), spoiler: Boolean = false): CommunityOperation
    suspend fun setLiked(comment: CommunityComment, liked: Boolean): CommunityOperation
    suspend fun report(comment: CommunityComment): CommunityOperation
    suspend fun editOwned(comment: CommunityComment, body: String, spoiler: Boolean = comment.spoiler): CommunityOperation
    suspend fun deleteOwned(comment: CommunityComment): CommunityOperation
}
class DisabledCommunityRepository : CommunityRepository {
    private val empty = MutableStateFlow(CommunitySnapshot()).asStateFlow()
    override val available = false
    override val events: Flow<CommunityEvent> = emptyFlow()
    override fun observe(target: CommunityTarget) = empty
    override suspend fun loadInitial(target: CommunityTarget) = CommunityOperation.NotConfigured
    override suspend fun setCommentOrder(target: CommunityTarget, order: CommunityCommentOrder) = CommunityOperation.NotConfigured
    override suspend fun publicProfile(userId: String) = CommunityProfileResult.Failed(CommunityError(CommunityErrorKind.BACKEND_UNAVAILABLE))
    override suspend fun refresh(target: CommunityTarget) = CommunityOperation.NotConfigured
    override suspend fun loadMore(target: CommunityTarget, cursor: CommunityCursor) = CommunityOperation.NotConfigured
    override suspend fun loadReplies(target: CommunityTarget, parentId: String, cursor: CommunityCursor?) = CommunityOperation.NotConfigured
    override suspend fun getRatingSummary(target: CommunityTarget) = CommunityOperation.NotConfigured
    override suspend fun rate(target: CommunityTarget, stars: Int): CommunityOperation =
        if (stars in 1..5) CommunityOperation.NotConfigured else CommunityOperation.Failed(
            CommunityError(CommunityErrorKind.VALIDATION, CommunityValidationIssue.INVALID_RATING),
        )
    override suspend fun post(target: CommunityTarget, body: String, parentCommentId: String?, requestId: String, spoiler: Boolean): CommunityOperation = validateUnavailable(body)
    override suspend fun setLiked(comment: CommunityComment, liked: Boolean) = CommunityOperation.NotConfigured
    override suspend fun report(comment: CommunityComment) = CommunityOperation.NotConfigured
    override suspend fun editOwned(comment: CommunityComment, body: String, spoiler: Boolean): CommunityOperation = validateUnavailable(body)
    override suspend fun deleteOwned(comment: CommunityComment) = CommunityOperation.NotConfigured
    private fun validateUnavailable(body: String): CommunityOperation = when (val result = CommunityCommentInput.validate(body)) {
        is CommentValidation.Valid -> CommunityOperation.NotConfigured
        is CommentValidation.Invalid -> CommunityOperation.Failed(CommunityError(CommunityErrorKind.VALIDATION, result.issue))
    }
}
