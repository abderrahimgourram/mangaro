package tachiyomi.domain.manga.model

/**
 * Reviewed source-asset corrections, never title matching or stored edition identity changes.
 * Both rejected URLs were publicly served for MangaTime series 6a49e00234c268b77054833e.
 * The replacement is the exact og:image supplied by the original publisher for webtoon 769209.
 * Other URLs/sources and user custom covers remain untouched. Remove/review if evidence is revoked.
 */
object ReviewedCoverArtwork {
    const val MANGA_TIME = 215553151312092548L
    const val MOUNT_HUA_OFFICIAL =
        "https://shared-comic.pstatic.net/thumb/webtoon/769209/thumbnail/thumbnail_IMAG19_f83b209a-25e4-4d75-8027-b7c7895a34ec.jpg"
    const val EVIDENCE = "https://m.comic.naver.com/webtoon/list?titleId=769209"
    const val REVIEWED_AT = "2026-10-20"
    private val rejected = setOf(
        "https://mangatime.org/uploads/series/6a49e00234c268b77054833e-6907fadc0a427774.webp",
        "https://mangatime.org/uploads/series/6a49e00234c268b77054833e-176048383179db70.webp",
    )

    fun resolve(sourceId: Long, url: String?): String? =
        if (sourceId == MANGA_TIME && url in rejected) MOUNT_HUA_OFFICIAL else url
}
