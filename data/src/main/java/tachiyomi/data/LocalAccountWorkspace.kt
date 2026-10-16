package tachiyomi.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.core.common.util.lang.LocalAccountScope

/** Account archives + an atomic active projection. Catalog/chapter identities never move.
 * Legacy rows are ALWAYS guest-owned; a verified login alone never claims them.
 */
class LocalAccountWorkspace(private val database: Database, private val driver: SqlDriver, private val legacyBackup: java.io.File? = null) {
    private val lock = Mutex()
    private val personal = linkedMapOf(
        "mangas" to listOf("favorite", "viewer", "chapter_flags", "cover_last_modified", "date_added", "favorite_modified_at", "notes", "last_modified_at"),
        "chapters" to listOf("read", "bookmark", "last_page_read", "total_pages", "last_modified_at"),
    )
    private val relations = linkedMapOf(
        "categories" to listOf("_id", "name", "sort", "flags"),
        "mangas_categories" to listOf("_id", "manga_id", "category_id"),
        "history" to listOf("_id", "chapter_id", "last_read", "time_read"),
        "manga_sync" to listOf("_id", "manga_id", "sync_id", "remote_id", "library_id", "title", "last_chapter_read", "total_chapters", "status", "score", "remote_url", "start_date", "finish_date", "private"),
        "excluded_scanlators" to listOf("manga_id", "scanlator"),
    )
    private fun literal(value: String) = "'" + value.replace("'", "''") + "'"
    private suspend fun exec(sql: String) { driver.execute(null, sql, 0).await() }
    private suspend fun scalar(sql: String): String? = driver.executeQuery(null, sql, { cursor ->
        QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null)
    }, 0).await()
    private suspend fun archive(owner: String) {
        val who = literal(owner)
        exec("DELETE FROM local_account_snapshots WHERE owner=$who")
        (personal + relations).forEach { (table, columns) ->
            val key = if (table == "excluded_scanlators") "rowid" else "_id"
            val json = columns.joinToString(",") { "'$it',$it" }
            val where = when (table) {
                "mangas" -> " WHERE favorite<>0 OR viewer<>0 OR chapter_flags<>0 OR cover_last_modified<>0 OR date_added<>0 OR favorite_modified_at IS NOT NULL OR notes<>''"
                "chapters" -> " WHERE read<>0 OR bookmark<>0 OR last_page_read<>0 OR total_pages<>0 OR last_modified_at<>0"
                else -> ""
            }
            exec("INSERT INTO local_account_snapshots SELECT $who,'$table',$key,json_object($json) FROM $table$where")
        }
    }
    private suspend fun restore(owner: String) {
        // Relations first: user categories/history/track rows are archived, never discarded.
        listOf("mangas_categories", "history", "manga_sync", "excluded_scanlators").forEach { exec("DELETE FROM $it") }
        exec("DELETE FROM categories WHERE _id>0")
        exec("UPDATE categories SET name='',sort=-1,flags=0 WHERE _id<=0")
        val defaults = mapOf("notes" to "''", "favorite_modified_at" to "NULL")
        personal.forEach { (table, columns) ->
            exec("UPDATE $table SET " + columns.joinToString(",") { "$it=${defaults[it] ?: "0"}" })
            val where = "s.owner=${literal(owner)} AND s.table_name='$table' AND s.row_key=$table._id"
            exec("UPDATE $table SET " + columns.joinToString(",") { "$it=(SELECT json_extract(s.payload,'$.$it') FROM local_account_snapshots s WHERE $where)" } +
                " WHERE EXISTS(SELECT 1 FROM local_account_snapshots s WHERE $where)")
        }
        relations.forEach { (table, columns) ->
            val conflict = if (table == "categories") " ON CONFLICT(_id) DO UPDATE SET name=excluded.name,sort=excluded.sort,flags=excluded.flags" else ""
            exec("INSERT INTO $table(${columns.joinToString()}) SELECT " +
                columns.joinToString(",") { "json_extract(payload,'$.$it')" } +
                " FROM local_account_snapshots WHERE owner=${literal(owner)} AND table_name='$table'" + conflict)
        }
        exec("INSERT OR IGNORE INTO categories(_id,name,sort,flags) VALUES(0,'',-1,0)")
    }
    suspend fun bind(verifiedUser: String?, beforeReady: suspend () -> Unit = {}) = lock.withLock {
        require(verifiedUser == null || runCatching { java.util.UUID.fromString(verifiedUser).toString() == verifiedUser }.getOrDefault(false))
        val target = verifiedUser ?: LocalAccountScope.GUEST
        if (verifiedUser == null && LocalAccountScope.active().owner != LocalAccountScope.GUEST) {
            logcat(LogPriority.DEBUG) { "Guest workspace bind; callers=" + Throwable().stackTrace.take(7).joinToString { "${it.className}.${it.methodName}" } }
        }
        if (target != LocalAccountScope.active().owner) LocalAccountScope.beginTransition()
        val current = scalar("SELECT owner FROM local_account_context WHERE id=1")!!
        if (current == target && LocalAccountScope.active().owner == target && LocalAccountScope.changes.value.ready) return@withLock
        LocalAccountScope.beginTransition()
        try {
            if (current == LocalAccountScope.GUEST && current != target && legacyBackup != null && !legacyBackup.exists()) {
                legacyBackup.parentFile?.mkdirs()
                val temporary = java.io.File(legacyBackup.parentFile, "${legacyBackup.name}.${java.util.UUID.randomUUID()}.tmp")
                try {
                    exec("VACUUM INTO ${literal(temporary.absolutePath)}")
                    check(temporary.length() >= 4096 && temporary.inputStream().use { val header = ByteArray(16); it.read(header) == 16 && header.decodeToString() == "SQLite format 3\u0000" })
                    java.io.RandomAccessFile(temporary, "rw").use { it.fd.sync() }
                    check(temporary.renameTo(legacyBackup)) { "Unable to preserve legacy database backup" }
                } finally { temporary.delete() }
            }
            database.transaction {
                exec("UPDATE local_account_context SET transitioning=1 WHERE id=1")
                if (current != target) { archive(current); restore(target) }
                exec("UPDATE local_account_context SET owner=${literal(target)},transitioning=0 WHERE id=1")
            }
            beforeReady()
            refreshFiles()
            LocalAccountScope.commit(target)
            driver.notifyListeners("mangas", "chapters", "history", "categories", "mangas_categories", "manga_sync", "excluded_scanlators")
        } catch (failure: Throwable) { throw failure }
    }
    private suspend fun refreshFiles() {
        val claims = driver.executeQuery(null, "SELECT owner,guest_generation FROM local_account_file_claims", { cursor ->
            val entries = mutableListOf<Pair<String, Long>>()
            while (cursor.next().value) entries += cursor.getString(0)!! to cursor.getLong(1)!!
            QueryResult.Value(entries.groupBy({ it.first }, { it.second }))
        }, 0).await()
        val categoryMaps = driver.executeQuery(null, "SELECT f.owner,f.guest_generation,s.row_key,json_extract(s.payload,'$.newId') FROM local_account_file_claims f JOIN local_account_snapshots s ON s.owner='claim:'||f.owner||':'||f.guest_generation AND s.table_name='category_map'", { cursor ->
            val mappings = mutableMapOf<String, MutableMap<Long, Long>>()
            while (cursor.next().value) mappings.getOrPut("${cursor.getString(0)}:guest:${cursor.getLong(1)}") { mutableMapOf() }[cursor.getLong(2)!!] = cursor.getLong(3)!!
            QueryResult.Value(mappings)
        }, 0).await()
        LocalAccountScope.installFileScopes(scalar("SELECT guest_generation FROM local_account_context WHERE id=1")!!.toLong(), claims, categoryMaps)
    }
    /** Explicit consent only; additions preserve an existing account's positions and categories. */
    suspend fun claimGuest(verifiedUser: String) = lock.withLock {
        LocalAccountScope.checkOwner(verifiedUser)
        database.transaction {
            archive(verifiedUser)
            val who = literal(verifiedUser)
            // Current account decisions win overlapping rows (including explicit unread/removal).
            // The original guest version is retained below for recovery, never erased.
            for (table in personal.keys) exec("INSERT OR IGNORE INTO local_account_snapshots SELECT $who,table_name,row_key,payload FROM local_account_snapshots WHERE owner='guest' AND table_name='$table'")
            val categoryOffset = scalar("SELECT coalesce(max(row_key),0) FROM local_account_snapshots WHERE owner=$who AND table_name='categories'")!!.toLong().coerceAtLeast(0)
            exec("INSERT OR IGNORE INTO local_account_snapshots SELECT $who,table_name,CASE WHEN row_key>0 THEN row_key+$categoryOffset ELSE row_key END,json_set(payload,'$._id',CASE WHEN row_key>0 THEN row_key+$categoryOffset ELSE row_key END) FROM local_account_snapshots WHERE owner='guest' AND table_name='categories'")
            for (table in listOf("mangas_categories", "history", "manga_sync", "excluded_scanlators")) {
                val offset = scalar("SELECT coalesce(max(row_key),0) FROM local_account_snapshots WHERE owner=$who AND table_name='$table'")!!.toLong().coerceAtLeast(0)
                val category = "CASE WHEN json_extract(g.payload,'$.category_id')>0 THEN json_extract(g.payload,'$.category_id')+$categoryOffset ELSE json_extract(g.payload,'$.category_id') END"
                val equivalent = when (table) {
                    "mangas_categories" -> "json_extract(a.payload,'$.manga_id')=json_extract(g.payload,'$.manga_id') AND json_extract(a.payload,'$.category_id')=$category"
                    "history" -> "json_extract(a.payload,'$.chapter_id')=json_extract(g.payload,'$.chapter_id')"
                    "manga_sync" -> "json_extract(a.payload,'$.manga_id')=json_extract(g.payload,'$.manga_id') AND json_extract(a.payload,'$.sync_id')=json_extract(g.payload,'$.sync_id')"
                    else -> "json_extract(a.payload,'$.manga_id')=json_extract(g.payload,'$.manga_id') AND json_extract(a.payload,'$.scanlator')=json_extract(g.payload,'$.scanlator')"
                }
                val payload = if (table == "excluded_scanlators") "g.payload" else if (table == "mangas_categories") "json_set(g.payload,'$._id',g.row_key+$offset,'$.category_id',$category)" else "json_set(g.payload,'$._id',g.row_key+$offset)"
                exec("INSERT INTO local_account_snapshots SELECT $who,g.table_name,g.row_key+$offset,$payload FROM local_account_snapshots g WHERE g.owner='guest' AND g.table_name='$table' AND NOT EXISTS(SELECT 1 FROM local_account_snapshots a WHERE a.owner=$who AND a.table_name='$table' AND $equivalent)")
            }
            exec("UPDATE local_account_context SET transitioning=1 WHERE id=1")
            restore(verifiedUser)
            val generation = scalar("SELECT guest_generation FROM local_account_context WHERE id=1")!!.toLong()
            exec("INSERT INTO local_account_file_claims VALUES($generation,${literal(verifiedUser)})")
            // Retained metadata survives later account archives and makes preference import
            // restart-safe without changing the SQLite schema or moving category identities.
            exec("INSERT INTO local_account_snapshots SELECT ${literal("claim:$verifiedUser:$generation")},'category_map',row_key,json_object('newId',CASE WHEN row_key>0 THEN row_key+$categoryOffset ELSE row_key END) FROM local_account_snapshots WHERE owner='guest' AND table_name='categories'")
            // Preserve the entire original guest archive as a recoverable, non-UI namespace.
            exec("INSERT INTO local_account_snapshots SELECT 'legacy-recovery-$generation',table_name,row_key,payload FROM local_account_snapshots WHERE owner='guest'")
            exec("DELETE FROM local_account_snapshots WHERE owner='guest'")
            exec("UPDATE local_account_context SET guest_generation=guest_generation+1 WHERE id=1")
            exec("INSERT INTO local_account_claims VALUES(${literal(verifiedUser)},$generation,strftime('%s','now'))")
            exec("UPDATE local_account_context SET transitioning=0 WHERE id=1")
        }
        refreshFiles()
        driver.notifyListeners("mangas", "chapters", "history", "categories", "mangas_categories", "manga_sync", "excluded_scanlators")
    }
}

/** The same write transaction fences both sides of suspension, so a late callback rolls back. */
suspend fun <T> Database.accountOperation(block: suspend () -> T): T {
    val token = LocalAccountScope.capture()
    LocalAccountScope.check(token)
    return transactionWithResult {
        LocalAccountScope.check(token)
        val result = block()
        LocalAccountScope.check(token)
        result
    }
}
