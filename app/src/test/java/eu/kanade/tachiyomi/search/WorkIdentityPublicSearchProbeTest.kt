package eu.kanade.tachiyomi.search

import eu.kanade.tachiyomi.source.internal.mangalek.MangaLek
import eu.kanade.tachiyomi.source.internal.mangastarz.MangaStarz
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.novels.*
import eu.kanade.tachiyomi.ui.home.CatalogWorkEvidence
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.RankedSearchResult
import mihon.domain.manga.model.toDomainManga
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Path
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.writeText

/** Four anonymous search pages, real adapters and final production presentation. No chapter calls. */
@EnabledIfEnvironmentVariable(named="MANGARO_PUBLIC_SEARCH_PROBE",matches="1")
class WorkIdentityPublicSearchProbeTest {
    @Test fun boundedPublicSearchSnapshots() = runBlocking {
        val output=Path.of(System.getenv("MANGARO_SEARCH_EVIDENCE_DIR") ?: "build/reports/work-identity")
        Files.createDirectories(output)
        val count=AtomicInteger()
        val transport=mutableListOf<String>()
        val client=OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
            .connectTimeout(8,TimeUnit.SECONDS).readTimeout(12,TimeUnit.SECONDS).callTimeout(15,TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).addInterceptor { chain ->
                val req=chain.request()
                check(req.url.isHttps && req.url.host in setOf("mangalik.net","starzmanga.com","cenele.com","kolnovel.com"))
                check(count.get()<4)
                count.incrementAndGet()
                val response=chain.proceed(req)
                transport+="host=${req.url.host} status=${response.code}"
                val body=response.body
                val buffer=body.source()
                buffer.request(2_000_001)
                check(buffer.buffer.size<=2_000_000) {"oversized-public-search"}
                val bytes=buffer.readByteArray()
                body.close()
                response.newBuilder().body(bytes.toResponseBody(body.contentType())).build()
            }.build()
        val candidates=mutableListOf<SearchWorkCandidate>()
        val failures=mutableListOf<String>()
        CatalogWorkEvidence.replaceReviewedRegistry(java.io.File("src/main/res/raw/catalog_reviewed_links.json").let {
            if(it.exists()) it.readText() else java.io.File("app",it.path).readText()
        })
        val evidence=MangaSearchEvidence(mockk(),{emptyList()})
        listOf(MangaStarz(client),MangaLek(client)).forEach { source ->
            try {
                val rows=source.getSearchManga(1,"Otherworldly Evil Monarch",FilterList()).mangas.take(20)
                candidates+=rows.map { evidence.candidate(RankedSearchResult(source,it.toDomainManga(source.id)),0) }
            } catch(e:Exception) { failures+="comic source=${source.id} category=${e.javaClass.simpleName}" }
            delay(1200)
        }
        val comics=SearchResultPresentationFilter().present(0,candidates)!!
        output.resolve("public-comic-search.json").writeText(WorkIdentityDiagnostics.snapshot("Otherworldly Evil Monarch",candidates,comics))
        val novelCandidates=mutableListOf<SearchWorkCandidate>()
        listOf(CeneleSource(NovelHttp(client)),KolNovelSource(NovelHttp(client))).forEach { source ->
            try {
                novelCandidates+=source.search("عبد الظل",1).novels.take(20).filter(NovelContentTypeClassifier::eligible).map {
                    SearchWorkCandidate(it.id,it.sourceId,SearchWorkKind.NOVEL,it.title,
                        aliases=it.alternativeTitles.toSet(),author=it.author,originalTitle=it.originalTitle)
                }
            } catch(e:Exception) { failures+="novel source=${source.id} category=${e.javaClass.simpleName}" }
            delay(1200)
        }
        val novels=SearchResultPresentationFilter().present(0,novelCandidates)!!
        output.resolve("public-novel-search.json").writeText(WorkIdentityDiagnostics.snapshot("عبد الظل",novelCandidates,novels))
        output.resolve("public-probe-status.txt").writeText("requests=${count.get()}\n"+transport.joinToString("\n")+"\n"+failures.joinToString("\n"))
        println("PUBLIC_SEARCH requests=${count.get()} comicEditions=${candidates.size} comicCards=${comics.size} novelEditions=${novelCandidates.size} novelCards=${novels.size} failures=${failures.size}")
        // An inaccessible source is evidence of a blocked observation, never a fabricated PASS.
    }
}
