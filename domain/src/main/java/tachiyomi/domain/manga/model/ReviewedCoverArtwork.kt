package tachiyomi.domain.manga.model

import java.net.URI

/** Reviewed provider-work artwork, never title matching or a stored edition rewrite.
 * The provider's asset filename carries its stable series ID; rotating hashes do
 * not revoke that identity. Re-review/remove a correction when its evidence changes.
 * User custom covers are selected before this policy by the existing keyer/fetcher.
 */
object ReviewedCoverArtwork {
    const val MANGA_TIME = 215553151312092548L
    const val MOUNT_HUA_OFFICIAL =
        "https://shared-comic.pstatic.net/thumb/webtoon/769209/thumbnail/thumbnail_IMAG19_f83b209a-25e4-4d75-8027-b7c7895a34ec.jpg"
    const val MANGA_DAR = 3975276517041363504L
    const val LORD_BLACKWOOD_OFFICIAL =
        "https://img.ridicdn.net/cover/4158001245/xxlarge"
    const val WHATS_OUR_HERO_DOING_OFFICIAL =
        "https://uploads.mangadex.org/covers/a64020f9-c628-4807-8cd5-1d9cbced35e2/d43e59cc-0f16-4092-9c00-5b9668209d1b.jpg"
    const val EVIDENCE = "https://m.comic.naver.com/webtoon/list?titleId=769209"
    const val REVIEWED_AT = "2026-10-23"
    private val reviewedSeries = mapOf("6a49e00234c268b77054833e" to MOUNT_HUA_OFFICIAL)
    private val assetPath = Regex("/uploads/series/([a-f0-9]{24})(?:-[A-Za-z0-9_-]+)?\\.(?:webp|png|jpe?g)")

    fun resolve(sourceId: Long, url: String?): String? {
        if (url == null) return null
        if (sourceId == MANGA_DAR) {
            if (url.contains("4158001245")) {
                return LORD_BLACKWOOD_OFFICIAL
            }
            if (url.contains("a64020f9-c628-4807-8cd5-1d9cbced35e2") ||
                url.contains("whats-our-hero-doing")
            ) {
                return WHATS_OUR_HERO_DOING_OFFICIAL
            }
            return url
        }
        if (sourceId != MANGA_TIME) return url
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        if (uri.scheme != "https" || uri.host != "mangatime.org" || uri.port !in setOf(-1, 443) ||
            uri.rawUserInfo != null || uri.rawFragment != null
        ) {
            return url
        }
        val work = assetPath.matchEntire(uri.rawPath.orEmpty())?.groupValues?.get(1) ?: return url
        return reviewedSeries[work] ?: url
    }
}
