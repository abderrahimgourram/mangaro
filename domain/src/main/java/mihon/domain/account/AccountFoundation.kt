package mihon.domain.account

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Account state never owns or gates the existing local manga database. */
sealed interface AccountSession {
    data object Guest : AccountSession
    data object Loading : AccountSession
    data class Authenticated(val profile: MangaroProfile) : AccountSession
}

data class MangaroProfile(
    val userId: String,
    val email: String?,
    val displayName: String?,
    val username: String?,
    val avatarUrl: String?,
    val xp: Long,
    val level: Int,
    val googleAvatarUrl: String? = null,
) {
    init {
        require(userId.isNotBlank())
        require(xp >= 0)
        require(level in 1..MangaroRanks.MAX_LEVEL)
    }
    val rankTitle: String get() = MangaroRanks.titleFor(level)
}

/** Public author projection of the existing profile; excludes private email and account XP. */
data class AccountAuthor(
    val userId: String,
    val displayName: String?,
    val username: String?,
    val avatarUrl: String?,
    val level: Int,
) {
    init { require(userId.isNotBlank()); require(level in 1..MangaroRanks.MAX_LEVEL) }
    val rankTitle: String get() = MangaroRanks.titleFor(level)
    companion object {
        fun fromProfile(profile: MangaroProfile) = AccountAuthor(
            profile.userId, profile.displayName, profile.username, profile.avatarUrl, profile.level,
        )
    }
}

/** Rank names only; no XP rewards, earning rules or level calculation. */
object MangaroRanks {
    const val MAX_LEVEL = 30
    fun titleFor(level: Int): String {
        require(level in 1..MAX_LEVEL)
        return when (level) {
            in 1..5 -> "قارئ هاوي"
            in 6..10 -> "قارئ متابع"
            in 11..15 -> "قارئ شغوف"
            in 16..20 -> "قارئ متمرس"
            in 21..25 -> "قارئ مخضرم"
            in 26..29 -> "قارئ نخبة"
            else -> "قارئ أسطوري"
        }
    }
}

// Editable profile properties exclude server-owned progression and account identity.
data class ProfileUpdate(val displayName: String?, val username: String?)
/** Same conservative rules as the server CHECK constraints; display names are not handles. */
object AccountProfileInput {
    fun username(value: String): String = value.trim().lowercase(java.util.Locale.ROOT)
    private val handle = Regex("[a-z0-9_]{3,24}")
    fun error(update: ProfileUpdate): String? {
        val name = update.displayName?.trim().orEmpty()
        return when {
            update.username == null || !username(update.username).matches(handle) ->
                "اسم المستخدم: 3–24 حرفًا إنجليزيًا صغيرًا أو رقمًا أو شرطة سفلية"
            name.isEmpty() || name.codePointCount(0, name.length) > 40 -> "أدخل اسم عرض من 1 إلى 40 حرفًا"
            else -> null
        }
    }
}
sealed interface AccountOperation {
    data object NotConfigured : AccountOperation
    data object Completed : AccountOperation
    data class Failed(val message: String) : AccountOperation
}

interface AccountAuth {
    val googleSignInAvailable: Boolean
    fun observeSession(): StateFlow<AccountSession>
    val error: StateFlow<String?>
    suspend fun signInWithGoogle(): AccountOperation
    suspend fun signOut()
    suspend fun getCurrentProfile(): MangaroProfile?
    suspend fun updateProfile(update: ProfileUpdate): AccountOperation
    suspend fun uploadAvatar(webp: ByteArray): AccountOperation
    suspend fun removeAvatar(): AccountOperation
}

/** No network, credentials, account fabrication, token storage or local-data mutation. */
class GuestAccountAuth : AccountAuth {
    private val session = MutableStateFlow<AccountSession>(AccountSession.Guest)
    override val googleSignInAvailable = false
    override val error: StateFlow<String?> = MutableStateFlow(null).asStateFlow()
    override fun observeSession(): StateFlow<AccountSession> = session.asStateFlow()
    override suspend fun signInWithGoogle(): AccountOperation = AccountOperation.NotConfigured
    override suspend fun signOut() { session.value = AccountSession.Guest }
    override suspend fun getCurrentProfile(): MangaroProfile? = null
    override suspend fun updateProfile(update: ProfileUpdate): AccountOperation = AccountOperation.NotConfigured
    override suspend fun uploadAvatar(webp: ByteArray): AccountOperation = AccountOperation.NotConfigured
    override suspend fun removeAvatar(): AccountOperation = AccountOperation.NotConfigured
}

