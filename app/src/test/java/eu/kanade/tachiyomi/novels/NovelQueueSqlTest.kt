package eu.kanade.tachiyomi.novels

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

class NovelQueueSqlTest {
    @TempDir lateinit var directory: File
    private fun open(): Connection { Class.forName("org.sqlite.JDBC"); return DriverManager.getConnection("jdbc:sqlite:"+File(directory,"queue.db").absolutePath) }
    private fun Connection.action(sql:String, edition:String="a") = prepareStatement(sql).use { it.setString(1,edition);it.executeUpdate() }
    private fun Connection.states() = createStatement().use { statement -> statement.executeQuery("SELECT id,state,generation FROM tasks ORDER BY id").use { c -> buildList {
        while(c.next()) add(Triple(c.getString(1),c.getString(2),c.getLong(3)))
    } } }
    @Test fun durableRecoveryPauseCancelRetryPreserveCompletedAndOtherEditions() {
        open().use { db ->
            db.createStatement().use { statement ->
                NovelQueueSql.schema.forEach(statement::execute)
                listOf("DONE","RUNNING","PENDING","FAILED","PAUSED").forEachIndexed { i,state ->
                    statement.execute("INSERT INTO tasks VALUES('$i','a','{}','$state',0,$i,0,NULL)")
                }
                statement.execute("INSERT INTO tasks VALUES('5','b','{}','PENDING',0,0,0,NULL)")
            }
            db.action(NovelQueueSql.pause)
            db.states().map {it.second} shouldBe listOf("DONE","PAUSED","PAUSED","FAILED","PAUSED","PENDING")
            db.action(NovelQueueSql.resume)
            db.states().map {it.second} shouldBe listOf("DONE","PENDING","PENDING","FAILED","PENDING","PENDING")
            db.action(NovelQueueSql.cancel)
            db.states().map {it.second} shouldBe listOf("DONE","CANCELLED","CANCELLED","CANCELLED","CANCELLED","PENDING")
            db.action(NovelQueueSql.retry)
            db.createStatement().use {it.execute("UPDATE tasks SET state='RUNNING' WHERE id='1'")}
        }
        // A different connection represents a restarted process using the same durable queue.
        open().use {db ->
            val prior=db.states().first {it.first=="1"}.third
            db.createStatement().use {it.execute(NovelQueueSql.recover)}
            db.states().first {it.first=="1"}.apply {second shouldBe "PENDING";third shouldBe prior+1}
            db.states().first {it.first=="0"}.apply {second shouldBe "DONE";third shouldBe 0}
            db.states().first {it.first=="5"}.apply {second shouldBe "PENDING";third shouldBe 0}
            db.createStatement().use {it.execute(NovelQueueSql.recover)}
            db.states().first {it.first=="1"}.third shouldBe prior+1
        }
    }
    @Test fun uniqueTaskIdentityAndGenerationProtectRepeatedRequests() {
        open().use {db ->
            db.createStatement().use {s ->
                NovelQueueSql.schema.forEach(s::execute)
                s.execute("INSERT INTO tasks VALUES('same','a','{}','RUNNING',4,0,0,NULL)")
                runCatching {s.execute("INSERT INTO tasks VALUES('same','a','{}','PENDING',0,0,0,NULL)")}.isFailure shouldBe true
            }
            db.action(NovelQueueSql.pause)
            db.states().single().apply {second shouldBe "PAUSED";third shouldBe 5}
            // A result from generation 4 cannot complete a paused/cancelled generation 5 task.
            db.prepareStatement("UPDATE tasks SET state='DONE' WHERE id=? AND state='RUNNING' AND generation=?").use {s ->
                s.setString(1,"same");s.setLong(2,4);s.executeUpdate() shouldBe 0
            }
            db.states().single().second shouldBe "PAUSED"
        }
    }
}
