package eu.kanade.tachiyomi.novels

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Explicit, serial, bounded observations through the shipping adapters. No UI/ad/account work. */
class NovelSourceReliabilityLiveTest {
    @TempDir lateinit var directory: File
    @Test
    @EnabledIfEnvironmentVariable(named="MANGARO_NOVEL_RELIABILITY_LIVE",matches="1")
    fun observeProductionAdaptersAndOfflineRoundTrips() = runBlocking<Unit> { withTimeout(600_000L) {
        val output=File(checkNotNull(System.getenv("MANGARO_NOVEL_HEALTH_OUTPUT")))
        val guard=Any(); var calls=0; var last=0L
        val blocked=mutableSetOf<String>(); val requests=mutableListOf<JsonObject>();val observations=mutableListOf<JsonObject>()
        val client=OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
            .connectTimeout(8,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS).callTimeout(20,TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request=chain.request();val host=request.url.host
                check(host in setOf("api.mknov.com","seanovel.org"))
                synchronized(guard) {
                    if(host in blocked) throw NovelSourceFailure("Upstream observation paused", "No further live requests", 403, code="HEALTH_UPSTREAM_BLOCKED")
                    check(calls<110) {"Health request budget exhausted"}
                    calls++
                    val wait=1000-(System.nanoTime()-last)/1_000_000
                    if(wait>0) Thread.sleep(wait)
                    last=System.nanoTime()
                }
                val response=chain.proceed(request)
                synchronized(guard) {
                    if(response.code in setOf(401,403,429)) blocked+=host
                    requests+=buildJsonObject {
                        put("url",request.url.toString());put("httpStatus",response.code)
                        response.header("Location")?.let { put("redirectHost",request.url.resolve(it)?.host.orEmpty()) }
                    }
                }
                response
            }.build()
        val http=NovelHttp(client)
        try {
            val sources=listOf(MknovSource(http),SeaNovelSource(http))
            val plan=Json.parseToJsonElement(File(checkNotNull(System.getenv("MANGARO_NOVEL_HEALTH_PLAN"))).readText()).jsonArray
            for(source in sources) {
                for(operation in listOf("popular","latest","search","genre")) {
                    try {
                        val page=when(operation) {
                            "search"->source.search(if(source.id==MknovSource.SOURCE_ID) "عبد الظل" else "تيرا",1)
                            "genre"->{val page=source.catalog(1,false,null);source.catalog(1,false,page.genres.firstOrNull()?.value)}
                            else->source.catalog(1,operation=="latest",null)
                        }
                        check(page.novels.isNotEmpty());check(page.novels.map{it.id}.distinct().size==page.novels.size)
                        observations+=buildJsonObject {put("sourceId",source.id);put("stage",operation);put("status","PASS");put("works",page.novels.size)}
                    } catch(e:Exception) { if(e is kotlinx.coroutines.CancellationException) throw e; observations+=error(source.id,operation,"",e) }
                }
                for(entry in plan.filter {it.jsonObject["sourceId"]!!.jsonPrimitive.content==source.id}.map{it.jsonObject}) {
                    val novel=Novel(source.id,entry["url"]!!.jsonPrimitive.content,entry["title"]!!.jsonPrimitive.content)
                    try {
                        val details=source.details(novel);check(details.id==novel.id)
                        val index=NovelChapterIndexer.collect(source,details)
                        check(index.complete && index.chapters.map{it.id}.distinct().size==index.chapters.size)
                        check(index.declaredTotal==index.chapters.size)
                        observations+=buildJsonObject {
                            put("sourceId",source.id);put("stage","index");put("url",novel.url);put("status","PASS")
                            put("title",details.title);put("uniqueChapters",index.chapters.size);put("declaredTotal",index.declaredTotal)
                            put("decimalIds",index.chapters.count {it.url.substringAfterLast('/').contains('.')})
                        }
                        val chapters=index.chapters
                        if(chapters.isEmpty())continue
                        val picks=linkedSetOf(0,1,chapters.size/2,chapters.lastIndex,minOf(99,chapters.lastIndex)).filter {it in chapters.indices}.toMutableSet()
                        chapters.indexOfFirst {it.url.substringAfterLast('/').contains('.')}.takeIf {it>=0}?.let(picks::add)
                        if(novel.url=="https://seanovel.org/novels/invincible") {
                            for(id in listOf("1620","1979"))chapters.indexOfFirst {it.url.endsWith("/"+id)}.takeIf{it>=0}?.let(picks::add)
                        }
                        if(novel.url=="https://mknov.com/novel/96")
                            chapters.indexOfFirst {it.url.endsWith("/20967")}.takeIf {it>=0}?.let(picks::add)
                        for(position in picks.sorted()) {
                            val chapter=chapters[position]
                            if(!chapter.available) {
                                observations+=buildJsonObject {put("sourceId",source.id);put("stage","text");put("url",chapter.url);put("status","ACCESS_UNAVAILABLE");put("reason","EXPLICIT_INDEX_RESTRICTION")};continue
                            }
                            try {
                                val text=source.chapter(chapter)
                                validateNovelRepositoryContent(source.id,text)
                                val quality=NovelContentQuality.assess(text)
                                val disk=NovelDownloadDisk(directory);disk.write(details.id,chapter.id,text)
                                check(NovelDownloadDisk(directory).read(details.id,chapter.id)==text) {"Offline roundtrip mismatch"}
                                // Preserve original URL identities and ordinary adjacent index routing.
                                check(chapters.getOrNull(position+1)?.order==position+1 || position==chapters.lastIndex)
                                observations+=buildJsonObject {
                                    put("sourceId",source.id);put("stage","text");put("url",chapter.url);put("workUrl",novel.url)
                                    put("status","PASS");put("quality",quality.verdict.name);put("qualityReason",quality.reason)
                                    put("paragraphs",text.paragraphs.size);put("characters",quality.characters)
                                    put("phoneticMarkers",quality.phoneticMarkers);put("distinctMarkers",quality.distinctMarkers)
                                    put("sha256",MessageDigest.getInstance("SHA-256").digest(text.paragraphs.joinToString("\n").toByteArray()).joinToString(""){"%02x".format(it)})
                                    put("offlineRoundTrip",true);put("indexPosition",position)
                                }
                            }catch(e:Exception){if(e is kotlinx.coroutines.CancellationException) throw e; observations+=error(source.id,"text",chapter.url,e)}
                        }
                    }catch(e:Exception){if(e is kotlinx.coroutines.CancellationException) throw e; observations+=error(source.id,"details/index",novel.url,e)}
                }
            }
        }finally {
            client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()
            val report=buildJsonObject {
                put("schemaVersion",1);put("requests",calls);put("scope","BOUNDED_PUBLIC_SAMPLE_NOT_ALL_CHAPTERS")
                put("androidUiAcceptance","NOT_TESTED");put("productionPublication",false)
                put("observations",JsonArray(observations));put("transport",JsonArray(requests))
            }
            output.parentFile.mkdirs();val temporary=File(output.parentFile,output.name+".part")
            temporary.writeText(report.toString());Files.move(temporary.toPath(),output.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)
            println("SOURCE_HEALTH requests=$calls observations=${observations.size}; sanitized report written, no prose retained")
        }
    } }
    private fun error(source:String,stage:String,url:String,e:Exception)=buildJsonObject {
        put("sourceId",source);put("stage",stage);put("url",url)
        val failure=e as? NovelSourceFailure
        put("status",when {
            failure?.code=="NOVEL_TEXT_CORRUPTED"->"CORRUPTED_UPSTREAM"
            source==MknovSource.SOURCE_ID && stage=="text" && failure?.code=="MKNOV_IDENTITY"->"UPSTREAM_IDENTITY_MISMATCH"
            failure?.httpStatus in setOf(401,403,429)->"ACCESS_UNAVAILABLE"
            failure?.httpStatus in setOf(404,410)->"UNAVAILABLE"
            e is java.io.IOException->"NETWORK_UNAVAILABLE"
            else->"PARSER_OR_CONTRACT_FAILURE"
        })
        put("reason",failure?.code ?: e.javaClass.simpleName)
        failure?.httpStatus?.let {put("httpStatus",it)}
    }
}
