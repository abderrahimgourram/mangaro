package eu.kanade.tachiyomi.data.account

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/** Execute the exact production SELECT, with only named-parameter syntax adapted for JDBC. */
class ProfileReadingSqlTest {
    private fun open(): Connection {
        Class.forName("org.sqlite.JDBC")
        return DriverManager.getConnection("jdbc:sqlite::memory:").apply {
            createStatement().use { s ->
                s.execute("CREATE TABLE local_account_context(id INTEGER PRIMARY KEY, owner TEXT, transitioning INTEGER)")
                s.execute("INSERT INTO local_account_context VALUES(1,'a',0)")
                s.execute("CREATE TABLE mangas(_id INTEGER PRIMARY KEY, source INTEGER, url TEXT, favorite INTEGER)")
                s.execute("CREATE TABLE chapters(_id INTEGER PRIMARY KEY, manga_id INTEGER, url TEXT, read INTEGER, last_page_read INTEGER, downloaded INTEGER)")
                s.execute("INSERT INTO mangas VALUES(1,10,'/work',1),(2,10,'/work',0),(3,11,'/work',0),(4,10,'/other',0)")
            }
        }
    }
    private fun sql(): String {
        val root = listOf(File("."), File("..")).first { File(it, "data/src/main/sqldelight/tachiyomi/data/profileReading.sq").exists() }
        return File(root, "data/src/main/sqldelight/tachiyomi/data/profileReading.sq").readText().substringAfter("readStatistics:").replace(":owner", "?")
    }
    private fun Connection.count(owner: String = "a"): Triple<Long, Long, Long>? = prepareStatement(sql()).use { s ->
        s.setString(1, owner)
        s.executeQuery().use { c -> if (!c.next()) null else Triple(c.getLong("comic_count"), c.getLong("invalid_count"), c.getLong("library_count")) }
    }
    private fun Connection.add(id: Int, work: Int = 1, url: String = "/chapter/1", read: Int = 1, page: Int = 0, downloaded: Int = 0) {
        prepareStatement("INSERT INTO chapters VALUES(?,?,?,?,?,?)").use { s ->
            s.setInt(1,id);s.setInt(2,work);s.setString(3,url);s.setInt(4,read);s.setInt(5,page);s.setInt(6,downloaded);s.executeUpdate()
        }
    }
    @Test fun `empty owned dataset confirms zero`(): Unit = open().use { it.count() shouldBe Triple(0, 0, 1) }
    @Test fun `opened partial and downloaded unread chapters are excluded`(): Unit = open().use { db ->
        db.add(1,read=0);db.add(2,url="/chapter/2",read=0,page=99,downloaded=1);db.add(3,url="/chapter/3",read=1)
        db.count()?.first shouldBe 1
    }
    @Test fun `duplicate source edition chapter and replayed read updates count once`(): Unit = open().use { db ->
        db.add(1);db.add(2,work=2)
        repeat(4) { db.createStatement().use { it.execute("UPDATE chapters SET read=1 WHERE _id=1") } }
        db.count()?.first shouldBe 1
    }
    @Test fun `equal chapter numbers across different providers or works remain independent`(): Unit = open().use { db ->
        db.add(1);db.add(2,work=3);db.add(3,work=4)
        db.count()?.first shouldBe 3
    }
    @Test fun `reset to unread follows existing read semantics without deleting progress`(): Unit = open().use { db ->
        db.add(1,page=80,downloaded=1)
        db.createStatement().use { it.execute("UPDATE chapters SET read=0 WHERE _id=1") }
        db.count()?.first shouldBe 0
        db.createStatement().use { s -> s.executeQuery("SELECT last_page_read, downloaded FROM chapters WHERE _id=1").use { it.next();it.getInt(1) shouldBe 80;it.getInt(2) shouldBe 1 } }
    }
    @Test fun `another account and transition never return previous account zero or count`(): Unit = open().use { db ->
        db.add(1);db.count("b") shouldBe null
        db.createStatement().use { it.execute("UPDATE local_account_context SET transitioning=1") }
        db.count() shouldBe null
    }
    @Test fun `invalid identity surfaces incompleteness rather than fabricated count`(): Unit = open().use { db ->
        db.add(1);db.add(2,url=" ")
        db.count() shouldBe Triple(1,1,1)
    }
    @Test fun `read-only aggregate does not touch saved chapter payloads`(): Unit = open().use { db ->
        db.add(1,page=17,downloaded=1)
        repeat(3) { db.count() shouldBe Triple(1,0,1) }
        db.createStatement().use { s -> s.executeQuery("SELECT count(*), sum(last_page_read), sum(downloaded) FROM chapters").use { it.next();it.getInt(1) shouldBe 1;it.getInt(2) shouldBe 17;it.getInt(3) shouldBe 1 } }
    }
}
