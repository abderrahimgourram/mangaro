package eu.kanade.tachiyomi.data.account

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.BitmapFactory
import eu.kanade.tachiyomi.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.auth.UrlLauncher
import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.auth.ExternalAuthAction
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.handleDeeplinks
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mihon.domain.account.*
import java.security.MessageDigest
import kotlin.time.Duration.Companion.seconds

/** One injectable application client; Google browser OAuth only. No local manga database access. */
class SupabaseAccountAuth private constructor(private val client: SupabaseClient, private val credentials: AccountSessionStorage) : AccountAuth {
    // Community reuses this application client; authentication lifecycle remains here.
    internal val communityClient: SupabaseClient get() = client
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutations = Mutex()
    private val session = MutableStateFlow<AccountSession>(AccountSession.Loading)
    private val errors = MutableStateFlow<String?>(null)
    override val error = errors.asStateFlow()
    override val googleSignInAvailable = true
    override fun observeSession() = session.asStateFlow()

    init {
        scope.launch {
            if (withTimeoutOrNull(15_000) { session.first { it != AccountSession.Loading } } == null) {
                session.value = AccountSession.Guest
                errors.value = "تعذّر استعادة الحساب، يمكنك المتابعة كضيف"
            }
        }
        scope.launch {
            client.auth.sessionStatus.collectLatest { status ->
                when (status) {
                    SessionStatus.Initializing -> session.value = AccountSession.Loading
                    is SessionStatus.Authenticated -> {
                        try {
                            val user = status.session.user ?: client.auth.retrieveUserForCurrentSession()
                            if (user.isAnonymous == true || (user.appMetadata?.get("provider") as? JsonPrimitive)?.content != "google") {
                                client.auth.clearSession()
                                session.value = AccountSession.Guest
                            } else {
                                mutations.withLock { loadProfile(user) }
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            session.value = AccountSession.Guest
                            errors.value = "تعذّر تحميل ملفك الشخصي، حاول مجددًا"
                        }
                    }
                    is SessionStatus.NotAuthenticated -> session.value = AccountSession.Guest
                    is SessionStatus.RefreshFailure -> {
                        session.value = AccountSession.Guest
                        errors.value = "تعذّر استعادة الحساب، يمكنك المتابعة كضيف"
                    }
                }
            }
        }
    }

    internal class SessionChangedException : IllegalStateException()
    /** Existing auth mutex prevents a queued social write running as a newly signed-in account. */
    internal suspend fun <T> withSession(userId: String, action: suspend () -> T): T = mutations.withLock {
        if ((session.value as? AccountSession.Authenticated)?.profile?.userId != userId || client.auth.currentUserOrNull()?.id != userId) {
            throw SessionChangedException()
        }
        action()
    }

    override suspend fun signInWithGoogle(): AccountOperation = mutations.withLock {
        attempt {
            errors.value = null
            credentials.beginAuthorization()
            client.auth.signInWith(Google, redirectUrl = redirectUri) { queryParams["prompt"] = "select_account" }
            // Launching the browser is not authentication success. Only the session stream decides.
        }
    }

    /** Exact route + pending encrypted PKCE verifier; never accepts implicit tokens from arbitrary links. */
    fun handleCallback(uri: Uri): Boolean {
        if (uri.scheme != redirectScheme || uri.host != "auth" || !uri.path.isNullOrEmpty() || uri.port != -1 || uri.userInfo != null) return false
        scope.launch {
            mutations.withLock {
                attempt {
                    if (withTimeoutOrNull(15_000) { client.auth.awaitInitialization(); true } == null) {
                        errors.value = "تعذّر استعادة الحساب، حاول تسجيل الدخول مجددًا"
                        return@attempt
                    }
                    if (credentials.loadCodeVerifier() == null) {
                        errors.value = "رابط تسجيل الدخول غير صالح أو انتهت صلاحيته"
                        return@attempt
                    }
                    val oauthError = uri.getQueryParameter("error")
                    if (oauthError != null) {
                        credentials.deleteCodeVerifier()
                        if (oauthError != "access_denied") errors.value = "تعذّر تسجيل الدخول باستخدام Google"
                        return@attempt
                    }
                    val code = uri.getQueryParameters("code").singleOrNull()?.takeIf { it.isNotBlank() }
                    if (code == null || uri.fragment != null) {
                        credentials.deleteCodeVerifier()
                        errors.value = "رابط تسجيل الدخول غير صالح"
                        return@attempt
                    }
                    val completed = CompletableDeferred<Unit>()
                    client.handleDeeplinks(Intent(Intent.ACTION_VIEW, uri),
                        onSessionSuccess = { completed.complete(Unit) },
                        onError = { completed.completeExceptionally(it) })
                    try {
                        if (withTimeoutOrNull(20_000) { completed.await(); true } == null) {
                            errors.value = "تعذّر إكمال تسجيل الدخول، حاول مجددًا"
                        }
                    } finally { credentials.deleteCodeVerifier() }
                }
            }
        }
        return true
    }

    override suspend fun signOut() = mutations.withLock {
        // Stop account-only UI/work immediately; remote revocation may be offline or slow.
        session.value = AccountSession.Guest
        errors.value = null
        try { client.auth.signOut() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Clear only this cloud session even if revocation is offline. */ }
        finally {
            withContext(NonCancellable) {
                try { client.auth.clearSession(); credentials.deleteCodeVerifier() }
                finally { session.value = AccountSession.Guest; errors.value = null }
            }
        }
    }

    override suspend fun getCurrentProfile(): MangaroProfile? = mutations.withLock {
        val user = client.auth.currentUserOrNull() ?: return@withLock null
        try { loadProfile(user) } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { errors.value = "تعذّر تحميل ملفك الشخصي"; null }
    }

    override suspend fun refreshProgression(): AccountOperation = mutations.withLock {
        val current = (session.value as? AccountSession.Authenticated)?.profile
            ?: return@withLock AccountOperation.NotConfigured
        try {
            val progress = client.from("user_progression").select {
                filter { eq("user_id", current.userId) }
            }.decodeSingle<ProgressionRow>()
            val latest = (session.value as? AccountSession.Authenticated)?.profile
            if (latest?.userId == current.userId && client.auth.currentUserOrNull()?.id == current.userId) {
                session.value = AccountSession.Authenticated(latest.copy(xp = progress.total_xp, level = ProfileIdentity.displayedLevel(progress.level, latest.role)))
            }
            AccountOperation.Completed
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { AccountOperation.Failed("تعذّر تحديث التقدم") }
    }

    override suspend fun claimChapterCompletion(mangaKey: String, chapterKey: String): AccountOperation {
        val current = (session.value as? AccountSession.Authenticated)?.profile
            ?: return AccountOperation.Failed("سجّل دخولك أولًا")
        if (current.username == null) return AccountOperation.Failed("أكمل اسم المستخدم أولًا")
        return try {
            client.postgrest.rpc("claim_chapter_completion", buildJsonObject {
                put("p_manga_key", mangaKey); put("p_chapter_key", chapterKey)
            }).decodeAs<CompletionResult>()
            // Server-confirmed, idempotent claim. Reading never waits for this method.
            refreshProgression()
            AccountOperation.Completed
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { AccountOperation.Failed("تعذّر تحديث التقدم") }
    }

    override suspend fun usernameAvailable(username: String): Boolean? = try {
        val user = (session.value as? AccountSession.Authenticated)?.profile?.userId ?: return null
        withTimeoutOrNull(8_000) { withSession(user) { client.postgrest.rpc("username_available", buildJsonObject { put("p_username", AccountProfileInput.username(username)) }).decodeAs<Boolean>() } }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }
    @Serializable private data class RoleRow(val role: String)
    @Serializable private data class ProgressionRow(val total_xp: Long, val level: Int)
    @Serializable private data class CompletionResult(val xp_awarded: Int, val new_total_xp: Long,
        val new_level: Int, val leveled_up: Boolean)

    override suspend fun updateProfile(update: ProfileUpdate): AccountOperation = mutations.withLock {
        AccountProfileInput.error(update)?.let { return@withLock AccountOperation.Failed(it) }
        val user = client.auth.currentUserOrNull() ?: return@withLock AccountOperation.Failed("سجّل دخولك أولًا")
        attempt {
            client.from("profiles").update(buildJsonObject {
                put("username", AccountProfileInput.username(update.username!!))
                put("display_name", update.displayName!!.trim())
                put("bio", update.bio?.trim()?.takeIf { it.isNotEmpty() })
            }) { filter { eq("user_id", user.id) }; select() }.decodeSingle<ProfileRow>()
            loadProfile(user)
        }
    }

    override suspend fun uploadAvatar(webp: ByteArray): AccountOperation = mutations.withLock {
        val user = client.auth.currentUserOrNull() ?: return@withLock AccountOperation.Failed("سجّل دخولك أولًا")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(webp, 0, webp.size, bounds)
        if (webp.size !in 1..1_048_576 || bounds.outMimeType != "image/webp" || bounds.outWidth !in 1..1024 || bounds.outHeight !in 1..1024) {
            return@withLock AccountOperation.Failed("اختر صورة صالحة بحجم مناسب")
        }
        attempt {
            val path = "${user.id}/avatar.webp"
            client.storage.from("avatars").upload(path, webp) { upsert = true; contentType = ContentType.parse("image/webp") }
            client.from("profiles").update(buildJsonObject { put("avatar_path", path) }) {
                filter { eq("user_id", user.id) }; select()
            }.decodeSingle<ProfileRow>()
            loadProfile(user)
        }
    }

    override suspend fun removeAvatar(): AccountOperation = mutations.withLock {
        val user = client.auth.currentUserOrNull() ?: return@withLock AccountOperation.Failed("سجّل دخولك أولًا")
        attempt {
            // Preserve fallback before deleting the custom object; never touches chapter files.
            client.from("profiles").update(buildJsonObject { put("avatar_path", kotlinx.serialization.json.JsonNull) }) {
                filter { eq("user_id", user.id) }; select()
            }.decodeSingle<ProfileRow>()
            loadProfile(user)
            client.storage.from("avatars").delete(listOf("${user.id}/avatar.webp"))
        }
    }

    override suspend fun uploadCover(webp: ByteArray): AccountOperation = mutations.withLock {
        val user = client.auth.currentUserOrNull() ?: return@withLock AccountOperation.Failed("سجّل دخولك أولًا")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(webp, 0, webp.size, bounds)
        if (webp.size !in 1..1_048_576 || bounds.outMimeType != "image/webp" || bounds.outWidth !in 1..1200 || bounds.outHeight !in 1..500) {
            return@withLock AccountOperation.Failed("اختر غلافًا صالحًا بحجم مناسب")
        }
        attempt {
            val path = "${user.id}/cover.webp"
            client.storage.from("profile-media").upload(path, webp) { upsert = true; contentType = ContentType.parse("image/webp") }
            client.from("profiles").update(buildJsonObject { put("cover_path", path) }) {
                filter { eq("user_id", user.id) }; select()
            }.decodeSingle<ProfileRow>()
            loadProfile(user)
        }
    }

    override suspend fun removeCover(): AccountOperation = mutations.withLock {
        val user = client.auth.currentUserOrNull() ?: return@withLock AccountOperation.Failed("سجّل دخولك أولًا")
        attempt {
            client.from("profiles").update(buildJsonObject { put("cover_path", kotlinx.serialization.json.JsonNull) }) {
                filter { eq("user_id", user.id) }; select()
            }.decodeSingle<ProfileRow>()
            loadProfile(user)
            client.storage.from("profile-media").delete(listOf("${user.id}/cover.webp"))
        }
    }

    override suspend fun profileStatistics(): ProfileStatistics? {
        // Optional read-only statistics must not hold up profile saves or sign-out.
        val user = client.auth.currentUserOrNull() ?: return null
        return try {
            val counts = client.postgrest.rpc("profile_own_statistics").decodeAs<StatisticsRow>()
            if (client.auth.currentUserOrNull()?.id == user.id) ProfileStatistics(counts.comments, counts.ratings) else null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }
    @Serializable private data class StatisticsRow(val comments: Long, val ratings: Long)

    private suspend fun loadProfile(user: UserInfo): MangaroProfile {
        val row = client.from("profiles").select { filter { eq("user_id", user.id) } }.decodeSingle<ProfileRow>()
        check(row.userId == user.id)
        val avatar = row.avatarPath?.takeIf { it == "${user.id}/avatar.webp" }?.let {
            client.storage.from("avatars").publicUrl(it) + "?v=" + Uri.encode(row.updatedAt)
        } ?: row.googleAvatarUrl?.takeIf { Uri.parse(it).scheme == "https" }
        // A failed progression read must not invalidate a working Google session.
        val previous = (session.value as? AccountSession.Authenticated)?.profile?.takeIf { it.userId == user.id }
        val progression = try {
            client.from("user_progression").select { filter { eq("user_id", user.id) } }.decodeSingle<ProgressionRow>()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        val role = try {
            client.from("profile_roles").select { filter { eq("user_id", user.id) } }.decodeList<RoleRow>().firstOrNull()?.role.let(AccountRole::fromServer)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { previous?.role ?: AccountRole.USER }
        val profile = MangaroProfile(user.id, user.email, row.displayName, row.username, avatar,
            xp = progression?.total_xp ?: previous?.xp ?: 0, level = ProfileIdentity.displayedLevel(progression?.level ?: previous?.level ?: 1, role), role = role, googleAvatarUrl = row.googleAvatarUrl?.takeIf { Uri.parse(it).scheme == "https" }, bio = row.bio,
            coverUrl = row.coverPath?.takeIf { it == "${user.id}/cover.webp" }?.let {
                client.storage.from("profile-media").publicUrl(it) + "?v=" + Uri.encode(row.updatedAt)
            })
        // Total XP/level come only from server state; offline refresh retains the last confirmed values.
        if (client.auth.currentUserOrNull()?.id == user.id) {
            session.value = AccountSession.Authenticated(profile)
            errors.value = null
        }
        return profile
    }

    private suspend fun attempt(block: suspend () -> Unit): AccountOperation = try {
        block()
        AccountOperation.Completed
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) {
        val message = when {
            failure is PostgrestRestException && failure.code == "23505" -> "اسم المستخدم مستخدم بالفعل، اختر اسمًا آخر"
            failure is android.content.ActivityNotFoundException -> "لا يوجد متصفح متاح لتسجيل الدخول"
            else -> "تعذّر تنفيذ العملية، تحقق من الاتصال وحاول مجددًا"
        }
        errors.value = message
        AccountOperation.Failed(message)
    }

    @Serializable
    private data class ProfileRow(
        @SerialName("user_id") val userId: String,
        val username: String? = null,
        @SerialName("display_name") val displayName: String? = null,
        @SerialName("avatar_path") val avatarPath: String? = null,
        val bio: String? = null,
        @SerialName("cover_path") val coverPath: String? = null,
        @SerialName("google_avatar_url") val googleAvatarUrl: String? = null,
        @SerialName("updated_at") val updatedAt: String,
    )

    companion object {
        const val redirectScheme = "mangaro"
        const val redirectUri = "mangaro://auth"
        @OptIn(SupabaseExperimental::class)
        fun create(context: Context): AccountAuth {
            val url = BuildConfig.SUPABASE_URL
            val key = BuildConfig.SUPABASE_PUBLISHABLE_KEY
            val uri = runCatching { Uri.parse(url) }.getOrNull()
            if (key.isBlank() || uri?.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null || !uri.path.isNullOrEmpty() && uri.path != "/") return GuestAccountAuth()
            return try {
                val namespace = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
                val storage = AccountSessionStorage(context, namespace)
                val client = createSupabaseClient(url, key) {
                    defaultLogLevel = LogLevel.NONE
                    requestTimeout = 12.seconds
                    install(Auth) {
                        flowType = FlowType.PKCE
                        scheme = redirectScheme
                        host = "auth"
                        defaultRedirectUrl = redirectUri
                        defaultExternalAuthAction = ExternalAuthAction.CustomTabs()
                        sessionManager = storage
                        codeVerifierCache = storage
                        urlLauncher = UrlLauncher { supabase, oauthUrl ->
                            // SDK writes its verifier asynchronously. Persist it BEFORE leaving the app.
                            storage.awaitVerifierSaved()
                            UrlLauncher.DEFAULT.openUrl(supabase, oauthUrl)
                        }
                    }
                    install(Postgrest)
                    install(Storage)
                }
                SupabaseAccountAuth(client, storage)
            } catch (_: Exception) { GuestAccountAuth() }
        }
    }
}
