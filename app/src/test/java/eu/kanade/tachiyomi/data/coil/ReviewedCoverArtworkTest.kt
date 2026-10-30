package eu.kanade.tachiyomi.data.coil

import coil3.request.Options
import eu.kanade.tachiyomi.data.cache.CoverCache
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.manga.model.ReviewedCoverArtwork
import java.io.File

class ReviewedCoverArtworkTest {
    private val old = "https://mangatime.org/uploads/series/6a49e00234c268b77054833e-6907fadc0a427774.webp"
    private val current = "https://mangatime.org/uploads/series/6a49e00234c268b77054833e-176048383179db70.webp"

    @Test fun `new rotating asset for the reviewed provider work is corrected without matching a title`() {
        val rotating = "https://mangatime.org/uploads/series/6a49e00234c268b77054833e-488f62e80816d89e.webp"
        ReviewedCoverArtwork.resolve(ReviewedCoverArtwork.MANGA_TIME, rotating) shouldBe
            ReviewedCoverArtwork.MOUNT_HUA_OFFICIAL
        val anotherWork = "https://mangatime.org/uploads/series/697df7f10c5d340ac1549cb4-488f62e80816d89e.webp"
        ReviewedCoverArtwork.resolve(ReviewedCoverArtwork.MANGA_TIME, anotherWork) shouldBe anotherWork
        val wrongHost = rotating.replace("mangatime.org", "mangatime.org.example.org")
        ReviewedCoverArtwork.resolve(ReviewedCoverArtwork.MANGA_TIME, wrongHost) shouldBe wrongHost
        ReviewedCoverArtwork.resolve(99, rotating) shouldBe rotating
    }

    @Test fun `both observed wrong assets map to publisher supplied artwork without global title rules`() {
        ReviewedCoverArtwork.resolve(ReviewedCoverArtwork.MANGA_TIME, old) shouldBe
            ReviewedCoverArtwork.MOUNT_HUA_OFFICIAL
        ReviewedCoverArtwork.resolve(ReviewedCoverArtwork.MANGA_TIME, current) shouldBe
            ReviewedCoverArtwork.MOUNT_HUA_OFFICIAL
        ReviewedCoverArtwork.resolve(99, old) shouldBe old
        ReviewedCoverArtwork.resolve(
            ReviewedCoverArtwork.MANGA_TIME,
            "https://mangatime.org/new-correct-cover.webp",
        ) shouldBe
            "https://mangatime.org/new-correct-cover.webp"
        ReviewedCoverArtwork.resolve(ReviewedCoverArtwork.MANGA_TIME, null) shouldBe null
    }

    @Test fun `mangadar lord blackwood cover maps to official ridi artwork`() {
        ReviewedCoverArtwork.resolve(
            ReviewedCoverArtwork.MANGA_DAR,
            "https://img.ridicdn.net/cover/4158001245/xxlarge",
        ) shouldBe ReviewedCoverArtwork.LORD_BLACKWOOD_OFFICIAL

        ReviewedCoverArtwork.resolve(
            ReviewedCoverArtwork.MANGA_DAR,
            "https://uploads.mangadex.org/covers/a64020f9-c628-4807-8cd5-1d9cbced35e2/bae617c8-2dbe-469b-a0b2-694e6fb3dd8c.jpg.512.jpg",
        ) shouldBe ReviewedCoverArtwork.LORD_BLACKWOOD_OFFICIAL

        ReviewedCoverArtwork.resolve(
            ReviewedCoverArtwork.MANGA_DAR,
            "/manga/whats-our-hero-doing/",
        ) shouldBe ReviewedCoverArtwork.LORD_BLACKWOOD_OFFICIAL

        ReviewedCoverArtwork.resolve(
            ReviewedCoverArtwork.MANGA_DAR,
            "https://mangadar.com/uploads/other-manga.jpg",
        ) shouldBe "https://mangadar.com/uploads/other-manga.jpg"
    }

    @Test fun `domain and cover projection use same corrected cache key and cannot reuse old incorrect entry`() {
        val absent = mockk<File> { every { exists() } returns false }
        val cache = mockk<CoverCache> { every { getCustomCoverFile(any(), any()) } returns absent }
        val manga = Manga.create().copy(
            id = 19,
            source = ReviewedCoverArtwork.MANGA_TIME,
            url = "/manhwa/return-of-the-mount-hua-sect#6a49e00234c268b77054833e",
            thumbnailUrl = old,
        )
        val cover = MangaCover(19, manga.source, false, old, 0, "guest")
        val options = mockk<Options>()
        val key = MangaKeyer(cache).key(manga, options)
        key shouldBe "${ReviewedCoverArtwork.MOUNT_HUA_OFFICIAL};0"
        MangaCoverKeyer(cache).key(cover, options) shouldBe key
        (key == "$old;0") shouldBe false
        manga.thumbnailUrl shouldBe old // No stored metadata/identity/progress rewrite.
        manga.favorite shouldBe false
    }

    @Test fun `user selected custom cover retains its own owner scoped identity`() {
        val present = mockk<File> { every { exists() } returns true }
        val cache = mockk<CoverCache> { every { getCustomCoverFile(any(), any()) } returns present }
        val cover = MangaCover(19, ReviewedCoverArtwork.MANGA_TIME, true, current, 7, "guest")
        val key = MangaCoverKeyer(cache).key(cover, mockk())
        (key.contains(ReviewedCoverArtwork.MOUNT_HUA_OFFICIAL)) shouldBe false
        (key.endsWith(";19;7")) shouldBe true
    }

    @Test fun `default keyer registration remains lazy before application dependency injection is ready`() {
        val previousScope = uy.kohesive.injekt.Injekt
        try {
            uy.kohesive.injekt.Injekt =
                uy.kohesive.injekt.api.InjektScope(uy.kohesive.injekt.registry.default.DefaultRegistrar())
            MangaKeyer() // Registration alone must not resolve CoverCache or fail startup.
        } finally {
            uy.kohesive.injekt.Injekt = previousScope
        }
    }
}