enum class AccountFeature {
    COMMENTS, REPLIES, MANGA_RATINGS, CHAPTER_RATINGS, REACTIONS, XP, LEVELS, RANKS, CLOUD_SYNC,
}
sealed interface AccountAccess {
    data class Allowed(val userId: String) : AccountAccess
    data class LoginRequired(val feature: AccountFeature) : AccountAccess
    data object SessionLoading : AccountAccess
}

/** One reusable decision and login-prompt stream for future account-only actions. */
class AccountFeatureGate(private val session: StateFlow<AccountSession>) {
    private val prompts = MutableSharedFlow<AccountAccess.LoginRequired>(
        extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val loginPrompts: SharedFlow<AccountAccess.LoginRequired> = prompts.asSharedFlow()
    fun access(feature: AccountFeature): AccountAccess = when (val current = session.value) {
        AccountSession.Guest -> AccountAccess.LoginRequired(feature)
        AccountSession.Loading -> AccountAccess.SessionLoading
        is AccountSession.Authenticated -> AccountAccess.Allowed(current.profile.userId)
    }
    fun ownsContent(ownerUserId: String): Boolean =
        (session.value as? AccountSession.Authenticated)?.profile?.userId == ownerUserId
    fun request(feature: AccountFeature): AccountAccess = access(feature).also {
        if (it is AccountAccess.LoginRequired) prompts.tryEmit(it)
    }
}

// Future sync snapshots are data contracts, not a replacement database or local-data mapper.
// Manga identity remains source-scoped; title/number alone never identifies a remote entity.
data class SyncMangaIdentity(val sourceId: Long, val mangaUrl: String)
data class SyncChapterIdentity(val chapterUrl: String, val remoteId: String?)
enum class SyncShelfKind { FAVORITES, READING, WATCH_LATER, COMPLETED, PAUSED, CUSTOM }
data class SyncShelf(val localCategoryId: Long, val name: String, val kind: SyncShelfKind)
data class SyncLibraryEntry(
    val localMangaId: Long,
    val identity: SyncMangaIdentity,
    val favorite: Boolean,
    val localCategoryIds: Set<Long>,
)
data class SyncReadingProgress(
    val manga: SyncMangaIdentity,
    val chapter: SyncChapterIdentity,
    val localChapterId: Long,
    val lastPageRead: Long,
    val totalPages: Long,
    val read: Boolean,
    val bookmark: Boolean,
    val lastReadAtMillis: Long?,
)
data class GuestLibrarySnapshot(
    val library: List<SyncLibraryEntry>,
    val shelves: List<SyncShelf>,
    val readingHistory: List<SyncReadingProgress>,
)
data class GuestMigrationPlan(val userId: String, val localSnapshot: GuestLibrarySnapshot)
enum class GuestMigrationChoice { KEEP_LOCAL_ONLY, ATTACH_TO_ACCOUNT }
sealed interface MigrationPreparation {
    data object NotConfigured : MigrationPreparation
    data class Offered(val plan: GuestMigrationPlan) : MigrationPreparation
}
interface AccountCloudSync {
    suspend fun prepareFirstLogin(userId: String, snapshot: GuestLibrarySnapshot): MigrationPreparation
    suspend fun applyMigration(plan: GuestMigrationPlan, choice: GuestMigrationChoice): AccountOperation
    suspend fun sync(userId: String, snapshot: GuestLibrarySnapshot): AccountOperation
}

/** Deliberately does not read, upload, rewrite or delete any local records. */
class DisabledAccountCloudSync : AccountCloudSync {
    override suspend fun prepareFirstLogin(userId: String, snapshot: GuestLibrarySnapshot): MigrationPreparation = MigrationPreparation.NotConfigured
    override suspend fun applyMigration(plan: GuestMigrationPlan, choice: GuestMigrationChoice): AccountOperation = AccountOperation.NotConfigured
    override suspend fun sync(userId: String, snapshot: GuestLibrarySnapshot): AccountOperation = AccountOperation.NotConfigured
}

/** Registered once at app scope; all account UI observes this same session. */
class AccountFoundation(val auth: AccountAuth, val cloudSync: AccountCloudSync) {
    val session: StateFlow<AccountSession> = auth.observeSession()
    val featureGate = AccountFeatureGate(session)
}
