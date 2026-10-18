package eu.kanade.tachiyomi.search

import eu.kanade.tachiyomi.novels.*
import eu.kanade.tachiyomi.source.internal.mangastarz.MangaStarz
import eu.kanade.tachiyomi.source.internal.mangatime.MangaTime
import eu.kanade.tachiyomi.source.internal.teamx.TeamX
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.RankedSearchResult
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import mihon.domain.manga.model.toDomainManga
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton

/** Explicitly opted-in bounded observations through real adapters and production projection.
 * Only public work metadata is retained; no chapter requests, images, bodies, cookies or accounts. */
@EnabledIfEnvironmentVariable(named = "MANGARO_ATLAS_PUBLIC_CORPUS", matches = "1")
class AtlasPublicSearchCorpusTest {
    @Test fun captureBoundedActualSearchMetadata() = runBlocking<Unit> {
        val out = Path.of(requireNotNull(System.getenv("MANGARO_ATLAS_EVIDENCE_DIR")))
        Files.createDirectories(out)
        val previous = System.getenv("MANGARO_ATLAS_RESUME_CORPUS")?.let {
            Json.parseToJsonElement(Path.of(it).toFile().readText()).jsonObject
        }
        // The real application installs its JSON singleton. The isolated host probe must too.
        Injekt.addSingleton(Json { ignoreUnknownKeys = true })
        val transport = mutableListOf<JsonObject>()
        val failures = mutableListOf<JsonObject>()
        var requests = 0
        val hosts = setOf("starzmanga.com", "mangatime.org", "olympustaff.com", "cenele.com", "kolnovel.com", "api.mknov.com")
        val client = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(6, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request()
                check(req.url.isHttps && req.url.host in hosts && req.url.port == 443)
                check(requests < if (previous == null) 18 else 12) { "Public observation budget exhausted" }
                check(!req.url.encodedPath.contains("getChapters") && !req.url.encodedPath.contains("chapter", true))
                if (requests > 0) Thread.sleep(1_000)
                requests++
                var response = chain.proceed(req)
                transport += buildJsonObject { put("host", req.url.host); put("status", response.code) }
                if (response.code in setOf(301, 302, 307, 308)) {
                    val next = requireNotNull(response.header("Location")?.let(req.url::resolve))
                    check(next.isHttps && next.host == req.url.host && next.port == 443 &&
                        next.encodedPath.trimEnd('/') == req.url.encodedPath.trimEnd('/') &&
                        next.query == req.url.query) { "Unapproved metadata redirect" }
                    response.close()
                    check(requests < if (previous == null) 18 else 12)
                    Thread.sleep(1_000); requests++
                    response = chain.proceed(req.newBuilder().url(next).build())
                    transport += buildJsonObject { put("host", next.host); put("status", response.code); put("sameRouteRedirect", true) }
                }
                val body = response.body
                val buffer = body.source()
                buffer.request(4_000_001)
                check(buffer.buffer.size <= 4_000_000) { "Oversized public metadata" }
                val bytes = buffer.readByteArray()
                body.close()
                response.newBuilder().body(bytes.toResponseBody(body.contentType())).build()
            }.build()
        fun failure(source: String, query: String, stage: String, e: Exception) {
            failures += buildJsonObject {
                put("source", source); put("query", query); put("stage", stage); put("category", e.javaClass.simpleName)
            }
        }
        fun row(c: SearchWorkCandidate, url: String) = buildJsonObject {
            put("id", c.id); put("source", c.source); put("url", url); put("title", c.title); put("kind", c.kind.name)
            put("aliases", JsonArray(c.aliases.sorted().map(::JsonPrimitive)))
            put("originalTitle", c.originalTitle?.let(::JsonPrimitive) ?: JsonNull)
            put("author", c.author?.let(::JsonPrimitive) ?: JsonNull); put("artist", c.artist?.let(::JsonPrimitive) ?: JsonNull)
            put("format", c.format?.let(::JsonPrimitive) ?: JsonNull); put("edition", c.edition?.let(::JsonPrimitive) ?: JsonNull)
            put("year", c.year?.let(::JsonPrimitive) ?: JsonNull)
            put("externalIds", JsonObject(c.externalIds.mapValues { JsonPrimitive(it.value) }))
            put("origin", c.metadataOrigin)
            c.catalogIdentity?.let { identity -> put("catalog", buildJsonObject {
                put("route", identity.route); put("editionKey", identity.editionKey)
                put("reviewedWork", identity.reviewedWorkKey?.let(::JsonPrimitive) ?: JsonNull)
                put("providerId", identity.providerId?.let(::JsonPrimitive) ?: JsonNull)
            }) }
        }
        val snapshots = mutableListOf<JsonObject>()
        val comics: List<HttpSource> = listOf(MangaStarz(client), MangaTime(client), TeamX(client))
        val mangaEvidence = MangaSearchEvidence(mockk(), { error("Chapter request forbidden") })
        for (query in listOf("Solo Leveling", "The Beginning After The End")) {
            val before = mutableListOf<SearchWorkCandidate>()
            val after = mutableListOf<SearchWorkCandidate>()
            val records = mutableListOf<JsonObject>()
            for (source in comics) {
                val retained = previous?.get("queries")?.jsonArray?.map { it.jsonObject }
                    ?.firstOrNull { it["query"]?.jsonPrimitive?.content == query }?.get("records")?.jsonArray
                    ?.map { it.jsonObject.getValue("short").jsonObject }
                    ?.filter { it["source"]?.jsonPrimitive?.content == source.id.toString() }.orEmpty()
                val items = try {
                    if (retained.isNotEmpty()) retained.map { r -> SManga.create().apply {
                        url = r.getValue("catalog").jsonObject.getValue("route").jsonPrimitive.content
                        title = r.getValue("title").jsonPrimitive.content
                        author = r["author"]?.jsonPrimitive?.contentOrNull
                        artist = r["artist"]?.jsonPrimitive?.contentOrNull
                    } } else source.getSearchManga(1, query, FilterList()).mangas.take(12)
                }
                catch (e: Exception) { failure(source.id.toString(), query, "search", e); emptyList() }
                val sample = items.firstOrNull { SearchWorkIdentity.normalize(it.title) == SearchWorkIdentity.normalize(query) } ?: items.firstOrNull()
                items.forEachIndexed { index, item ->
                    val c = mangaEvidence.candidate(RankedSearchResult(source, item.toDomainManga(source.id)), 0)
                    before += c
                    val updated = if (item == sample) try {
                        source.getMangaUpdate(item.copy(), emptyList(), fetchDetails = true, fetchChapters = false).manga
                    } catch (e: Exception) { failure(source.id.toString(), query, "details", e); item } else item
                    val enriched = mangaEvidence.candidate(RankedSearchResult(source, updated.toDomainManga(source.id)), 0)
                    after += enriched
                    records += buildJsonObject {
                        put("short", row(c, source.getMangaUrl(item))); put("details", row(enriched, source.getMangaUrl(updated)))
                    }
                }
            }
            snapshots += buildJsonObject {
                put("query", query); put("kind", "COMIC"); put("records", JsonArray(records))
                put("before", Json.parseToJsonElement(WorkIdentityDiagnostics.snapshot(query, before, SearchResultPresentationFilter().present(0, before)!!)))
                put("after", Json.parseToJsonElement(WorkIdentityDiagnostics.snapshot(query, after, SearchResultPresentationFilter().present(0, after)!!)))
            }
        }
        if (previous != null) snapshots += previous.getValue("queries").jsonArray.map { it.jsonObject }
            .filter { it["kind"]?.jsonPrimitive?.content == "NOVEL" }
        else {
        val novels = listOf(CeneleSource(NovelHttp(client)), KolNovelSource(NovelHttp(client)), MknovSource(NovelHttp(client)))
        val repo = mockk<NovelRepository>()
        every { repo.catalog } returns MutableStateFlow(NovelCatalogState())
        every { repo.activeSources } returns novels
        val selector = NovelSearchSelection(repo)
        val query = "عبد الظل"
        val before = mutableListOf<Novel>(); val after = mutableListOf<Novel>(); val records = mutableListOf<JsonObject>()
        for (source in novels) {
            val items = try { source.search(query, 1).novels.take(12).filter(NovelContentTypeClassifier::eligible) }
            catch (e: Exception) { failure(source.id, query, "search", e); emptyList() }
            val sample = items.firstOrNull { SearchWorkIdentity.normalize(it.title) == SearchWorkIdentity.normalize(query) } ?: items.firstOrNull()
            items.forEach { n ->
                val detail = if (n == sample) try { source.details(n) }
                catch (e: Exception) { failure(source.id, query, "details", e); n } else n
                before += n; after += detail
                records += buildJsonObject { put("short", row(selector.candidate(n, 0), n.url)); put("details", row(selector.candidate(detail, 0), detail.url)) }
            }
        }
        snapshots += buildJsonObject {
            put("query", query); put("kind", "NOVEL"); put("records", JsonArray(records))
            put("before", Json.parseToJsonElement(WorkIdentityDiagnostics.snapshot(query, before.map { selector.candidate(it, 0) }, selector.presentation(before, emptyList()).groups)))
            put("after", Json.parseToJsonElement(WorkIdentityDiagnostics.snapshot(query, after.map { selector.candidate(it, 0) }, selector.presentation(after, emptyList()).groups)))
        }
        }
        out.resolve("public-search-corpus.json").toFile().writeText(buildJsonObject {
            put("schema", 1); put("observedAt", Instant.now().toString()); put("requests", requests)
            put("previousRequests", previous?.get("requests") ?: JsonPrimitive(0))
            put("retainedInitialCatalog", previous != null)
            put("queries", JsonArray(snapshots)); put("transport", JsonArray(transport)); put("failures", JsonArray(failures))
        }.toString())
        println("ATLAS_PUBLIC_OBSERVATION requests=$requests queries=${snapshots.size} classifiedFailures=${failures.size}")
    }
}
