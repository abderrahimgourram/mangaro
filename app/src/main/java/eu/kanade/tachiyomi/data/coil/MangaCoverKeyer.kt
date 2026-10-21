package eu.kanade.tachiyomi.data.coil

import coil3.key.Keyer
import coil3.request.Options
import eu.kanade.domain.manga.model.hasCustomCover
import eu.kanade.tachiyomi.data.cache.CoverCache
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.manga.model.ReviewedCoverArtwork
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.domain.manga.model.Manga as DomainManga

class MangaKeyer(
    private val coverCache: CoverCache? = null,
) : Keyer<DomainManga> {
    override fun key(data: DomainManga, options: Options): String {
        return if (data.hasCustomCover(coverCache ?: Injekt.get())) {
            "${tachiyomi.core.common.util.lang.LocalAccountScope.segment(
                data.accountToken.fileOwner,
            )};${data.id};${data.coverLastModified}"
        } else {
            "${ReviewedCoverArtwork.resolve(data.source, data.thumbnailUrl)};${data.coverLastModified}"
        }
    }
}

class MangaCoverKeyer(
    private val coverCache: CoverCache = Injekt.get(),
) : Keyer<MangaCover> {
    override fun key(data: MangaCover, options: Options): String {
        return if (coverCache.getCustomCoverFile(data.mangaId, data.owner).exists()) {
            "${tachiyomi.core.common.util.lang.LocalAccountScope.segment(
                data.owner,
            )};${data.mangaId};${data.lastModified}"
        } else {
            "${ReviewedCoverArtwork.resolve(data.sourceId, data.url)};${data.lastModified}"
        }
    }
}
