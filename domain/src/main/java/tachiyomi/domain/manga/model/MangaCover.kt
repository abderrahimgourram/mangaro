package tachiyomi.domain.manga.model

/**
 * Contains the required data for MangaCoverFetcher
 */
data class MangaCover(
    val mangaId: Long,
    val sourceId: Long,
    val isMangaFavorite: Boolean,
    val url: String?,
    val lastModified: Long,
    val owner: String = tachiyomi.core.common.util.lang.LocalAccountScope.fileOwner(),
) {
    override fun toString() = "MangaCover(mangaId=$mangaId, sourceId=$sourceId, lastModified=$lastModified, accountScoped=true)"
}

fun Manga.asMangaCover(): MangaCover {
    return MangaCover(
        mangaId = id,
        owner = accountToken.fileOwner,
        sourceId = source,
        isMangaFavorite = favorite,
        url = thumbnailUrl,
        lastModified = coverLastModified,
    )
}
