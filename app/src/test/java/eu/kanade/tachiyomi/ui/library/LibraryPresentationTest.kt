package eu.kanade.tachiyomi.ui.library

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import java.util.Date

class LibraryPresentationTest {
    @Test
    fun `inconsistent chapter counts never display negative unread or invalid progress`() {
        item(1, total = 3, read = 5).apply {
            libraryManga.visibleUnreadCount shouldBe 0L
            chapterProgress shouldBe 1f
        }
        item(1, total = -1, read = -3).apply {
            libraryManga.visibleUnreadCount shouldBe 0L
            chapterProgress shouldBe 0f
        }
        item(1, total = 10, read = -2).apply {
            libraryManga.visibleUnreadCount shouldBe 10L
            chapterProgress shouldBe 0f
        }
    }

    @Test
    fun `chapter progress uses actual local chapter counts`() {
        item(1, total = 8, read = 2).chapterProgress shouldBe 0.25f
        item(1, total = 0, read = 0).chapterProgress shouldBe 0f
    }

    @Test
    fun `continue reading retains stored chapter and page without matching by title`() {
        val library = listOf(item(1), item(2)) // Same title, distinct real identities.
        val history = listOf(history(2, chapter = 24, page = 17), history(1, chapter = 11, page = 6))
        val result = recentLibraryHistory(history, library.mapTo(HashSet()) { it.id })
        result.map { it.mangaId } shouldBe listOf(2L, 1L)
        result.first().chapterId shouldBe 24L
        result.first().lastPageRead shouldBe 17L
    }

    @Test
    fun `continue reading excludes non-library works and finished chapters`() {
        val history = listOf(history(99), history(1, read = true), history(2))
        recentLibraryHistory(history, setOf(1L, 2L)).map { it.mangaId } shouldBe listOf(2L)
    }

    @Test
    fun `history changes are reflected without duplicates and list is bounded`() {
        val library = (1L..6L).map { item(it) }
        val history = listOf(history(1, chapter = 15), history(1, chapter = 14)) + (2L..6L).map { history(it) }
        recentLibraryHistory(history, library.mapTo(HashSet()) { it.id }).map { it.chapterId } shouldBe listOf(15L, 2L, 3L)
        recentLibraryHistory(history.drop(2), library.mapTo(HashSet()) { it.id }).map { it.mangaId } shouldBe listOf(2L, 3L, 4L)
    }

    @Test
    fun `removed library manga cannot remain a continue reading target`() {
        recentLibraryHistory(listOf(history(1)), emptySet()) shouldBe emptyList()
    }

    private fun item(id: Long, total: Long = 10, read: Long = 1): LibraryItem {
        val local = LibraryManga(Manga.create().copy(id = id, title = "Same title", source = id), emptyList(), total, read, 0, 0, 0, 0)
        return LibraryItem(local, 0, local.visibleUnreadCount, false, "internal", "ar", LibraryItem.Badges(0, local.visibleUnreadCount, false, ""))
    }

    private fun history(id: Long, chapter: Long = id, page: Long = 2, read: Boolean = false) =
        HistoryWithRelations(id, chapter, id, "Same title", 1.0, Date(), 1, item(id).libraryManga.manga.asMangaCover(), page, read, 25)
}
