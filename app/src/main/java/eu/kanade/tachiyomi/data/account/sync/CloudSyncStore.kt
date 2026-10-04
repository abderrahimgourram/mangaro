package eu.kanade.tachiyomi.data.account.sync

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/** Isolated sync journal; never modifies Mihon SQL or contains credentials/download files. */
class CloudSyncStore(context: Context) : SQLiteOpenHelper(context, File(context.noBackupFilesDir, "cloud-sync.db").path, null, 1) {
    override fun onCreate(db: SQLiteDatabase) { db.execSQL("CREATE TABLE metadata(account TEXT NOT NULL, key TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(account,key))") }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    @Synchronized fun get(user: String, key: String): String? = readableDatabase.rawQuery("SELECT value FROM metadata WHERE account=? AND key=?", arrayOf(user,key)).use { if(it.moveToFirst()) it.getString(0) else null }
    @Synchronized fun put(user: String, key: String, value: String) { writableDatabase.insertWithOnConflict("metadata",null,ContentValues().apply { put("account",user);put("key",key);put("value",value) },SQLiteDatabase.CONFLICT_REPLACE) }
    @Synchronized fun remove(user: String, key: String) { writableDatabase.delete("metadata","account=? AND key=?",arrayOf(user,key)) }
    /** SQLite category row IDs can be reused after deletion; never reuse their cloud UUID. */
    @Synchronized fun invalidateCategory(id:Long) {
        atomic {
            val mappings=readableDatabase.rawQuery("SELECT account,key FROM metadata WHERE key LIKE 'mapping/%' AND value=?",arrayOf(id.toString())).use {c->buildList {while(c.moveToNext())add(c.getString(0) to c.getString(1))}}
            mappings.forEach {(user,key)->remove(user,key);remove(user,"category/$id")}
        }
    }
    @Synchronized fun count(user:String,prefix:String):Int = readableDatabase.rawQuery("SELECT count(*) FROM metadata WHERE account=? AND key LIKE ?",arrayOf(user,prefix+"%")).use {it.moveToFirst();it.getInt(0)}
    @Synchronized fun rows(user: String, prefix: String): Map<String,String> = readableDatabase.rawQuery("SELECT key,value FROM metadata WHERE account=? AND key LIKE ?",arrayOf(user,prefix+"%")).use { c -> buildMap { while(c.moveToNext()) put(c.getString(0),c.getString(1)) } }
    @Synchronized fun acknowledge(user: String, key: String, value: String) { writableDatabase.delete("metadata","account=? AND key=? AND value=?",arrayOf(user,key,value)) }
    @Synchronized fun replaceIfCurrent(user: String, key: String, expected: String, replacement: String): Boolean {
        return writableDatabase.update("metadata", ContentValues().apply {put("value",replacement)}, "account=? AND key=? AND value=?", arrayOf(user,key,expected)) == 1
    }
    @Synchronized fun atomic(block: () -> Unit) { val db=writableDatabase;db.beginTransaction();try { block();db.setTransactionSuccessful() } finally {db.endTransaction()} }
}
