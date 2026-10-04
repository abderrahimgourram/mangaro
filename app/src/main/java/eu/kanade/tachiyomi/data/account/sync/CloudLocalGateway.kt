package eu.kanade.tachiyomi.data.account.sync

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import mihon.domain.account.LocalCloudChanges
import mihon.domain.account.CloudSyncPolicy
import mihon.domain.community.*
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.service.ChapterIdentity
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.source.service.SourceManager
import java.util.UUID
import java.util.Date
import kotlin.time.Instant

internal val cloudTables = listOf("cloud_library_collections","cloud_library_entries","cloud_library_entry_collections","cloud_manga_history","cloud_chapter_progress")
internal fun JsonObject.text(key:String) = (get(key) as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.number(key:String) = text(key)?.toLongOrNull() ?: 0L
internal fun JsonObject.flag(key:String) = text(key)=="true"
internal fun rowKey(table:String,row:JsonObject):String = when(table) {
    "cloud_library_collections" -> row.text("id")!!
    "cloud_library_entry_collections" -> row.text("manga_key")+"/"+row.text("collection_id")
    "cloud_chapter_progress" -> row.text("manga_key")+"/"+row.text("chapter_key")
    else -> row.text("manga_key")!!
}
internal fun stamp(millis:Long) = Instant.fromEpochMilliseconds(millis).toString()
internal fun time(row:JsonObject,key:String) = row.text(key)?.let { Instant.parse(it).toEpochMilliseconds() } ?: 0L
internal fun clientRow(row:JsonObject) = JsonObject(row - setOf("user_id","revision","updated_at","created_at"))

/** Restoration uses only existing local repositories; no provider requests/chapter fabrication. */
class CloudLocalGateway(private val mangas:MangaRepository, private val chapters:ChapterRepository,
    private val categories:CategoryRepository, private val history:HistoryRepository,
    private val sources:SourceManager, private val store:CloudSyncStore, private val preferences:tachiyomi.core.common.preference.PreferenceStore) {
    private fun mangaKey(m:Manga) = CommunityMangaKey.fromSource(m.source,m.url)
    private fun chapterKey(m:Manga,c:tachiyomi.domain.chapter.model.Chapter) = CommunityChapterKey.fromSource(mangaKey(m),c.url,ChapterIdentity.remoteIds(c,m.source).singleOrNull()).value
    private fun metadata(m:Manga) = buildJsonObject { put("manga_key",mangaKey(m).value);put("source_id",m.source);put("source_manga_url",m.url);put("title_snapshot",m.title.take(512));put("thumbnail_url_snapshot",m.thumbnailUrl?.take(4096)) }
    private fun categoryId(user:String,id:Long):String {
        val builtin=eu.kanade.tachiyomi.ui.library.MangaroLibraryShelves.Shelf.entries.firstOrNull {
            preferences.getLong("mangaro.library.shelf.${it.storageKey}",-1L).get()==id
        }
        return store.get(user,"category/$id") ?: (builtin?.let {UUID.nameUUIDFromBytes("mangaro/shelf/${it.storageKey}".toByteArray(Charsets.UTF_8)).toString()} ?: UUID.randomUUID().toString()).also { store.put(user,"category/$id",it);store.put(user,"mapping/$it",id.toString()) }
    }
    suspend fun captureKey(change:LocalCloudChanges.Change):String = when(change.kind) {
        LocalCloudChanges.Kind.CHAPTER,LocalCloudChanges.Kind.HISTORY_CHAPTER -> "manga/"+(chapters.getChapterById(change.id)?.mangaId ?: -1)
        LocalCloudChanges.Kind.MANGA,LocalCloudChanges.Kind.HISTORY_MANGA -> "manga/${change.id}"
        else -> change.kind.name
    }
    suspend fun capture(user:String,change:LocalCloudChanges.Change):Map<String,JsonObject> {
        if(change.kind==LocalCloudChanges.Kind.COLLECTIONS || change.kind==LocalCloudChanges.Kind.COLLECTION_DELETED) return collectionRows(user)
        if(change.kind==LocalCloudChanges.Kind.HISTORY_ALL) return historyRows(user)
        val m = when(change.kind) {
            LocalCloudChanges.Kind.CHAPTER,LocalCloudChanges.Kind.HISTORY_CHAPTER,LocalCloudChanges.Kind.RESOLVE -> {
                if(change.id<0) mangas.getMangaById(-change.id) else chapters.getChapterById(change.id)?.let { mangas.getMangaById(it.mangaId) }
            }
            else -> runCatching { mangas.getMangaById(change.id) }.getOrNull()
        } ?: return emptyMap()
        return mangaRows(user,m)
    }
    suspend fun all(user:String):Map<String,JsonObject> {
        val historyMangas=history.getHistory("").first().map {it.mangaId}.distinct().map {mangas.getMangaById(it)}
        val ms=(mangas.getFavorites()+mangas.getReadMangaNotInLibrary()+historyMangas).distinctBy { it.id }
        return collectionRows(user)+ms.flatMap { mangaRows(user,it).entries }.associate { it.key to it.value }
    }
    suspend fun knownSnapshot(user:String):Map<String,JsonObject> {
        val rows=all(user).toMutableMap()
        val known=(store.rows(user,"remote/cloud_library_entries/").values+store.rows(user,"remote/cloud_manga_history/").values)
            .map {Json.parseToJsonElement(it).jsonObject}.distinctBy {it.text("manga_key")}
        for(row in known) mangas.getMangaByUrlAndSourceId(row.text("source_manga_url")!!,row.number("source_id"))?.let {rows.putAll(mangaRows(user,it))}
        return rows
    }
    private suspend fun collectionRows(user:String):Map<String,JsonObject> = categories.getAll().filter { !it.isSystemCategory }.associate { c ->
        val id=categoryId(user,c.id)
        "cloud_library_collections/$id" to buildJsonObject {put("id",id);put("name",c.name);put("sort_order",c.order);put("deleted_at",JsonNull)}
    }
    private suspend fun historyRows(user:String):Map<String,JsonObject> = history.getHistory("").first().map { it.mangaId }.distinct().flatMap { mangaRows(user,mangas.getMangaById(it)).entries }.associate {it.key to it.value}
    private suspend fun mangaRows(user:String,m:Manga):Map<String,JsonObject> = buildMap {
        val key=mangaKey(m).value
        if(m.favorite) {
            put("cloud_library_entries/$key",JsonObject(metadata(m)+buildJsonObject { put("added_at",m.dateAdded.takeIf {it>0}?.let(::stamp));put("deleted_at",JsonNull) }))
            categories.getCategoriesByMangaId(m.id).filter { !it.isSystemCategory }.forEach { c ->
                val id=categoryId(user,c.id)
                put("cloud_library_entry_collections/$key/$id",buildJsonObject {put("manga_key",key);put("collection_id",id);put("deleted_at",JsonNull)})
            }
        }
        if(!m.favorite && (store.get(user,"local/cloud_library_entries/$key")!=null || store.get(user,"remote/cloud_library_entries/$key")!=null)) {
            put("cloud_library_entries/$key",JsonObject(metadata(m)+buildJsonObject {put("added_at",m.dateAdded.takeIf {it>0}?.let(::stamp));put("deleted_at",stamp(m.favoriteModifiedAt ?: System.currentTimeMillis()))}))
        }
        val cs=chapters.getChapterByMangaId(m.id)
        cs.filter { it.read || it.lastPageRead>0 || it.totalPages>0 || store.get(user,"remote/cloud_chapter_progress/$key/${chapterKey(m,it)}")!=null }.forEach { c ->
            val ck=chapterKey(m,c)
            put("cloud_chapter_progress/$key/$ck",progressRow(key,ck,c))
        }
        val last=history.getHistoryByMangaId(m.id).filter {it.readAt!=null}.maxByOrNull {it.readAt!!.time}
        val c=last?.let { h -> cs.find {it.id==h.chapterId} }
        if(last!=null && c!=null) put("cloud_manga_history/$key",JsonObject(metadata(m)+buildJsonObject {put("last_chapter_key",chapterKey(m,c));put("last_chapter_source_url",c.url);put("last_page_index",c.lastPageRead.coerceAtLeast(0));put("last_total_pages",c.totalPages.takeIf {it>0});put("last_read_at",stamp(last.readAt!!.time));put("deleted_at",JsonNull)}))
    }
    private fun progressRow(key:String,ck:String,c:tachiyomi.domain.chapter.model.Chapter):JsonObject = buildJsonObject {put("manga_key",key);put("chapter_key",ck);put("source_chapter_url",c.url);put("chapter_name_snapshot",c.name.take(512));put("chapter_number_snapshot",c.chapterNumber.takeIf { it.isFinite() });put("last_page_index",c.lastPageRead.coerceAtLeast(0));put("total_pages",c.totalPages.takeIf {it>0});put("is_read",c.read);put("completed_at",if(c.read) stamp(c.lastModifiedAt.coerceAtLeast(1)) else null);put("state_changed_at",stamp(c.lastModifiedAt.coerceAtLeast(1)));put("deleted_at",JsonNull)}
    suspend fun baseline(user:String,table:String,row:JsonObject):JsonObject? {
        val key="$table/${rowKey(table,row)}"
        if(table=="cloud_library_collections") return collectionRows(user)[key] ?: clientRow(row)
        val metadata=if(table in listOf("cloud_library_entries","cloud_manga_history"))row else
            store.get(user,"remote/cloud_library_entries/${row.text("manga_key")}")?.let {Json.parseToJsonElement(it).jsonObject}
                ?: store.get(user,"remote/cloud_manga_history/${row.text("manga_key")}")?.let {Json.parseToJsonElement(it).jsonObject}
                ?: return null
        val m=mangas.getMangaByUrlAndSourceId(metadata.text("source_manga_url")!!,metadata.number("source_id")) ?: return null
        if(table=="cloud_chapter_progress") {
            val c=chapters.getChapterByUrlAndMangaId(row.text("source_chapter_url")!!,m.id) ?: return null
            val ck=chapterKey(m,c)
            if(ck!=row.text("chapter_key"))return null
            return progressRow(mangaKey(m).value,ck,c)
        }
        return mangaRows(user,m)[key] ?: clientRow(row)
    }
    suspend fun apply(user:String,table:String,row:JsonObject,initial:Boolean):Boolean = LocalCloudChanges.applyRemote {
        val deleted=row.text("deleted_at")!=null
        if(table=="cloud_library_collections") {
            val uuid=row.text("id")!!; var id=store.get(user,"mapping/$uuid")?.toLongOrNull()
            if(deleted) { if(!initial && id!=null) {categories.delete(id);store.invalidateCategory(id)}; return@applyRemote true }
            if(id==null) {
                val builtin=eu.kanade.tachiyomi.ui.library.MangaroLibraryShelves.Shelf.entries.firstOrNull {
                    UUID.nameUUIDFromBytes("mangaro/shelf/${it.storageKey}".toByteArray(Charsets.UTF_8)).toString()==uuid
                }
                if(builtin!=null) id=eu.kanade.tachiyomi.ui.library.MangaroLibraryShelves.ensure().first {it.shelf==builtin}.categoryId
            }
            if(id==null || categories.get(id)==null) {
                val before=categories.getAll().map {it.id}.toSet()
                categories.insert(Category(-1,row.text("name")!!,row.number("sort_order"),0))
                id=categories.getAll().single {it.id !in before}.id
                store.put(user,"mapping/$uuid",id.toString());store.put(user,"category/$id",uuid)
            }
            store.put(user,"mapping/$uuid",id.toString());store.put(user,"category/$id",uuid)
            categories.updateName(id,row.text("name")!!)
            // Preserve all custom categories; apply ordering without deleting unmentioned categories.
            val desired=store.rows(user,"remote/cloud_library_collections/").values.map {Json.parseToJsonElement(it).jsonObject}.filter {it.text("deleted_at")==null}
                .mapNotNull {r->store.get(user,"mapping/${r.text("id")}")?.toLongOrNull()?.let {it to r.number("sort_order")}}.toMap()
            val order=categories.getAll().filter { !it.isSystemCategory }.sortedWith(compareBy<Category> {desired[it.id] ?: it.order}.thenBy {categoryId(user,it.id)}).map {it.id}
            categories.updateAllOrders(order)
            return@applyRemote true
        }
        val key=row.text("manga_key")!!
        val entry = if(table in listOf("cloud_library_entries","cloud_manga_history")) row else
            store.get(user,"remote/cloud_library_entries/$key")?.let {Json.parseToJsonElement(it).jsonObject}
                ?: store.get(user,"remote/cloud_manga_history/$key")?.let {Json.parseToJsonElement(it).jsonObject} ?: return@applyRemote false
        val source=entry.number("source_id");val url=entry.text("source_manga_url")!!
        if(CommunityMangaKey.fromSource(source,url).value!=key) return@applyRemote false
        var m=mangas.getMangaByUrlAndSourceId(url,source)
        if(m==null) {
            if(deleted) return@applyRemote true
            if(sources.get(source)==null) return@applyRemote false
            m=mangas.insertNetworkManga(listOf(Manga.create().copy(source=source,url=url,title=entry.text("title_snapshot")!!,thumbnailUrl=entry.text("thumbnail_url_snapshot")))).single()
        }
        if(table=="cloud_library_entries") {
            val favorite=if(initial) CloudSyncPolicy.initialLibrary(m.favorite,!deleted) else !deleted
            check(mangas.update(MangaUpdate(m.id,favorite=favorite,dateAdded=m.dateAdded.takeIf {it>0} ?: time(row,"added_at"))))
            return@applyRemote true
        }
        if(table=="cloud_library_entry_collections") {
            val local=store.get(user,"mapping/${row.text("collection_id")}")?.toLongOrNull() ?: return@applyRemote deleted
            if(categories.get(local)==null) return@applyRemote deleted
            val ids=categories.getCategoriesByMangaId(m.id).map {it.id}.toSet()
            mangas.setMangaCategories(m.id,if(deleted && !initial) (ids-local).toList() else if(!deleted) (ids+local).toList() else ids.toList())
            return@applyRemote true
        }
        if(table=="cloud_manga_history" && deleted) {
            if(!initial) history.resetHistoryByMangaId(m.id)
            return@applyRemote true
        }
        val cs=chapters.getChapterByMangaId(m.id)
        val ck=row.text(if(table=="cloud_manga_history") "last_chapter_key" else "chapter_key")
        val c=cs.singleOrNull {chapterKey(m,it)==ck} ?: return@applyRemote false
        if(table=="cloud_manga_history") {
            if(deleted) {if(!initial) history.resetHistoryByMangaId(m.id);return@applyRemote true}
            val latest=history.getHistoryByMangaId(m.id).maxOfOrNull {it.readAt?.time ?:0} ?: 0
            if(time(row,"last_read_at")>=latest) {
                history.upsertHistory(HistoryUpdate(c.id,Date(time(row,"last_read_at")),0))
                chapters.update(ChapterUpdate(c.id,lastPageRead=if(initial) CloudSyncPolicy.initialPage(c.lastPageRead,row.number("last_page_index")) else row.number("last_page_index"),totalPages=maxOf(c.totalPages,row.number("last_total_pages")).takeIf {it>0}))
            }
            return@applyRemote true
        }
        if(deleted) return@applyRemote true // Progress tombstones never erase local history/read state.
        val read=if(initial) CloudSyncPolicy.initialRead(c.read,row.flag("is_read")) else row.flag("is_read")
        val page=if(initial) CloudSyncPolicy.initialPage(c.lastPageRead,row.number("last_page_index")) else row.number("last_page_index")
        val total=maxOf(c.totalPages,row.number("total_pages"))
        chapters.update(ChapterUpdate(c.id,read=read,lastPageRead=page,totalPages=total.takeIf {it>0}))
        if(row.flag("is_read")) store.put(user,"restored/${row.text("chapter_key")}","true")
        true
    }
}
