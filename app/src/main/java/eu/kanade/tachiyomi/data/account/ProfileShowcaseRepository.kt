package eu.kanade.tachiyomi.data.account

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import eu.kanade.tachiyomi.novels.Novel
import eu.kanade.tachiyomi.novels.NovelRepository
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import logcat.logcat
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import mihon.domain.account.AccountFoundation
import mihon.domain.account.AccountSession
import mihon.domain.community.CommunityMangaKey
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import java.io.ByteArrayOutputStream
import kotlin.time.Duration

/** An opt-in showcase, separate from private cloud Library replication. */
class ProfileShowcaseRepository(private val account: AccountFoundation) {
    @Serializable data class Favorite(
        val manga_key: String,
        val title: String,
        val cover_path: String? = null,
        val sort_order: Int = 0,
        val featured: Boolean = false,
        val is_novel: Boolean = false,
        val chapter_count: Int? = null,
    )
    @Serializable private data class Setting(val enabled: Boolean)
    data class Snapshot(val enabled: Boolean, val favorites: List<Favorite>, val covers: Map<String, String> = emptyMap())
    private val backend get() = account.auth as? SupabaseAccountAuth
    suspend fun load(user: String): Snapshot = withContext(Dispatchers.IO) {
        val auth = backend ?: error("Not configured")
        withTimeoutOrNull(15_000) { auth.withSession(user) {
            val enabled = auth.communityClient.from("public_showcase_settings").select { filter { eq("user_id", user) } }.decodeList<Setting>().firstOrNull()?.enabled ?: false
            val favorites = auth.communityClient.from("public_favorites").select { filter { eq("user_id", user) } }.decodeList<Favorite>().sortedBy { it.sort_order }
            val paths = favorites.mapNotNull { it.cover_path }
            val covers = try {
                if (paths.isEmpty()) emptyMap() else {
                    val signedList = auth.communityClient.storage.from("showcase-covers").createSignedUrls(
                        Duration.parse("2h"), paths)
                    val map = mutableMapOf<String, String>()
                    for (signed in signedList) {
                        if (signed.error == null) {
                            val raw = signed.path
                            val clean = raw.trim().removePrefix("/")
                            val file = clean.substringAfterLast("/")
                            map[raw] = signed.signedURL
                            map[clean] = signed.signedURL
                            map["/$clean"] = signed.signedURL
                            map[file] = signed.signedURL
                        }
                    }
                    map
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                android.util.Log.w("ProfileShowcase", "Failed to create signed URLs for showcase covers: $paths", e)
                emptyMap()
            }
            Snapshot(enabled, favorites, covers)
        } } ?: error("Showcase load timed out")
    }
    suspend fun save(
        context: Context,
        user: String,
        enabled: Boolean,
        favorites: List<Favorite>,
        local: Map<String, Manga> = emptyMap(),
        localNovels: Map<String, Novel> = emptyMap(),
    ) = withContext(Dispatchers.IO) {
        val auth = backend ?: error("Not configured")
        withTimeoutOrNull(60_000) {
            val processedFavorites = favorites.map { favorite ->
                // Only upload covers after explicit owner save. Missing images retain a normal fallback.
                val path = favorite.cover_path ?: if (enabled) {
                    val manga = local[favorite.manga_key]
                    val novel = localNovels[favorite.manga_key]
                    val coverData: Any? = manga?.asMangaCover() ?: novel?.cover
                    if (coverData != null) {
                        val bytes = cover(context, coverData)
                        if (bytes != null) {
                            val destination = "$user/${favorite.manga_key}.webp"
                            val uploaded = try {
                                withTimeoutOrNull(15_000) {
                                    auth.withSession(user) {
                                        auth.communityClient.storage.from("showcase-covers")
                                            .upload(destination, bytes) { upsert = true; contentType = ContentType.parse("image/webp") }
                                    }
                                    true
                                } ?: false
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { false }
                            if (uploaded) destination else null
                        } else null
                    } else null
                } else null
                favorite.copy(cover_path = path)
            }

            fun buildPayload(includeExtendedMetadata: Boolean): JsonObject {
                val rows = processedFavorites.map { favorite ->
                    buildJsonObject {
                        put("manga_key", favorite.manga_key)
                        put("title", favorite.title)
                        put("cover_path", favorite.cover_path)
                        put("featured", favorite.featured)
                        if (includeExtendedMetadata) {
                            if (favorite.is_novel) put("is_novel", true)
                            favorite.chapter_count?.let { put("chapter_count", it) }
                        }
                    }
                }
                return buildJsonObject {
                    put("p_enabled", enabled)
                    put("p_favorites", JsonArray(rows))
                }
            }

            try {
                auth.withSession(user) {
                    auth.communityClient.postgrest.rpc("save_public_showcase", buildPayload(includeExtendedMetadata = true))
                }
            } catch (e: Exception) {
                if (e is CancellationException || e is SupabaseAccountAuth.SessionChangedException) throw e
                auth.withSession(user) {
                    auth.communityClient.postgrest.rpc("save_public_showcase", buildPayload(includeExtendedMetadata = false))
                }
            }

            check((account.session.value as? AccountSession.Authenticated)?.profile?.userId == user)
        } ?: error("Showcase save timed out")
    }
    private suspend fun cover(context: Context, data: Any): ByteArray? = try {
        val loader = if (data is String && (data.contains("novel") || data.contains("ceneles") || data.contains("sunovels") || data.contains("seanovel"))) {
            NovelRepository.get(context).covers
        } else context.imageLoader
        val image = withTimeoutOrNull(8_000) { loader.execute(ImageRequest.Builder(context).data(data).size(320, 480).allowHardware(false).build()).image }
        image?.asDrawable(context.resources)?.toBitmap(320, 480)?.let { bitmap ->
            ByteArrayOutputStream().use { stream ->
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 75, stream)
                } else {
                    @Suppress("DEPRECATION")
                    bitmap.compress(Bitmap.CompressFormat.WEBP, 75, stream)
                }
                stream.toByteArray().takeIf { it.size <= 262144 }
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }

    companion object {
        fun key(manga: Manga) = CommunityMangaKey.fromSource(manga.source, manga.url).value
        fun key(novel: Novel) = CommunityMangaKey.fromNovelEdition(novel.sourceId, novel.id).value
    }
}
