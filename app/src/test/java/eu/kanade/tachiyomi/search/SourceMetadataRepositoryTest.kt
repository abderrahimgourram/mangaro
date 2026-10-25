package eu.kanade.tachiyomi.search

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.chapter.service.ChapterListIntegrity

class SourceMetadataRepositoryTest {
    @Test fun `real SQLite metadata transaction preserves concurrent private and chapter state`(): Unit = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            Database.Schema.create(driver).await()
            driver.execute(
                null,
                "INSERT INTO mangas(_id,source,url,title,status,favorite,initialized,viewer,chapter_flags," +
                    "cover_last_modified,date_added) VALUES(1,44,'/work/1','Actual Work',0,1,0,0,7,0,0)",
                0,
            ).await()
            driver.execute(
                null,
                "INSERT INTO chapters(_id,manga_id,url,name,read,bookmark,last_page_read,chapter_number," +
                    "source_order,date_fetch,date_upload) VALUES(1,1,'/chapter/1','1',1,1,8,1,0,0,0)",
                0,
            ).await()
            val db = Database(
                driver,
                Chapters.Adapter(MemoColumnAdapter),
                History.Adapter(DateColumnAdapter),
                Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter, MemoColumnAdapter),
            )
            val repository = MangaRepositoryImpl(db)
            val before = repository.getMangaById(1)
            val newest = JsonObject(
                mapOf(
                    ChapterListIntegrity.KEY to JsonPrimitive("newer-index"),
                    "private" to JsonPrimitive("preserve"),
                ),
            )
            driver.execute(null, "UPDATE mangas SET memo=? WHERE _id=1", 1) {
                bindString(0, newest.toString())
            }.await()
            val fresh = before.copy(
                author = "Writer",
                initialized = true,
                memo = AtlasDeclaredNames.retain(
                    before.memo,
                    "44",
                    "source.v1",
                    mapOf("title" to before.title, "alternative" to "原作名"),
                    "manhwa",
                ),
            )
            repository.updateSourceMetadata(before, fresh) shouldBe true
            val restored = repository.getMangaById(1)
            restored.memo[ChapterListIntegrity.KEY] shouldBe newest[ChapterListIntegrity.KEY]
            restored.memo["private"] shouldBe newest["private"]
            restored.favorite shouldBe true
            restored.chapterFlags shouldBe 7
            restored.url shouldBe before.url
            restored.author shouldBe "Writer"
            AtlasDeclaredNames.aliases(restored) shouldBe setOf(before.title, "原作名")
            repository.updateSourceMetadata(before, fresh.copy(author = "Stale Writer")) shouldBe false
            repository.getMangaById(1).author shouldBe "Writer"
            driver.executeQuery(
                null,
                "SELECT read||':'||bookmark||':'||last_page_read FROM chapters WHERE _id=1",
                { cursor ->
                    app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null)
                },
                0,
            ).await() shouldBe "1:1:8"
        }
    }
}
