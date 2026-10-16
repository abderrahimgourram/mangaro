package eu.kanade.tachiyomi.novels

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Assertions.*
import java.io.File
import java.sql.DriverManager

/** Real SQLite + persistent files; no Android runtime or live source requests. */
class NovelDownloadRemovalTest {
    @TempDir lateinit var root: File
    private val edition = "novel.seanovel:https://seanovel.org/novels/removal-fixture"
    private val text = NovelText(listOf("وصل المسافر إلى المدينة بعد رحلة طويلة وبدأ البحث عن رفاقه. ".repeat(30), "فتح الباب وتابع الطريق نحو النهر واحتفظ برسالة المعلم. ".repeat(30)))
    private fun disk(owner: String = "guest:0") = NovelDownloadDisk(File(novelDownloadRoot(root,owner),"text-downloads"))
    private fun db(owner: String = "guest:0"): java.sql.Connection {
        Class.forName("org.sqlite.JDBC")
        val f = File(novelDownloadRoot(root,owner),"downloads/queue.db"); f.parentFile.mkdirs()
        return DriverManager.getConnection("jdbc:sqlite:"+f).also { db ->
            if (!db.metaData.getTables(null,null,"tasks",null).use { it.next() }) NovelQueueSql.schema.forEach { sql -> db.createStatement().use { it.execute(sql) } }
        }
    }
    private fun java.sql.Connection.sql(sql: String, vararg args: Any) = prepareStatement(sql).use { q -> args.forEachIndexed { i,x -> q.setObject(i+1,x) };q.executeUpdate() }
    private fun java.sql.Connection.add(id: String, state: String = "DONE", work: String = edition) = sql("INSERT INTO tasks VALUES(?,?,?, ?,1,0,0,NULL)",id,work,"{}",state)
    private fun java.sql.Connection.visible() = createStatement().use { s -> s.executeQuery(NovelQueueSql.visible).use { r -> buildList { while(r.next()) add(r.getString(1)) } } }
    private fun java.sql.Connection.remove(id: String, owner: String = "guest:0") {
        sql(NovelQueueSql.deleting,id); disk(owner).delete(edition,id);sql(NovelQueueSql.deleted,id)
    }
    @Test fun completedDeletionIsAbsentAfterRestart() {
        disk().write(edition,"1",text); db().use { it.add("1");it.remove("1");assertTrue(it.visible().isEmpty()) }
        db().use { assertTrue(it.visible().isEmpty()) };assertFalse(disk().complete(edition,"1"))
    }
    @Test fun cancellationSurvivesRestartWithoutDeletingCompletedFiles() {
        disk().write(edition,"1",text)
        db().use { it.add("1");it.add("2","RUNNING");it.sql(NovelQueueSql.cancel,edition) }
        db().use { it.createStatement().use { s->s.execute(NovelQueueSql.recover) };assertEquals(listOf("1"),it.visible()) }
        assertEquals(text,disk().read(edition,"1"))
    }
    @Test fun anotherNovelsActiveQueueIsUntouched() {
        db().use { it.add("1");it.add("other","RUNNING","another-edition");it.remove("1");assertEquals(listOf("other"),it.visible()) }
    }
    @Test fun partialDeletionRetainsNonselectedChapter() {
        disk().write(edition,"1",text);disk().write(edition,"2",text)
        db().use { it.add("1");it.add("2");it.remove("1");assertEquals(listOf("2"),it.visible()) }
        assertEquals(text,disk().read(edition,"2"));assertNull(disk().read(edition,"1"))
    }
    @Test fun deletionDoesNotTouchLibraryPositionOrBookmarks() {
        val library=File(root,"novels-local/library.json");library.parentFile.mkdirs();library.writeText("fixture favorite,bookmark,page8")
        disk().write(edition,"1",text);db().use { it.add("1");it.remove("1") }
        assertEquals("fixture favorite,bookmark,page8",library.readText())
    }
    @Test fun accountFilesAndPersistentQueueCannotCrossOwners() {
        for(owner in listOf("account-a","account-b")) {disk(owner).write(edition,"1",text);db(owner).use {it.add("1")} }
        db("account-a").use {it.remove("1","account-a")}
        db("account-b").use {assertEquals(listOf("1"),it.visible())};assertEquals(text,disk("account-b").read(edition,"1"))
        assertNotEquals(novelDownloadRoot(root,"guest:0"),novelDownloadRoot(root,"account-a"))
        assertNotEquals(novelTextCacheKey("account-a",edition,"1"),novelTextCacheKey("account-b",edition,"1"))
    }
    @Test fun redownloadUsesNewGenerationAndPersistsNormally() {
        disk().write(edition,"1",text)
        db().use { q -> q.add("1");q.remove("1");q.sql("UPDATE tasks SET state='RUNNING',generation=generation+1 WHERE id=?","1");disk().write(edition,"1",text);q.sql("UPDATE tasks SET state='DONE' WHERE id=?","1");assertEquals(listOf("1"),q.visible()) }
        assertTrue(disk().complete(edition,"1"))
    }
    @Test fun oldWorkerCannotCommitAfterDeleteAndRedownloadABA() {
        db().use { q ->
            q.add("1","RUNNING");q.remove("1");q.sql("UPDATE tasks SET state='RUNNING',generation=generation+1 WHERE id=?","1")
            assertEquals(0,q.sql("UPDATE tasks SET state='DONE' WHERE id=? AND state='RUNNING' AND generation=?","1",1))
        }
    }
    @Test fun interruptedDeletionRemainsFencedAcrossRecovery() {
        disk().write(edition,"1",text);db().use { it.add("1","RUNNING");it.sql(NovelQueueSql.deleting,"1") }
        db().use { q ->q.createStatement().use { it.execute(NovelQueueSql.recover) };assertEquals(0,q.sql("UPDATE tasks SET state='DONE' WHERE id=? AND state='RUNNING' AND generation=?","1",1));q.remove("1");assertTrue(q.visible().isEmpty()) }
        assertNull(disk().read(edition,"1"))
    }
}
