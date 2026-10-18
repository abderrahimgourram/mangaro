package eu.kanade.tachiyomi.search

import eu.kanade.tachiyomi.novels.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.*
import java.util.concurrent.TimeUnit
import kotlin.io.path.*

/** Two details from actual prior search snapshot, no new catalog requests or chapter bodies. */
@EnabledIfEnvironmentVariable(named="MANGARO_PUBLIC_DETAIL_PROBE",matches="1")
class WorkIdentityPublicDetailProbeTest {
    @Test fun actualObservedNovelPairThroughProductionGridProjection()=runBlocking {
        val dir=Path.of(System.getenv("MANGARO_SEARCH_EVIDENCE_DIR"))
        val input=Json.parseToJsonElement(dir.resolve("public-novel-search.json").readText()).jsonObject["rows"]!!.jsonArray
        val rows=input.map {it.jsonObject}.filter {it["title"]!!.jsonPrimitive.content=="عبد الظل"}.map {
            Novel(it["source"]!!.jsonPrimitive.content,it["editionReference"]!!.jsonPrimitive.content,it["title"]!!.jsonPrimitive.content)
        }
        check(rows.size==2)
        var requests=0
        val statuses=mutableListOf<String>()
        val client=OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).connectTimeout(8,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS)
            .callTimeout(20,TimeUnit.SECONDS).addInterceptor {chain->
                check(chain.request().url.isHttps && chain.request().url.host in setOf("cenele.com","kolnovel.com"))
                check(requests<2);requests++
                val response=chain.proceed(chain.request())
                statuses+="host=${chain.request().url.host} status=${response.code}"
                val body=response.body;val buffer=body.source();buffer.request(4_000_001)
                check(buffer.buffer.size<=4_000_000);val bytes=buffer.readByteArray();body.close()
                response.newBuilder().body(bytes.toResponseBody(body.contentType())).build()
            }.build()
        val sources=listOf(CeneleSource(NovelHttp(client)),KolNovelSource(NovelHttp(client)))
        val repo=mockk<NovelRepository>()
        every{repo.activeSources} returns sources
        every{repo.catalog} returns MutableStateFlow(NovelCatalogState())
        val selector=NovelSearchSelection(repo)
        val before=selector.presentation(rows,emptyList())
        val details=mutableListOf<Novel>()
        val failures=mutableListOf<String>()
        for(row in rows) {
            try {details+=sources.single {it.id==row.sourceId}.details(row)}
            catch(e:Exception){failures+="source=${row.sourceId} category=${e.javaClass.simpleName}";details+=row}
            delay(1200)
        }
        val after=selector.presentation(details,emptyList())
        val candidates=details.map {selector.candidate(it,0)}
        dir.resolve("public-novel-detail-resolution.json").writeText(WorkIdentityDiagnostics.snapshot("عبد الظل",candidates,after.groups))
        dir.resolve("public-detail-status.txt").writeText("requests=$requests beforeCards=${before.works.size} afterCards=${after.works.size} retainedEditions=${after.works.sumOf {it.editions.size}}\n"+statuses.joinToString("\n")+"\n"+failures.joinToString("\n"))
        println("PUBLIC_DETAIL requests=$requests before=${before.works.size} after=${after.works.size} editions=${after.works.sumOf {it.editions.size}} failures=${failures.size}")
    }
}
