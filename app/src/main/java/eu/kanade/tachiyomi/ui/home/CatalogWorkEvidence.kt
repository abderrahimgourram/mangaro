package eu.kanade.tachiyomi.ui.home

import android.content.Context
import logcat.logcat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import mihon.domain.community.CommunityMangaKey
import mihon.domain.community.CommunityRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.service.ReviewedCatalogMappings
import tachiyomi.domain.manga.service.WorkMetadata
import tachiyomi.domain.source.service.SourceManager
import eu.kanade.tachiyomi.source.internal.mangatime.MangaTime
import eu.kanade.tachiyomi.source.repair.RepairableSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** One batch for visible public edition keys; no title matching, image fetches or chapter probes. */
internal object CatalogWorkEvidence {
    private val mappings = ReviewedCatalogMappings()
    private val pending = linkedSetOf<String>()
    private val attempts = linkedMapOf<String, Long>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var installed = false
    @Serializable private data class Saved(val key: String, val work: String?, val checkedAt: Long)

    fun install(context: Context, scope: CoroutineScope) {
        if (installed) return
        installed = true
        if (eu.kanade.tachiyomi.BuildConfig.DEBUG || eu.kanade.tachiyomi.BuildConfig.SUNOVELS_PRIVATE_ACCEPTANCE)
            CatalogDuplicateDiagnostics.logger = { row -> logcat(CatalogDuplicateDiagnostics.priority) { "CatalogReview: $row" } }
        val preferences = context.getSharedPreferences("catalog-reviewed-identities-v1", Context.MODE_PRIVATE)
        scope.launch(Dispatchers.IO) {
            runCatching {
                val saved = Json.decodeFromString<List<Saved>>(preferences.getString("snapshot", null) ?: "[]")
                mappings.restore(saved.associate { it.key to ReviewedCatalogMappings.Entry(it.work, it.checkedAt) })
            }
            PreferredMangaVariants.changes.update { it + 1 }
            for (signal in wake) {
                delay(250) // Coalesce interleaved source results, rather than one request per card.
                val keys = synchronized(pending) { pending.toList().also { pending.clear() } }
                val now = System.currentTimeMillis()
                val eligible = mappings.pending(keys).filter { now - (attempts[it] ?: 0L) >= 30_000 }
                for (batch in eligible.chunked(200)) {
                    batch.forEach { attempts[it] = now }
                    while (attempts.size > 2048) attempts.remove(attempts.keys.first())
                    try {
                        val result = withTimeout(5_000) { Injekt.get<CommunityRepository>().reviewedMangaWorks(batch.toSet()) }
                        val changed = mappings.replace(batch.toSet(), result)
                        preferences.edit().putString("snapshot", Json.encodeToString(mappings.snapshot().map { (key, row) ->
                            Saved(key, row.work, row.checkedAt)
                        })).apply()
                        if (changed) PreferredMangaVariants.changes.update { it + 1 }
                    } catch (cancelled: CancellationException) {
                        if (cancelled !is TimeoutCancellationException) throw cancelled
                    } catch (_: Exception) { /* Unknown evidence stays separate; no automatic retry loop. */ }
                }
            }
        }
    }

    fun request(works: Collection<WorkMetadata>) {
        if (!installed) return
        val keys = mappings.pending(works.filter { it.reference.url.isNotBlank() }.map {
            CommunityMangaKey.fromSource(it.reference.sourceId, it.reference.url).value
        })
        if (keys.isEmpty()) return
        synchronized(pending) { pending.addAll(keys.take((800 - pending.size).coerceAtLeast(0))) }
        wake.trySend(Unit)
    }

    fun resolve(work: WorkMetadata): WorkMetadata {
        val root = work.reference.url.takeIf { it.isNotBlank() }?.let {
            mappings.resolve(CommunityMangaKey.fromSource(work.reference.sourceId, it).value)
        }
        return work.copy(reviewedWorkKey = root)
    }

    fun from(manga: Manga): WorkMetadata {
        val work = WorkMetadata.from(manga)
        // Only the adapter that actually validates this server ID may supply route-alias proof.
        val providerId = runCatching {
            val source = Injekt.get<SourceManager>().get(manga.source)
            val native = (source as? RepairableSource)?.original ?: source
            if (native is MangaTime) {
                val memoId = (manga.memo["mangatime.seriesId"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
                // This adapter's existing route contract explicitly carries the server series ID.
                val routeId = manga.url.takeIf { it.matches(Regex("/(manga|manhwa|manhua)/[^?#]+#[^#?]+")) }
                    ?.substringAfter('#')
                if (memoId != null && routeId != null && memoId != routeId) null else memoId ?: routeId
            } else if (native is eu.kanade.tachiyomi.source.internal.mangalek.MangaLek) {
                // Existing pinned MangaLek contract: legacy numeric post ID or source-emitted #postId.
                manga.url.takeIf { it.matches(Regex("[0-9]+")) } ?: manga.url
                    .takeIf { it.matches(Regex("/manga/[^?#]+#[0-9]+")) }?.substringAfter('#')
            } else null
        }.getOrNull()
        return resolve(work.copy(providerId = providerId))
    }
}
