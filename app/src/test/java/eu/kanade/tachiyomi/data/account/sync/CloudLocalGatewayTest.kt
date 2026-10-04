package eu.kanade.tachiyomi.data.account.sync

import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import mihon.domain.account.LocalCloudChanges
import mihon.domain.community.CommunityMangaKey
import mihon.domain.community.CommunityChapterKey
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager

class CloudLocalGatewayTest {
    private val mangas=mockk<MangaRepository>(relaxed=true)
    private val chapters=mockk<ChapterRepository>(relaxed=true)
    private val categories=mockk<CategoryRepository>(relaxed=true)
    private val history=mockk<HistoryRepository>(relaxed=true)
    private val sources=mockk<SourceManager>(relaxed=true)
    private val store=mockk<CloudSyncStore>(relaxed=true)
    private val preferences=mockk<PreferenceStore>(relaxed=true)
    private val values=mutableMapOf<Pair<String,String>,String>()
    private val m=Manga.create().copy(id=42,source=17,url="/work",title="Real cached title")
    private val c=Chapter.create().copy(id=71,mangaId=42,url="/chapter",name="Chapter",lastPageRead=8,totalPages=12)
    private val mk=CommunityMangaKey.fromSource(m.source,m.url)
    private val ck=CommunityChapterKey.fromSource(mk,c.url).value
    private val gateway=CloudLocalGateway(mangas,chapters,categories,history,sources,store,preferences)
    init {
        every {store.get(any(),any())} answers {values[firstArg<String>() to secondArg<String>()]}
        every {store.put(any(),any(),any())} answers {values[firstArg<String>() to secondArg<String>()]=thirdArg<String>()}
        every {store.rows(any(),any())} answers {values.filterKeys {it.first==firstArg<String>() && it.second.startsWith(secondArg<String>())}.mapKeys {it.key.second}}
        every {preferences.getLong(any(),any()).get()} returns -1
        coEvery {mangas.getMangaByUrlAndSourceId(m.url,m.source)} returns m
        coEvery {mangas.getMangaById(m.id)} returns m
        coEvery {mangas.getFavorites()} returns emptyList()
        coEvery {mangas.getReadMangaNotInLibrary()} returns emptyList()
        coEvery {mangas.update(any())} returns true
        coEvery {chapters.getChapterByMangaId(m.id,any())} returns listOf(c)
        coEvery {history.getHistoryByMangaId(m.id)} returns emptyList()
        every {history.getHistory("")} returns flowOf(emptyList())
        coEvery {categories.getAll()} returns emptyList()
    }
    private fun entry()=buildJsonObject {
        put("manga_key",mk.value);put("source_id",m.source);put("source_manga_url",m.url)
        put("title_snapshot",m.title);put("deleted_at",JsonNull)
    }
    @Test fun `restoring same work twice uses existing source scoped manga without duplicates`()=runTest {
        gateway.apply("A","cloud_library_entries",entry(),false) shouldBe true
        gateway.apply("A","cloud_library_entries",entry(),false) shouldBe true
        coVerify(exactly=0) {mangas.insertNetworkManga(any())}
        coVerify(exactly=2) {mangas.update(match {it.id==42L && it.favorite==true})}
    }
    @Test fun `history restore stays outside library and keeps actual chapter page`()=runTest {
        val row=JsonObject(entry()+buildJsonObject {put("last_chapter_key",ck);put("last_page_index",10);put("last_total_pages",12);put("last_read_at",stamp(1_700_000_000_000))})
        gateway.apply("A","cloud_manga_history",row,false) shouldBe true
        coVerify {history.upsertHistory(match {it.chapterId==c.id})}
        coVerify {chapters.update(match {it.lastPageRead==10L})}
        coVerify(exactly=0) {mangas.update(any())}
    }
    @Test fun `remote completed chapter emits no local mutation and records XP suppression`()=runTest {
        values["A" to "remote/cloud_library_entries/${mk.value}"]=entry().toString()
        val row=buildJsonObject {put("manga_key",mk.value);put("chapter_key",ck);put("source_chapter_url",c.url);put("is_read",true);put("last_page_index",11);put("total_pages",12);put("deleted_at",JsonNull)}
        var emitted=0
        LocalCloudChanges.capture={emitted++}
        coEvery {chapters.update(any())} answers {LocalCloudChanges.changed(LocalCloudChanges.Kind.CHAPTER,c.id)}
        try {
            gateway.apply("A","cloud_chapter_progress",row,false) shouldBe true
            emitted shouldBe 0
            values["A" to "restored/$ck"] shouldBe "true"
        } finally {LocalCloudChanges.capture=null}
    }
    @Test fun `library tombstone removes favorite only and keeps history progress`()=runTest {
        gateway.apply("A","cloud_library_entries",JsonObject(entry()+("deleted_at" to JsonPrimitive(stamp(1_700_000_000_000)))),false) shouldBe true
        coVerify {mangas.update(match {it.favorite==false})}
        coVerify(exactly=0) {history.resetHistoryByMangaId(any())}
        coVerify(exactly=0) {chapters.update(any())}
    }
    @Test fun `unavailable source is unresolved without chapter fabrication or substitution`()=runTest {
        coEvery {mangas.getMangaByUrlAndSourceId(m.url,m.source)} returns null
        every {sources.get(m.source)} returns null
        gateway.apply("A","cloud_library_entries",entry(),false) shouldBe false
        coVerify(exactly=0) {mangas.insertNetworkManga(any())}
        coVerify(exactly=0) {chapters.addAll(any())}
    }
    @Test fun `custom collection mapping is stable and account isolated not name merged`()=runTest {
        coEvery {categories.getAll()} returns listOf(Category(4,"Custom",0,0),Category(5,"Custom",1,0))
        val first=gateway.all("A")
        gateway.all("A") shouldBe first
        first.size shouldBe 2
        (gateway.all("B").keys intersect first.keys).size shouldBe 0
    }
    @Test fun `first merge does not let cloud tombstone destroy preexisting local library`()=runTest {
        coEvery {mangas.getMangaByUrlAndSourceId(m.url,m.source)} returns m.copy(favorite=true)
        gateway.apply("A","cloud_library_entries",JsonObject(entry()+("deleted_at" to JsonPrimitive(stamp(1_700_000_000_000)))),true) shouldBe true
        coVerify {mangas.update(match {it.favorite==true})}
    }
    @Test fun `canonical built in shelf UUID follows internal identifier despite rename and account`()=runTest {
        every {preferences.getLong("mangaro.library.shelf.favorite",-1L).get()} returns 4
        coEvery {categories.getAll()} returns listOf(Category(4,"Renamed",0,0))
        gateway.all("A").keys shouldBe gateway.all("B").keys
    }

}
