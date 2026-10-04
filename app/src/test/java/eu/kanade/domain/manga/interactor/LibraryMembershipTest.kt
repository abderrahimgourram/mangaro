package eu.kanade.domain.manga.interactor

import eu.kanade.tachiyomi.ui.library.libraryMembershipAfterShelfEdit
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

class LibraryMembershipTest {
    @Test fun `normal add remove add writes only authoritative membership and preserves local metadata`() = runTest {
        val original = Manga.create().copy(id=42, source=8, url="/real", title="Real", favorite=false, chapterFlags=9, notes="Keep notes")
        val state = MutableStateFlow(original)
        val repository = mockk<MangaRepository>()
        coEvery { repository.update(any()) } coAnswers {
            val update = firstArg<tachiyomi.domain.manga.model.MangaUpdate>()
            state.value = state.value.copy(favorite=update.favorite ?: state.value.favorite, dateAdded=update.dateAdded ?: state.value.dateAdded)
            true
        }
        val update = UpdateManga(repository,mockk<FetchInterval>())
        val library = state.map { listOf(it).filter(Manga::favorite) }
        update.awaitUpdateFavorite(42,true) shouldBe true
        library.first().single().id shouldBe 42L
        update.awaitUpdateFavorite(42,false) shouldBe true
        library.first() shouldBe emptyList()
        update.awaitUpdateFavorite(42,true) shouldBe true
        library.first().size shouldBe 1
        state.value.chapterFlags shouldBe original.chapterFlags
        state.value.notes shouldBe original.notes
        state.value.source shouldBe original.source
        state.value.url shouldBe original.url
        coVerify(exactly=3) { repository.update(any()) }
        confirmVerified(repository) // No manga deletion, category destruction, or unrelated repository mutation.
    }
    @Test fun `clearing last shelf clears membership but preserves custom categories and uncategorized entries`() {
        libraryMembershipAfterShelfEdit(true,true,emptyList()) shouldBe false
        libraryMembershipAfterShelfEdit(true,true,listOf(77)) shouldBe true
        libraryMembershipAfterShelfEdit(true,false,emptyList()) shouldBe true
        libraryMembershipAfterShelfEdit(false,false,emptyList()) shouldBe true
    }
}
