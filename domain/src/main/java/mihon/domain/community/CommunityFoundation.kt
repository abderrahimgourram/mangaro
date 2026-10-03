package mihon.domain.community

import java.io.Serializable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import mihon.domain.account.MangaroRanks

enum class CommunityTargetType { MANGA, CHAPTER }
data class CommunityMangaKey(val sourceId: Long, val mangaUrl: String) : Serializable
data class CommunityChapterKey(val identity: String) : Serializable
/** Chapter keys are always scoped to their manga and its source, never to chapter number. */
data class CommunityTarget(
    val targetType: CommunityTargetType,
    val mangaKey: CommunityMangaKey,
    val chapterKey: CommunityChapterKey? = null,
) : Serializable {
    init { require((targetType == CommunityTargetType.CHAPTER) == (chapterKey != null)) }
}

data class CommunityComment(
    val id: String,
    val target: CommunityTarget,
    val userId: String,
    val displayName: String,
    val username: String?,
    val avatarUrl: String?,
    val level: Int,
    val body: String,
    val createdAt: Long,
    val updatedAt: Long?,
    val likeCount: Long,
    val replyCount: Long,
    val isEdited: Boolean,
    val isOwnedByCurrentUser: Boolean,
    val parentCommentId: String? = null,
) {
    val targetType get() = target.targetType
    val mangaKey get() = target.mangaKey
    val chapterKey get() = target.chapterKey
    val rankTitle get() = MangaroRanks.titleFor(level)
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
    val rating: CommunityRatingSummary? = null,
    val commentCount: Long? = null,
    val comments: List<CommunityComment> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)
sealed interface CommunityOperation {
    data object NotConfigured : CommunityOperation
    data object Completed : CommunityOperation
    data class Failed(val message: String) : CommunityOperation
}
/** Emit only after backend confirmation. These events never award or increment XP on Android. */
sealed interface CommunityEvent {
    data class CommentCreated(val target: CommunityTarget, val commentId: String) : CommunityEvent
    data class ReplyCreated(val target: CommunityTarget, val commentId: String, val parentId: String) : CommunityEvent
    data class MangaRated(val target: CommunityTarget, val stars: Int) : CommunityEvent
    data class ChapterRated(val target: CommunityTarget, val stars: Int) : CommunityEvent
}
interface CommunityRepository {
    val available: Boolean
    val events: Flow<CommunityEvent>
    fun observe(target: CommunityTarget): StateFlow<CommunitySnapshot>
    // Future implementation upserts one rating per authenticated user + target.
    suspend fun rate(target: CommunityTarget, stars: Int): CommunityOperation
    suspend fun post(target: CommunityTarget, body: String, parentCommentId: String? = null): CommunityOperation
    suspend fun react(comment: CommunityComment): CommunityOperation
    suspend fun report(comment: CommunityComment): CommunityOperation
    suspend fun editOwned(comment: CommunityComment, body: String): CommunityOperation
    suspend fun deleteOwned(comment: CommunityComment): CommunityOperation
}
class DisabledCommunityRepository : CommunityRepository {
    private val empty = MutableStateFlow(CommunitySnapshot()).asStateFlow()
    override val available = false
    override val events: Flow<CommunityEvent> = emptyFlow()
    override fun observe(target: CommunityTarget) = empty
    override suspend fun rate(target: CommunityTarget, stars: Int): CommunityOperation {
        require(stars in 1..5)
        return CommunityOperation.NotConfigured
    }
    override suspend fun post(target: CommunityTarget, body: String, parentCommentId: String?) = CommunityOperation.NotConfigured
    override suspend fun react(comment: CommunityComment) = CommunityOperation.NotConfigured
    override suspend fun report(comment: CommunityComment) = CommunityOperation.NotConfigured
    override suspend fun editOwned(comment: CommunityComment, body: String) = CommunityOperation.NotConfigured
    override suspend fun deleteOwned(comment: CommunityComment) = CommunityOperation.NotConfigured
}
