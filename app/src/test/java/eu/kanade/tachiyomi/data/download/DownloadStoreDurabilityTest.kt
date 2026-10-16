package eu.kanade.tachiyomi.data.download

import android.content.Context
import android.content.SharedPreferences
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import java.io.IOException
import java.util.UUID

class DownloadStoreDurabilityTest {
    private class Fixture {
        val values=mutableMapOf<String,String>();var failCommit=false
        val context=mockk<Context>();val prefs=mockk<SharedPreferences>();val editor=mockk<SharedPreferences.Editor>()
        val source=mockk<HttpSource>();val sources=mockk<SourceManager>()
        val getManga=mockk<GetManga>();val getChapter=mockk<GetChapter>()
        val manga=Manga.create().copy(id=42,source=17,url="/fixture")
        val chapter=Chapter.create().copy(id=71,mangaId=42,url="/fixture/chapter")
        val owner=UUID.randomUUID().toString()
        private val staged=mutableMapOf<String,String?>();private var clear=false
        init {
            every {context.getSharedPreferences(any(),any())} returns prefs
            every {prefs.all} answers {values.toMap()}
            every {prefs.getString(any(),any())} answers {values[firstArg()] ?: secondArg()}
            every {prefs.edit()} answers {staged.clear();clear=false;editor}
            every {editor.putString(any(),any())} answers {staged[firstArg()]=secondArg();editor}
            every {editor.remove(any())} answers {staged[firstArg()]=null;editor}
            every {editor.clear()} answers {clear=true;editor}
            every {editor.commit()} answers {
                if(failCommit) false else {if(clear)values.clear();staged.forEach {(k,v)->if(v==null)values.remove(k) else values[k]=v};true}
            }
            every {sources.get(17)} returns source
            coEvery {getManga.await(42)} returns manga
            coEvery {getChapter.await(71)} returns chapter
        }
        fun store()=DownloadStore(context,sources,Json {ignoreUnknownKeys=true},getManga,getChapter)
        fun download()=Download(source,manga,chapter,owner)
    }
    @Test fun `cancelled restored event cannot be republished and never returns after reconstruction`() {
        val f=Fixture();val store=f.store();val entry=f.download();store.addAll(listOf(entry))
        val oldStartupSnapshot=store.restore().single()
        assertTrue(store.isCurrent(oldStartupSnapshot));store.remove(entry)
        assertFalse(store.isCurrent(oldStartupSnapshot));assertTrue(f.store().restore().isEmpty())
        verify(exactly=0) {f.editor.apply()}
    }
    @Test fun `replaced event rejects stale restore and another owner cannot clear it`() {
        val f=Fixture();val store=f.store();val first=f.download();store.addAll(listOf(first))
        val snapshot=store.restore().single();val replacement=f.download();store.addAll(listOf(replacement))
        assertFalse(store.isCurrent(snapshot));assertTrue(store.isCurrent(replacement))
        store.clearOwner(UUID.randomUUID().toString());assertTrue(store.isCurrent(replacement))
        store.clearOwner(f.owner);assertTrue(f.store().restore().isEmpty())
    }
    @Test fun `failed durable commit is an error rather than successful queue acceptance`() {
        val f=Fixture();f.failCommit=true
        assertThrows(IOException::class.java) {f.store().addAll(listOf(f.download()))}
        assertTrue(f.values.isEmpty())
    }
}
