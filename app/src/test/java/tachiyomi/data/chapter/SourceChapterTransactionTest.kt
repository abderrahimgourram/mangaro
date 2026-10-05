package tachiyomi.data.chapter

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import io.mockk.*
import eu.kanade.domain.chapter.model.toSChapter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tachiyomi.data.*
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate

class SourceChapterTransactionTest {
    private suspend fun database(driver: JdbcSqliteDriver): Database {
        Database.Schema.create(driver).await()
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        driver.execute(null, """INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer,
            chapter_flags, cover_last_modified, date_added) VALUES (1,44,'/manga','Work',0,1,1,0,0,0,0)""", 0)
        return Database(driver, Chapters.Adapter(MemoColumnAdapter), History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter, MemoColumnAdapter))
    }

    private fun chapter(number: Int) = Chapter.create().copy(mangaId=1,url="/chapter/$number",name="Chapter $number",chapterNumber=number.toDouble())

    @Test fun `new chapter commits once and metadata changes preserve stored reading progress`() = runTest {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            val repo=ChapterRepositoryImpl(database(driver))
            val original=repo.applySourceChanges(listOf(chapter(10).copy(read=true,bookmark=true,lastPageRead=18)),emptyList(),emptyList()).single()
            val observed = async(start = CoroutineStart.UNDISPATCHED) { repo.getChapterByMangaIdAsFlow(1).first { it.size == 2 } }
            val added=repo.applySourceChanges(listOf(chapter(11)),listOf(ChapterUpdate(original.id,name="10 updated",sourceOrder=1)),emptyList()).single()
            observed.await().map { it.url }.toSet() shouldBe setOf("/chapter/10", "/chapter/11")
            repo.getChapterByMangaId(1).size shouldBe 2
            repo.getChapterById(original.id)!!.apply { read shouldBe true; bookmark shouldBe true; lastPageRead shouldBe 18; name shouldBe "10 updated" }
            repo.applySourceChanges(emptyList(),listOf(ChapterUpdate(added.id,sourceOrder=0)),emptyList()) shouldBe emptyList()
            repo.getChapterByMangaId(1).map {it.url}.toSet() shouldBe setOf("/chapter/10","/chapter/11")
        }
    }

    @Test fun `failed source batch rolls back additions metadata and removals`() = runTest {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            val repo=ChapterRepositoryImpl(database(driver))
            val original=repo.applySourceChanges(listOf(chapter(10)),emptyList(),emptyList()).single()
            // Failure happens after inserting a valid new chapter and updating the original row.
            driver.execute(null,"CREATE TRIGGER reject_remove BEFORE DELETE ON chapters BEGIN SELECT RAISE(ABORT,'fixture failure'); END",0)
            assertThrows<Exception> {
                repo.applySourceChanges(listOf(chapter(11)),listOf(ChapterUpdate(original.id,name="changed")),listOf(original.id))
            }
            repo.getChapterByMangaId(1).single().apply { id shouldBe original.id; name shouldBe "Chapter 10" }
        }
    }

    @Test fun `real reconciliation persists newly fetched chapter and repeat refresh cannot duplicate it`() = runTest {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            val repo = ChapterRepositoryImpl(database(driver))
            val old = repo.applySourceChanges(listOf(chapter(10)), emptyList(), emptyList()).single()
            val preferences = mockk<tachiyomi.domain.library.service.LibraryPreferences> {
                every { markDuplicateReadChapterAsRead.get() } returns emptySet()
            }
            val excluded = mockk<eu.kanade.domain.manga.interactor.GetExcludedScanlators>()
            coEvery { excluded.await(any()) } returns emptySet()
            val sync = eu.kanade.domain.chapter.interactor.SyncChaptersWithSource(
                mockk(relaxed=true), mockk(relaxed=true), repo,
                tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter(), mockk(relaxed=true),
                tachiyomi.domain.chapter.interactor.UpdateChapter(repo),
                tachiyomi.domain.chapter.interactor.GetChaptersByMangaId(repo), excluded, preferences,
            )
            val source = mockk<eu.kanade.tachiyomi.source.Source> { every { id } returns 44L }
            val manga = tachiyomi.domain.manga.model.Manga.create().copy(id=1,source=44,url="/manga",title="Work")
            val fresh = listOf(chapter(11).toSChapter(), old.toSChapter())
            val first = sync.await(fresh,manga,source,manualFetch=true,
                completeness=eu.kanade.tachiyomi.source.model.ChapterFetchCompleteness.COMPLETE,declaredChapterCount=2)
            first.single().url shouldBe "/chapter/11"
            sync.await(fresh,manga,source,manualFetch=true,
                completeness=eu.kanade.tachiyomi.source.model.ChapterFetchCompleteness.COMPLETE,declaredChapterCount=2) shouldBe emptyList()
            repo.getChapterByMangaId(1).sortedBy {it.sourceOrder}.map {it.url} shouldBe listOf("/chapter/11","/chapter/10")
            repo.getChapterById(old.id)!!.url shouldBe "/chapter/10"
        }
    }

    @Test fun `title only unnumbered decimal zero special and distinct same names survive real reconciliation`() = runTest {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            val repo = ChapterRepositoryImpl(database(driver))
            val preferences = mockk<tachiyomi.domain.library.service.LibraryPreferences> {
                every { markDuplicateReadChapterAsRead.get() } returns emptySet()
            }
            val excluded = mockk<eu.kanade.domain.manga.interactor.GetExcludedScanlators>()
            coEvery { excluded.await(any()) } returns emptySet()
            val sync = eu.kanade.domain.chapter.interactor.SyncChaptersWithSource(
                mockk(relaxed=true), mockk(relaxed=true), repo,
                tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter(), mockk(relaxed=true),
                tachiyomi.domain.chapter.interactor.UpdateChapter(repo),
                tachiyomi.domain.chapter.interactor.GetChaptersByMangaId(repo), excluded, preferences,
            )
            val source = mockk<eu.kanade.tachiyomi.source.Source> { every { id } returns 44L }
            val manga = tachiyomi.domain.manga.model.Manga.create().copy(id=1,source=44,url="/manga",title="Work")
            val rows = listOf(
                Chapter.create().copy(mangaId=1,url="/one-shot",name="Work",chapterNumber=-1.0,dateUpload=0),
                chapter(0).copy(name="Prologue"),
                chapter(1).copy(url="/half",name="Chapter 0.5",chapterNumber=0.5),
                chapter(10).copy(url="/decimal",name="Chapter 10.5",chapterNumber=10.5),
                chapter(1).copy(url="/special",name="Epilogue",chapterNumber=-2.0),
                chapter(1).copy(url="/release-a",name="Chapter 1",scanlator="Team A"),
                chapter(1).copy(url="/release-b",name="Chapter 1",scanlator="Team B"),
                chapter(1).copy(url="/unnumbered",name="A new beginning",chapterNumber=-1.0),
            )
            val incoming = (rows + rows.first().copy()).map { it.toSChapter() }
            val inserted = sync.await(incoming,manga,source,
                completeness=eu.kanade.tachiyomi.source.model.ChapterFetchCompleteness.COMPLETE,declaredChapterCount=rows.size)
            inserted.size shouldBe rows.size
            val stored = repo.getChapterByMangaId(1)
            stored.map { it.url }.toSet() shouldBe rows.map { it.url }.toSet()
            stored.single { it.url == "/one-shot" }.apply { name shouldBe "Work"; chapterNumber shouldBe -1.0 }
            stored.single { it.url == "/decimal" }.chapterNumber shouldBe 10.5
            stored.single { it.url == "/half" }.chapterNumber shouldBe 0.5
            stored.single { it.url == "/special" }.chapterNumber shouldBe -2.0
            stored.single { it.url == "/unnumbered" }.chapterNumber shouldBe -1.0
            stored.sortedBy { it.sourceOrder }.map { it.url } shouldBe rows.map { it.url }
            sync.await(incoming,manga,source,completeness=eu.kanade.tachiyomi.source.model.ChapterFetchCompleteness.COMPLETE) shouldBe emptyList()
            repo.getChapterByMangaId(1).size shouldBe rows.size
        }
    }

}
