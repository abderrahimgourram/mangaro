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

    override suspend fun getCurrentProfile(): MangaroProfile? {
        val user = client.auth.currentUserOrNull() ?: return null
        return try { loadProfile(user) } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { errors.value = "تعذّر تحميل ملفك الشخصي"; null }
    }

    override suspend fun updateProfile(update: ProfileUpdate): AccountOperation = mutations.withLock {
        AccountProfileInput.error(update)?.let { return@withLock AccountOperation.Failed(it) }
        val user = client.auth.currentUserOrNull() ?: return@withLock AccountOperation.Failed("سجّل دخولك أولًا")
        attempt {
            client.from("profiles").update(buildJsonObject {
                put("username", AccountProfileInput.username(update.username!!))
                put("display_name", update.displayName!!.trim())
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

    private suspend fun loadProfile(user: UserInfo): MangaroProfile {
        val row = client.from("profiles").select { filter { eq("user_id", user.id) } }.decodeSingle<ProfileRow>()
        check(row.userId == user.id)
        val avatar = row.avatarPath?.takeIf { it == "${user.id}/avatar.webp" }?.let {
            client.storage.from("avatars").publicUrl(it) + "?v=" + Uri.encode(row.updatedAt)
        } ?: row.googleAvatarUrl?.takeIf { Uri.parse(it).scheme == "https" }
        val profile = MangaroProfile(user.id, user.email, row.displayName, row.username, avatar, xp = 0, level = 1, googleAvatarUrl = row.googleAvatarUrl?.takeIf { Uri.parse(it).scheme == "https" })
        // No progression table exists in Phase 1; no client XP earning or mutation.
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
