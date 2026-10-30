package eu.kanade.tachiyomi.data.account

import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import mihon.domain.manga.model.toDomainManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Opaque public keys cannot reveal restoration URLs. Resolve only an exact source-scoped key. */
internal class PublicFavoriteResolver {
    suspend fun resolve(key: String, title: String): Manga? = withContext(Dispatchers.IO) {
        val repository = Injekt.get<MangaRepository>()
        val cleanTitle = title.replace(Regex("[.…]+$"), "").trim()
        val cleanNorm = tachiyomi.domain.manga.service.WorkTitleNormalizer.normalize(cleanTitle)

        (repository.getFavorites() + repository.getReadMangaNotInLibrary()).firstOrNull { ProfileShowcaseRepository.key(it) == key }?.let { return@withContext it }
        // Existing CatalogueSource search is used only on an explicit tap or missing showcase cover.
        withTimeoutOrNull(25_000) {
            val slots = Semaphore(3)
            coroutineScope {
                val sources = Injekt.get<SourceManager>().getAll().filterIsInstance<HttpSource>()
                val outcomes = Channel<Manga?>(Channel.UNLIMITED)
                val jobs = sources.map { source -> launch {
                    val found = slots.withPermit {
                        try {
                            withTimeoutOrNull(8_000) {
                                val results = source.getSearchManga(1, cleanTitle, source.getFilterList()).mangas.map { it.toDomainManga(source.id) }
                                results.firstOrNull { ProfileShowcaseRepository.key(it) == key }
                                    ?: results.firstOrNull {
                                        val candNorm = tachiyomi.domain.manga.service.WorkTitleNormalizer.normalize(it.title)
                                        candNorm == cleanNorm || (cleanNorm.length >= 4 && (candNorm.startsWith(cleanNorm) || cleanNorm.startsWith(candNorm)))
                                    }
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                    }
                    outcomes.send(found)
                } }
                var match: Manga? = null
                try {
                    for (ignored in sources.indices) {
                        val candidate = outcomes.receive()
                        if (candidate != null) { match = candidate; break }
                    }
                } finally { jobs.forEach { it.cancel() } }
                match?.let { Injekt.get<NetworkToLocalManga>()(it) }
            }
        }
    }
}
