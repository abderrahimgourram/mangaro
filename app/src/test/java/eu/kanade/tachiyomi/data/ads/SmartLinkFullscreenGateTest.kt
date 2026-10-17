package eu.kanade.tachiyomi.data.ads

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

/** Persisted policy fixtures and static UI wiring checks, not Android rendering proof. */
class SmartLinkFullscreenGateTest {
    private val owner=adHash("account-a")
    private fun restart(policy: SmartLinkPolicy)=SmartLinkPolicy(
        Json.decodeFromString(Json.encodeToString(SmartLinkState.serializer(),policy.state)),
    )
    private fun complete(policy: SmartLinkPolicy, kind: String, work: String, chapter: Int) {
        val key=adHash(kind,work,"chapter-$chapter")
        policy.observe(owner,kind,key,false);policy.complete(owner,kind,key)
    }

    @Test fun switchingWorksAndRestartKeepBothTwentyChapterCyclesAndExactDestination() {
        for(kind in listOf("novel","manga")) {
            var policy=SmartLinkPolicy()
            for(i in 1..40) {
                val work=if(i%2==0) "work-a" else "work-b"
                repeat(3) {complete(policy,kind,work,i)} // repeated completion/recomposition is inert
                policy=restart(policy)
                val gate=policy.transition(owner,kind,work,"chapter-$i","chapter-${i+1}","page:8")
                if(i%20!=0) {assertNull(gate);continue}
                val pending=requireNotNull(gate)
                assertFalse(pending.satisfied)
                assertEquals("chapter-${i+1}",pending.destination)
                policy.prepare(owner,pending.id,"browser-$i",i*10_000L)
                policy.accepted("browser-$i",i*10_000L)
                policy=restart(policy)
                assertFalse(policy.state.readerTransitions[adHash(owner,kind)]!!.satisfied)
                policy.away("browser-$i");policy.returned(owner,i*10_000L+500)
                policy=restart(policy)
                val ready=policy.transition(owner,kind,work,"chapter-$i","chapter-${i+1}","page:8")!!
                assertTrue(ready.satisfied);assertEquals("page:8",ready.payload)
                policy.applied(owner,kind,work,ready.destination)
                assertNull(policy.dueBoundary(owner,kind))
                assertTrue(policy.state.readerTransitions.isEmpty())
            }
            assertEquals(40L,policy.state.chapters[adHash(owner,kind)]!!.count)
            assertEquals(40L,policy.state.chapters[adHash(owner,kind)]!!.servicedCount)
        }
    }

    @Test fun historicalProgressDoesNotCreateRetroactiveAdvertisingDebt() {
        val policy=SmartLinkPolicy()
        for(kind in listOf("novel","manga")) {
            for(i in 1..100) {
                policy.observe(owner,kind,"historical-$i",true)
                policy.complete(owner,kind,"historical-$i")
            }
            val restored=restart(policy)
            assertEquals(0L,restored.state.chapters[adHash(owner,kind)]!!.count)
            assertNull(restored.transition(owner,kind,"new-work","1","2",null))
        }
    }

    @Test fun readerGateIsFullWindowWithExplicitActionAndNoChapterEndAdvertisingButton() {
        val root=generateSequence(File(System.getProperty("user.dir"))) {it.parentFile}
            .first {File(it,"app/src/main").isDirectory}
        fun source(path: String)=File(root,"app/src/main/java/$path").readText()
        val controller=source("eu/kanade/tachiyomi/data/ads/SmartLinkController.kt")
        val page=source("eu/kanade/tachiyomi/data/ads/ReaderAdPageActivity.kt")
        assertTrue(controller.contains("activity.startActivity(ReaderAdPageActivity.intent("))
        assertFalse(controller.contains("android.app.Dialog("))
        assertTrue(page.contains("class ReaderAdPageActivity : BaseActivity()"))
        assertTrue(page.contains("if (isFinishing)"));assertTrue(page.contains("controller.closeReaderPage("))
        assertTrue(page.contains("متابعة القراءة"));assertTrue(page.contains("افتح إعلانًا واحدًا للمتابعة"))
        assertTrue(page.contains("شاهد إعلان للإكمال"));assertTrue(page.contains("إعلان خارجي · Adsterra · asiafilm.org"))
        assertFalse(page.contains("وليست إثبات مشاهدة"))
        val novel=source("eu/kanade/presentation/novels/NovelReaderScreen.kt")
        val boundary=source("eu/kanade/tachiyomi/data/ads/NativeBoundaryAd.kt")
        assertFalse(novel.contains("SmartLinkChapterBoundary"));assertFalse(boundary.contains("SmartLinkChapterBoundary"))
        val download=source("eu/kanade/tachiyomi/data/ads/DownloadAdGate.kt")
        assertFalse(download.contains("SmartLinkChapterBoundary"))
        assertTrue(download.contains("Text(\"فتح التنزيل\""))
        assertTrue(download.contains("\${batch.returnedActions} / \${batch.required}"))
        assertTrue(download.contains("افتح الإعلانات المطلوبة لبدء التنزيل"))
        assertTrue(download.contains("Text(\"فتح إعلان\")"))
        assertTrue(download.contains("إعلان خارجي · Adsterra · asiafilm.org"))
        assertFalse(download.contains("خطوة تنقل محلية"));assertFalse(download.contains("قد يعرض المتصفح"))
        // The queue remains behind its existing durable unlock and one-shot continuation.
        assertTrue(download.contains("batch?.unlocked==true"));assertTrue(download.contains("next.run()"))
    }
}
