package eu.kanade.tachiyomi.source.internal.util

import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.source.model.ChapterFetchCompleteness
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap

/** Discovery only. UNKNOWN is pending verification, never a zero count or a DB deletion. */
object DiscoveryChapterGate {
    private val sources = setOf(918460697583900080L, 3975276517041363504L, 917436262447415426L)
    private data class Evidence(val count: Int?, val until: Long)
    private val evidence = ConcurrentHashMap<Pair<Long, String>, Evidence>()
    private val locks = List(32) { Mutex() }
    private val permits = Semaphore(2)
    fun positive(source: Long, url: String) { evidence[source to url] = Evidence(1, System.currentTimeMillis() + 3_600_000) }

    suspend fun filter(mangas: List<Manga>): List<Manga> = supervisorScope {
        val accepted = ConcurrentHashMap.newKeySet<Long>()
        // Every batch has a hard budget; obsolete screen jobs cancel its children normally.
        withTimeoutOrNull(20_000) {
            mangas.map { manga -> async {
                if (manga.source !in sources || manga.favorite) { accepted += manga.id; return@async }
                if (tachiyomi.domain.chapter.service.ChapterListIntegrity.count(manga) > 0) {
                    positive(manga.source, manga.url)
                    accepted += manga.id
                    return@async
                }
                val key = manga.source to manga.url
                locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
                    val now = System.currentTimeMillis()
                    val known = evidence[key]?.takeIf { it.until > now }
                    if (known != null) { if ((known.count ?: 0) > 0) accepted += manga.id; return@withLock }
                    if (!mihon.domain.source.health.SourceHealthMonitor.shared.due(manga.source)) return@withLock
                    val result = try {
                        permits.withPermit { withTimeoutOrNull(8_000) {
                            Injekt.get<SourceManager>().get(manga.source)?.getMangaUpdate(manga.toSManga(), emptyList(), false, true)
                        } }
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { null }
                    val count = when {
                        result == null || result.chapterCompleteness == ChapterFetchCompleteness.FAILED -> null
                        result.chapters.isNotEmpty() && result.chapters.all { it.url.isNotBlank() && it.name.isNotBlank() } -> result.chapters.size
                        result.chapters.isEmpty() && result.chapterCompleteness == ChapterFetchCompleteness.COMPLETE -> 0
                        else -> null
                    }
                    evidence[key] = Evidence(count, now + when { count == null -> 30_000; count == 0 -> 300_000; else -> 3_600_000 })
                    if ((count ?: 0) > 0) accepted += manga.id
                }
            } }.awaitAll()
        }
        // Bounded evidence storage; evicted entries require verification again.
        if (evidence.size > 1024) { evidence.entries.removeIf { it.value.until <= System.currentTimeMillis() }; if (evidence.size > 2048) evidence.clear() }
        mangas.filter { it.id in accepted }
    }
}
