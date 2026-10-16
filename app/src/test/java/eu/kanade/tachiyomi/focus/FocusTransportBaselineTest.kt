package eu.kanade.tachiyomi.focus
import eu.kanade.tachiyomi.novels.NovelHttp
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import io.kotest.matchers.shouldBe
class FocusTransportBaselineTest {
    @Test fun nativeHostLimitContentionBaseline():Unit=runBlocking {
        val times=mutableListOf<Double>()
        repeat(3) {
            val start=CompletableDeferred<Unit>();val seen=AtomicInteger()
            val client=OkHttpClient.Builder().addInterceptor {c ->
                if(c.request().url.encodedPath.startsWith("/bulk")) {seen.incrementAndGet();start.complete(Unit);Thread.sleep(400)}
                Response.Builder().request(c.request()).protocol(Protocol.HTTP_1_1).code(200).message("controlled baseline").body("fixture".toResponseBody()).build()
            }.build()
            val http=NovelHttp(client)
            val jobs=List(2) {i -> async {http.get("https://seanovel.org/bulk-$i")}}
            start.await();delay(50)
            val at=System.nanoTime();http.get("https://seanovel.org/visible");times+=(System.nanoTime()-at)/1e6
            jobs.awaitAll();seen.get() shouldBe 2
        }
        File(File(System.getProperty("java.io.tmpdir"),"mangaro-smart-focus").apply {mkdirs()},"transport-before.json").writeText("{\"fixture\":\"two400msGETs_then_visibleGET\",\"ms\":$times}")
    }
    @Test fun focusedReaderDoesNotWaitForBulkHostSlots():Unit=runBlocking {
        val times=mutableListOf<Double>()
        repeat(3) {
            val lease=FocusEngine.enter(FocusTarget(FocusContext.NOVEL_READER,"sea","work","visible"))
            try {
                val start=CompletableDeferred<Unit>();val seen=AtomicInteger()
                val client=OkHttpClient.Builder().addInterceptor {c ->
                    if(c.request().url.encodedPath.startsWith("/bulk")) {seen.incrementAndGet();start.complete(Unit);Thread.sleep(400)}
                    Response.Builder().request(c.request()).protocol(Protocol.HTTP_1_1).code(200).message("controlled fixture").body("fixture".toResponseBody()).build()
                }.build()
                val http=NovelHttp(client)
                val jobs=List(2) {i -> async(FocusTask(WorkPriority.DOWNLOAD,"sea","work","$i")) {http.get("https://seanovel.org/bulk-$i")}}
                start.await();delay(50)
                val at=System.nanoTime()
                withContext(FocusTask(WorkPriority.VISIBLE,"sea","work","visible")) {http.get("https://seanovel.org/visible")}
                times+=(System.nanoTime()-at)/1e6
                jobs.awaitAll();seen.get() shouldBe 2
            } finally {lease.close()}
        }
        File(File(System.getProperty("java.io.tmpdir"),"mangaro-smart-focus").apply {mkdirs()},"transport-after.json").writeText("{\"fixture\":\"two400msGETs_then_visibleGET\",\"ms\":$times}")
        // Both bulk jobs must complete; the visible request must avoid their 350ms residual delay.
        check(times.all {it<200}) {"Interactive request waited behind bulk work: $times"}
    }
    @Test fun switchingFromIdleBulkQueuePromotesReaderAheadOfBacklog():Unit=runBlocking {
        val two=CompletableDeferred<Unit>();val seen=AtomicInteger();val order=java.util.concurrent.CopyOnWriteArrayList<String>();val release=java.util.concurrent.CountDownLatch(1)
        val http=NovelHttp(OkHttpClient.Builder().addInterceptor {c ->
            order+=c.request().url.encodedPath
            if(c.request().url.encodedPath.startsWith("/bulk")) {val n=seen.incrementAndGet();if(n==2)two.complete(Unit);if(n<=2) check(release.await(5,java.util.concurrent.TimeUnit.SECONDS)) else Thread.sleep(20)}
            Response.Builder().request(c.request()).protocol(Protocol.HTTP_1_1).code(200).message("transition fixture").body("fixture".toResponseBody()).build()
        }.build())
        val downloads=List(20) {i -> async(FocusTask(WorkPriority.DOWNLOAD,"sea","work","$i")) {http.get("https://seanovel.org/bulk-$i")}}
        two.await()
        val lease=FocusEngine.enter(FocusTarget(FocusContext.NOVEL_READER,"sea","work","visible"))
        try {
            val reader=async(FocusTask(WorkPriority.VISIBLE,"sea","work","visible")) {http.get("https://seanovel.org/visible")}
            withTimeout(2000) {while(FocusEngine.metrics().active<3) delay(1)}
            release.countDown();reader.await();downloads.awaitAll()
            // Two already-running requests may finish, and the independent fair download lane
            // may submit one request concurrently with the reserved reader. Never the whole batch.
            check(order.indexOf("/visible") in 2..3) {"Reader was queued behind bulk work: $order"}
            seen.get() shouldBe 20
            File(File(System.getProperty("java.io.tmpdir"),"mangaro-smart-focus").apply {mkdirs()},"transition-after.json")
                .writeText("{\"readerRequestOrdinal\":${order.indexOf("/visible")+1},\"backgroundCompleted\":${seen.get()}}")
        } finally {release.countDown();lease.close()}
    }
}
