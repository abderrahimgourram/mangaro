package eu.kanade.tachiyomi.novels

/** Shared durable SQL protocol; focused JVM tests exercise the same statements used on Android. */
internal object NovelQueueSql {
    val schema = listOf(
        "CREATE TABLE editions (id TEXT PRIMARY KEY, source_id TEXT NOT NULL, novel TEXT NOT NULL)",
        "CREATE TABLE tasks (id TEXT PRIMARY KEY, edition_id TEXT NOT NULL, chapter TEXT NOT NULL, state TEXT NOT NULL, generation INTEGER NOT NULL DEFAULT 0, ordinal INTEGER NOT NULL, created INTEGER NOT NULL, error TEXT)",
        "CREATE INDEX pending_tasks ON tasks(state,edition_id,created,ordinal)",
    )
    const val recover = "UPDATE tasks SET state='PENDING',generation=generation+1 WHERE state='RUNNING'"
    const val pause = "UPDATE tasks SET state='PAUSED',generation=generation+1 WHERE edition_id=? AND state IN ('PENDING','RUNNING')"
    const val cancel = "UPDATE tasks SET state='CANCELLED',generation=generation+1 WHERE edition_id=? AND state NOT IN ('DONE','DELETING')"
    const val resume = "UPDATE tasks SET state='PENDING',generation=generation+1,error=NULL WHERE edition_id=? AND state='PAUSED'"
    const val retry = "UPDATE tasks SET state='PENDING',generation=generation+1,error=NULL WHERE edition_id=? AND state IN ('FAILED','CANCELLED')"
}
