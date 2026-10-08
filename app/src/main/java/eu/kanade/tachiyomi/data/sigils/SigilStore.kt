package eu.kanade.tachiyomi.data.sigils

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.domain.sigils.*
import java.io.File

/** Private, account-scoped cosmetic ledger. Never stores manga titles, URLs, auth or XP. */
internal class SigilStore(context: Context) : SQLiteOpenHelper(context, File(context.noBackupFilesDir,"realm-sigils.db").path,null,1) {
    private val json = Json { ignoreUnknownKeys = true }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE facts(owner TEXT NOT NULL,key TEXT NOT NULL,body TEXT NOT NULL,dirty INTEGER NOT NULL DEFAULT 1,PRIMARY KEY(owner,key))")
        db.execSQL("CREATE TABLE unlocks(owner TEXT NOT NULL,id TEXT NOT NULL,body TEXT NOT NULL,announced INTEGER NOT NULL DEFAULT 1,PRIMARY KEY(owner,id))")
        db.execSQL("CREATE TABLE metadata(owner TEXT NOT NULL,key TEXT NOT NULL,value TEXT NOT NULL,PRIMARY KEY(owner,key))")
        db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT,owner TEXT NOT NULL,kind TEXT NOT NULL,entity INTEGER NOT NULL,manga INTEGER NOT NULL,evidence TEXT)")
        db.execSQL("CREATE UNIQUE INDEX sigil_event_coalescing ON events(owner,kind,entity)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    @Synchronized fun meta(owner: String,key: String): String? = readableDatabase.rawQuery("SELECT value FROM metadata WHERE owner=? AND key=?",arrayOf(owner,key)).use { if(it.moveToFirst()) it.getString(0) else null }
    @Synchronized fun put(owner: String,key: String,value: String) { writableDatabase.insertWithOnConflict("metadata",null,ContentValues().apply {put("owner",owner);put("key",key);put("value",value)},SQLiteDatabase.CONFLICT_REPLACE) }
    @Synchronized fun clearSlotsDirty(owner: String) { writableDatabase.delete("metadata","owner=? AND key=?",arrayOf(owner,"slots_dirty")); writableDatabase.delete("metadata","owner=? AND key=?",arrayOf(owner,"slots_base")) }
    @Synchronized fun facts(owner: String, dirty: Boolean = false): List<SigilFact> = readableDatabase.rawQuery("SELECT body FROM facts WHERE owner=?"+(if(dirty) " AND dirty=1" else ""),arrayOf(owner)).use { c -> buildList {while(c.moveToNext()) add(json.decodeFromString<SigilFact>(c.getString(0)))} }
    @Synchronized fun merge(owner: String,fact: SigilFact,dirty: Boolean = true) {
        val old = readableDatabase.rawQuery("SELECT body FROM facts WHERE owner=? AND key=?",arrayOf(owner,fact.kind+":"+fact.key)).use {if(it.moveToFirst()) json.decodeFromString<SigilFact>(it.getString(0)) else null}
        val merged = old?.merge(fact) ?: fact
        if(old == merged) return
        writableDatabase.insertWithOnConflict("facts",null,ContentValues().apply {put("owner",owner);put("key",fact.kind+":"+fact.key);put("body",json.encodeToString(merged));put("dirty",if(dirty) 1 else 0)},SQLiteDatabase.CONFLICT_REPLACE)
    }
    @Synchronized fun acknowledge(owner: String,fact: SigilFact) { writableDatabase.update("facts",ContentValues().apply {put("dirty",0)},"owner=? AND key=? AND body=?",arrayOf(owner,fact.kind+":"+fact.key,json.encodeToString(fact))) }
    @Synchronized fun unlocks(owner: String): List<SigilUnlock> = readableDatabase.rawQuery("SELECT body FROM unlocks WHERE owner=?",arrayOf(owner)).use { c -> buildList {while(c.moveToNext()) add(json.decodeFromString<SigilUnlock>(c.getString(0)))} }
    @Synchronized fun unlock(owner: String,unlock: SigilUnlock,announce: Boolean) {
        val seen = readableDatabase.rawQuery("SELECT announced FROM unlocks WHERE owner=? AND id=?",arrayOf(owner,unlock.id)).use {if(it.moveToFirst()) it.getInt(0) else null}
        writableDatabase.insertWithOnConflict("unlocks",null,ContentValues().apply {put("owner",owner);put("id",unlock.id);put("body",json.encodeToString(unlock));put("announced",if(unlock.revoked) 1 else seen ?: if(announce) 0 else 1)},SQLiteDatabase.CONFLICT_REPLACE)
    }
    @Synchronized fun pendingAnnouncement(owner: String): SigilUnlock? = readableDatabase.rawQuery("SELECT body FROM unlocks WHERE owner=? AND announced=0 ORDER BY rowid LIMIT 1",arrayOf(owner)).use {if(it.moveToFirst()) json.decodeFromString<SigilUnlock>(it.getString(0)).takeUnless(SigilUnlock::revoked) else null}
    @Synchronized fun announced(owner: String,id: String) { writableDatabase.update("unlocks",ContentValues().apply {put("announced",1)},"owner=? AND id=?",arrayOf(owner,id)) }
    @Synchronized fun event(owner: String,event: SigilEvents.Event) { writableDatabase.insertWithOnConflict("events",null,ContentValues().apply {put("owner",owner);put("kind",event.kind.name);put("entity",event.id);put("manga",event.mangaId);put("evidence",event.evidence?.let {json.encodeToString(it)})},SQLiteDatabase.CONFLICT_IGNORE) }
    @Synchronized fun events(owner: String): List<Pair<Long,SigilEvents.Event>> = readableDatabase.rawQuery("SELECT id,kind,entity,manga,evidence FROM events WHERE owner=? ORDER BY id LIMIT 250",arrayOf(owner)).use {c -> buildList {while(c.moveToNext()) add(c.getLong(0) to SigilEvents.Event(SigilEvents.Kind.valueOf(c.getString(1)),c.getLong(2),c.getLong(3),evidence=if(c.isNull(4)) null else json.decodeFromString<SigilFact>(c.getString(4))))} }
    @Synchronized fun finishEvent(id: Long) {writableDatabase.delete("events","id=?",arrayOf(id.toString()))}
    @Synchronized fun atomic(block: () -> Unit) {val db=writableDatabase;db.beginTransaction();try {block();db.setTransactionSuccessful()} finally {db.endTransaction()}}
}
