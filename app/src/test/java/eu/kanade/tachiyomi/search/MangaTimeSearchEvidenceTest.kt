package eu.kanade.tachiyomi.search

import eu.kanade.tachiyomi.source.internal.mangatime.MangaTime
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.RankedSearchResult
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.ChapterListIntegrity
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.fullType

/** Authored native-adapter/SQL fixtures. Similar names and even identical covers are not work proof. */
class MangaTimeSearchEvidenceTest {
    @Test fun distinctServerRecordsNeverMergeAndIdentityChecksMakeZeroSourceRequests() = runBlocking {
        Injekt.addSingletonFactory(fullType<Json>()) { Json { ignoreUnknownKeys = true } }
        var requests = 0
        val source = MangaTime(OkHttpClient.Builder().addInterceptor { requests++; error("No source request expected") }.build())
        val names = listOf("Survival Story of a Sword King in a Fantasy World", "Survival Diary of the Sword King in a Fantasy World")
        val entries = names.mapIndexed { index, title ->
            val id = 99101L + index
            val manga = Manga.create().copy(id = id, source = source.id, url = "/manhwa/route$index#server$index", title = title,
                initialized = true, thumbnailUrl = "https://mangatime.org/shared-cover.gif")
            val chapters = (1..if (index == 0) 304 else 999).map {
                Chapter.create().copy(id = it.toLong(), mangaId = id, url = "/server$index/$it", name = "Chapter $it")
            }
            manga.copy(memo = ChapterListIntegrity.memo(manga, "COMPLETE", chapters)) to chapters
        }
        val db = mockk<GetManga>()
        coEvery { db.await(any()) } answers { entries.first { it.first.id == firstArg<Long>() }.first }
        val evidence = MangaSearchEvidence(db, { id -> entries.first { it.first.id == id }.second })
        val rows = entries.map { RankedSearchResult(source, it.first) }
        evidence.refresh(rows)
        val groups = SearchWorkSelection().select(rows.map { evidence.candidate(it, 0) })
        groups.size shouldBe 2
        groups.map { it.winner.chapters.distinct }.toSet() shouldBe setOf(304, 999)
        requests shouldBe 0
        Unit
    }
}
