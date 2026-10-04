package eu.kanade.tachiyomi.data.account

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import mihon.domain.account.AccountFoundation
import mihon.domain.account.AccountSession
import mihon.domain.community.CommunityMangaKey
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import java.io.ByteArrayOutputStream

/** An opt-in showcase, separate from private cloud Library replication. */
class ProfileShowcaseRepository(private val account: AccountFoundation) {
    @Serializable data class Favorite(val manga_key: String, val title: String, val cover_path: String? = null, val sort_order: Int = 0)
    @Serializable private data class Setting(val enabled: Boolean)
    data class Snapshot(val enabled: Boolean, val favorites: List<Favorite>)
    private val backend get() = account.auth as? SupabaseAccountAuth
    suspend fun load(user: String): Snapshot = withContext(Dispatchers.IO) {
        val auth = backend ?: error("Not configured")
        withTimeoutOrNull(15_000) { auth.withSession(user) {
            val enabled = auth.communityClient.from("public_showcase_settings").select { filter { eq("user_id", user) } }.decodeList<Setting>().firstOrNull()?.enabled ?: false
            val favorites = auth.communityClient.from("public_favorites").select { filter { eq("user_id", user) } }.decodeList<Favorite>().sortedBy { it.sort_order }
            Snapshot(enabled, favorites)
        } } ?: error("Showcase load timed out")
    }
    suspend fun save(context: Context, user: String, enabled: Boolean, favorites: List<Favorite>, local: Map<String, Manga>) = withContext(Dispatchers.IO) {
        val auth = backend ?: error("Not configured")
        withTimeoutOrNull(60_000) {
            val rows = favorites.map { favorite ->
                // Only upload covers after explicit owner save. Missing images retain a normal fallback.
                val path = favorite.cover_path ?: if (enabled) local[favorite.manga_key]?.let { manga ->
                    val bytes = cover(context, manga)
                    if (bytes != null) {
                        val destination = "$user/${favorite.manga_key}.webp"
                        withTimeoutOrNull(15_000) { auth.withSession(user) { auth.communityClient.storage.from("showcase-covers").upload(destination, bytes) { upsert = true; contentType = ContentType.parse("image/webp") } } } ?: error("Cover upload timed out")
                        destination
                    } else null
                } else null
                buildJsonObject { put("manga_key", favorite.manga_key); put("title", favorite.title); put("cover_path", path) }
            }
            auth.withSession(user) { auth.communityClient.postgrest.rpc("save_public_showcase", buildJsonObject { put("p_enabled", enabled); put("p_favorites", JsonArray(rows)) }) }
            check((account.session.value as? AccountSession.Authenticated)?.profile?.userId == user)
        } ?: error("Showcase save timed out")
    }
    private suspend fun cover(context: Context, manga: Manga): ByteArray? = try {
        val image = withTimeoutOrNull(5_000) { context.imageLoader.execute(ImageRequest.Builder(context).data(manga.asMangaCover()).size(320, 480).allowHardware(false).build()).image }
        image?.asDrawable(context.resources)?.toBitmap(320, 480)?.let { bitmap ->
            ByteArrayOutputStream().use { stream ->
                @Suppress("DEPRECATION")
                bitmap.compress(Bitmap.CompressFormat.WEBP, 72, stream)
                stream.toByteArray().takeIf { it.size <= 262144 }
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }
    companion object { fun key(manga: Manga) = CommunityMangaKey.fromSource(manga.source, manga.url).value }
}
