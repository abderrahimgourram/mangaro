package eu.kanade.tachiyomi.ui.home

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mihon.domain.source.discovery.model.DiscoveryCategory
import tachiyomi.domain.manga.model.DiscoveryEligibility
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.manga.repository.MangaRepository

/** Bounded presentation references only. Restoring never writes old metadata into the database. */
internal class HomeDiscoveryCache(context: Context, private val mangas: MangaRepository) {
    private val preferences = context.getSharedPreferences("home-discovery-cache", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Reference(val id: Long, val source: Long, val url: String, val name: String)
    @Serializable
    private data class Stored(val savedAt: Long, val sources: List<Long>, val sections: Map<DiscoveryCategory, List<Reference>>, val freshAt: Long = 0L)
    data class Snapshot(val freshAt: Long, val sources: List<Long>, val sections: Map<DiscoveryCategory, List<HomeDiscoveryItem>>)

    suspend fun read(): Snapshot? = withContext(Dispatchers.IO) {
        try {
            val value = preferences.getString("snapshot", null) ?: return@withContext null
            val stored = json.decodeFromString<Stored>(value)
            // Old content is a brief offline fallback, never a reason to suppress refreshing.
            if (System.currentTimeMillis() - stored.savedAt !in 0..86_400_000L) return@withContext null
            val references = stored.sections.values.flatten().take(MAX_REFERENCES)
            val local = mangas.getMangaByIds(references.map { it.id }.distinct()).associateBy { it.id }
            PreferredMangaVariants.rememberAll(local.values)
            Snapshot(stored.freshAt, stored.sources, stored.sections.mapValues { (category, refs) ->
                GroupDiscoveryItems.group(refs.take(MAX_PER_SECTION).mapNotNull { ref ->
                    local[ref.id]?.takeIf { it.source == ref.source && it.url == ref.url && DiscoveryEligibility.valid(it.title, it.url) }?.let { manga ->
                        HomeDiscoveryItem(manga.id, manga.title, manga.asMangaCover(), manga.source, ref.name, manga.url)
                    }
                }, category.selectionContext())
            })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null } // A disposable cache cannot prevent fresh discovery.
    }

    suspend fun write(state: HomeState, freshAt: Long) = withContext(Dispatchers.IO) {
        val sections = mapOf(
            DiscoveryCategory.POPULAR to (listOfNotNull(state.discoveryFeatured) + state.popularManga),
            DiscoveryCategory.LATEST to state.latestManga,
            DiscoveryCategory.NEW to state.newManga,
            DiscoveryCategory.COMPLETED to state.completedManga,
        ).mapValues { (_, items) ->
            items.flatMap { listOf(it) + it.alternatives }.distinctBy { it.mangaId }.take(MAX_PER_SECTION)
                .map { Reference(it.mangaId, it.sourceId, it.url, it.sourceName) }
        }
        if (sections.values.all { it.isEmpty() }) {
            if (freshAt > 0L) preferences.edit().remove("snapshot").apply()
            return@withContext
        }
        preferences.edit().putString("snapshot", json.encodeToString(Stored(System.currentTimeMillis(), state.installedSources.map { it.id }, sections, freshAt))).apply()
    }

    companion object {
        private const val MAX_PER_SECTION = 64
        private const val MAX_REFERENCES = MAX_PER_SECTION * 4
    }
}
