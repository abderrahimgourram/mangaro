package eu.kanade.tachiyomi.data.account

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import tachiyomi.core.common.util.lang.LocalAccountScope as Local
import tachiyomi.data.Database
import tachiyomi.data.ProfileReadingQueries

/** Real generated SQLDelight query -> production repository -> final owner projection. No device. */
class ProfileReadingProductionTest {
    @Test fun `generated query reaches owner projection and rereading restored flags cannot award XP`(): Unit = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            fun sql(value: String) { driver.execute(null,value,0).value }
            sql("CREATE TABLE local_account_context(id INTEGER,owner TEXT,transitioning INTEGER)")
            sql("INSERT INTO local_account_context VALUES(1,'a',0)")
            sql("CREATE TABLE mangas(_id INTEGER,source INTEGER,url TEXT,favorite INTEGER)")
            sql("INSERT INTO mangas VALUES(1,10,'/work',1)")
            sql("CREATE TABLE chapters(_id INTEGER,manga_id INTEGER,url TEXT,read INTEGER)")
            sql("INSERT INTO chapters VALUES(1,1,'/chapter/1',1),(2,1,'/chapter/1',1),(3,1,'/chapter/2',0)")
            val queries = ProfileReadingQueries(driver)
            val database = mockk<Database>()
            every { database.profileReadingQueries } returns queries
            val workspace = MutableStateFlow(Local.State(Local.Token("a",1)))
            val repository = ProfileReadingStatisticsRepository(database,workspace,Dispatchers.IO)
            suspend fun result() = withTimeout(3_000) { repository.observe("a").first { !it.loading } }
            val first = result()
            first.totals.comics shouldBe 1
            first.totals.total shouldBe null
            first.library shouldBe 1
            repeat(3) {
                sql("UPDATE chapters SET read=1 WHERE _id=1")
                result().totals.comics shouldBe 1
            }
            // New account workspace has different persisted reading flags, keeping source IDs unchanged.
            workspace.value = Local.State(workspace.value.token,ready=false)
            first.visibleFor("a",workspace.value).totals.comics shouldBe null
            sql("UPDATE local_account_context SET owner='b'")
            sql("UPDATE chapters SET read=0")
            workspace.value = Local.State(Local.Token("b",2))
            withTimeout(3_000) { repository.observe("b").first { !it.loading } }.totals.comics shouldBe 0
            first.visibleFor("b",workspace.value).totals.comics shouldBe null
        }
    }
}
