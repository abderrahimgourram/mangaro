package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.ui.reader.model.*
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.network.HttpException
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.IOException

class ReaderReliabilityTest {
    private fun chapter(id: Long)=ReaderChapter(Chapter.create().copy(id=id,mangaId=10,lastPageRead=9))
    private fun loader(factory: (ReaderChapter)->PageLoader)=ChapterLoader(mockk(relaxed=true),mockk(relaxed=true),
        mockk(relaxed=true),Manga.create().copy(id=10,source=44),mockk(relaxed=true),factory)
    private fun pages(local: Boolean=false)=object:PageLoader() {
        override var isLocal=local
        override suspend fun getPages()=listOf(ReaderPage(0),ReaderPage(1))
    }
    @Test fun unreadChapterWithSamePageCountStartsAtOwnFirstPage() {
        val p=ReaderPositionOwnership(1,9)
        assertEquals(9,p.enter(1,0,false,19));assertEquals(0,p.enter(2,0,false,19))
        assertEquals(9,p.enter(1,9,false,19))
    }
    @Test fun partialChapterAndPendingAdPageHaveExplicitOwnership() {
        val p=ReaderPositionOwnership(1,9)
        assertEquals(6,p.enter(2,6,false,19));assertEquals(0,p.enter(1,0,false,19))
        assertEquals(16,p.enter(3,0,false,19,16))
        assertEquals(16,ReaderPositionOwnership(3,16).enter(3,0,false,19))
        assertFalse(ReaderPositionOwnership.owns(2,1));assertTrue(ReaderPositionOwnership.owns(2,2))
    }
    @Test fun oldCallbackCannotOverwriteCurrentPageEvenAfterReturningToSameChapter() {
        val selections=ReaderPageSelection()
        val oldA=selections.selected(1);val b=selections.selected(2)
        assertFalse(selections.isVisible(oldA));assertTrue(selections.isLatest(1,oldA))
        val newA=selections.selected(1)
        assertFalse(selections.isLatest(1,oldA));assertTrue(selections.isVisible(newA))
        assertFalse(selections.isVisible(b));assertTrue(selections.isLatest(2,b))
    }
    @Test fun savedPageIsConsumedOnceAndClamped() {
        val p=ReaderPositionOwnership(1,99)
        assertEquals(4,p.enter(1,0,false,4));assertEquals(2,p.enter(1,2,false,4))
        assertEquals(0,p.enter(2,0,true,4))
    }
    @Test fun validLoadedChapterIsReusedWithoutAnotherRequest()=runBlocking {
        var calls=0;val c=chapter(1);val loader=loader {calls++;pages()}
        loader.loadChapter(c);loader.loadChapter(c)
        assertEquals(1,calls);assertEquals(2,c.pages!!.size);assertSame(c,c.pages!!.first().chapter)
    }
    @Test fun offlinePageLoaderRemainsOfflineAndPreservesChapterState()=runBlocking {
        val c=chapter(1);c.chapter.bookmark=true
        loader {pages(true)}.loadChapter(c)
        assertTrue(c.pageLoader!!.isLocal);assertEquals(9,c.requestedPage)
        assertTrue(c.chapter.bookmark);assertFalse(c.chapter.read)
    }
    @Test fun emptyPageListFailsRatherThanBecomingLoaded()=runBlocking {
        val c=chapter(1)
        val loader=loader {object:PageLoader() {override var isLocal=false;override suspend fun getPages()=emptyList<ReaderPage>()}}
        assertThrows(EmptyReaderPages::class.java) {runBlocking {loader.loadChapter(c)}}
        assertTrue(c.state is ReaderChapter.State.Error);assertNull(c.pageLoader)
    }
    @Test fun concurrentPreloadAndOpenIssueOneFetch()=runBlocking {
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var calls=0;val c=chapter(1)
        val loader=loader {object:PageLoader() {
            override var isLocal=false
            override suspend fun getPages():List<ReaderPage> {calls++;started.complete(Unit);release.await();return listOf(ReaderPage(0))}
        }}
        val a=async {loader.loadChapter(c)};started.await();val b=async {loader.loadChapter(c)}
        release.complete(Unit);awaitAll(a,b);assertEquals(1,calls)
    }
    @Test fun cancellationReleasesLoadingAndAllowsRetry()=runBlocking {
        val started=CompletableDeferred<Unit>();var attempt=0;val c=chapter(1)
        val loader=loader {object:PageLoader() {
            override var isLocal=false
            override suspend fun getPages():List<ReaderPage> {
                if(attempt++==0) {started.complete(Unit);awaitCancellation()}
                return listOf(ReaderPage(0))
            }
        }}
        val first=launch {loader.loadChapter(c)};started.await();first.cancelAndJoin()
        assertEquals(ReaderChapter.State.Wait,c.state);assertNull(c.pageLoader)
        loader.loadChapter(c);assertTrue(c.state is ReaderChapter.State.Loaded)
    }
    @Test fun timeoutAndServerFailureHaveOneBoundedRetry()=runBlocking {
        for(failure in listOf(IOException("temporary"),HttpException(503))) {
            var calls=0;var waits=0
            assertEquals("pages",ReaderLoadRecovery.request({waits++}) {if(calls++==0) throw failure;"pages"})
            assertEquals(2,calls);assertEquals(1,waits)
        }
    }
    @Test fun repeatedTransportFailureStopsAfterTwoAttempts()=runBlocking {
        var calls=0
        assertThrows(IOException::class.java) {runBlocking {ReaderLoadRecovery.request({}) {calls++;throw IOException()}}}
        assertEquals(2,calls)
    }
    @Test fun accessDenialMissingRateLimitEmptyAndCancellationNeverAutoRetry()=runBlocking {
        for(failure in listOf(HttpException(403),HttpException(404),HttpException(429),EmptyReaderPages(),CancellationException())) {
            var calls=0
            assertThrows(failure.javaClass) {runBlocking {ReaderLoadRecovery.request({fail("no backoff")}) {calls++;throw failure}}}
            assertEquals(1,calls)
        }
    }
}
