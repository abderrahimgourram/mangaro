package eu.kanade.tachiyomi.source.internal.util

import tachiyomi.domain.manga.model.DiscoveryEligibility
import tachiyomi.domain.manga.model.Manga

/** Catalog eligibility is not chapter availability. Resolve indexes only on work/reader demand.
 * Unknown and genuinely new zero-chapter works remain discoverable. Never probe every card.
 * Reader/index validation and source-health suppression remain in their existing owners.
 */
object DiscoveryChapterGate {
    // Kept for source adapter compatibility; discovery no longer needs a parallel chapter cache.
    fun positive(@Suppress("UNUSED_PARAMETER") source: Long, @Suppress("UNUSED_PARAMETER") url: String) = Unit

    suspend fun filter(
        mangas: List<Manga>,
        onProgress: (suspend (List<Manga>) -> Unit)? = null,
    ): List<Manga> = mangas.filter { DiscoveryEligibility.valid(it.title, it.url) }.also {
        if (it.isNotEmpty()) onProgress?.invoke(it)
    }
}
