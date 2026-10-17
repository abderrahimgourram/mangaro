package eu.kanade.tachiyomi.search

import eu.kanade.tachiyomi.ui.browse.source.globalsearch.RankedSearchResult
import eu.kanade.tachiyomi.ui.home.CatalogWorkEvidence
import eu.kanade.tachiyomi.ui.home.PreferredMangaVariants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.ChapterListIntegrity
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.service.VerifiedCatalogIdentity
import java.util.concurrent.ConcurrentHashMap

/** Existing SQL evidence only. Identity enrichment never downloads covers or probes chapter indexes. */
internal class MangaSearchEvidence(private val getManga: GetManga, private val getChapters: suspend (Long) -> List<Chapter>) {
    private val metadata = ConcurrentHashMap<String, Manga>()
    private val counts = ConcurrentHashMap<String, SearchChapterEvidence>()
    fun resetBudget(recheck: Boolean = false) { metadata.clear(); if (recheck) counts.clear() }
    fun key(manga: Manga) = "${manga.source}:${manga.url}"
    fun requestMappings(rows: List<RankedSearchResult>) = CatalogWorkEvidence.request(rows.map {
        CatalogWorkEvidence.from(metadata[key(it.manga)] ?: it.manga)
    })
    fun candidate(row: RankedSearchResult, priority: Int): SearchWorkCandidate {
        val manga = metadata[key(row.manga)] ?: row.manga
        val identity = CatalogWorkEvidence.from(manga)
        return SearchWorkCandidate(key(manga), manga.source.toString(), SearchWorkKind.COMIC, manga.title,
            identity.aliases, identity.externalIds, identity.author, identity.artist, identity.format,
            identity.edition, identity.year, priority,
            (counts[key(manga)] ?: cachedChapters(manga)).copy(readable = PreferredMangaVariants.readerUsable(manga.id)),
            catalogIdentity = VerifiedCatalogIdentity.from(identity))
    }
    /** Same reconciled-index proof as Home; no chapter labels, advertised totals or new requests. */
    private fun cachedChapters(manga: Manga): SearchChapterEvidence {
        val count = ChapterListIntegrity.actualChapterCount(manga) ?: return SearchChapterEvidence()
        return SearchChapterEvidence(count, ChapterListIntegrity.complete(manga),
            checkedAt = ChapterListIntegrity.lastSuccessfulAt(manga))
    }
    suspend fun refresh(rows: List<RankedSearchResult>) = withContext(Dispatchers.IO) {
        for (row in rows.distinctBy { key(it.manga) }.take(12)) {
            ensureActive()
            val manga = getManga.await(row.manga.id)?.takeIf {
                it.source == row.manga.source && it.url == row.manga.url
            } ?: row.manga
            // The current record is authoritative; don't resurrect removed external IDs from an older memo.
            metadata[key(manga)] = manga
            counts[key(manga)] = if (ChapterListIntegrity.proof(manga) == null) SearchChapterEvidence() else {
                val chapters = getChapters(manga.id)
                if (!ChapterListIntegrity.verifiedRows(manga, chapters)) SearchChapterEvidence() else SearchChapterEvidence(
                    ChapterListIntegrity.uniqueIndexedCount(chapters, manga.source),
                    ChapterListIntegrity.complete(manga), checkedAt = ChapterListIntegrity.lastSuccessfulAt(manga))
            }
        }
        requestMappings(rows)
        if (metadata.size > 256) { metadata.clear(); counts.clear() }
    }
}
