package eu.kanade.tachiyomi.novels

import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

/** Bounded replay of designated owner-authorized responses. Never contacts a live server. */
fun main(args: Array<String>) = runBlocking {
    val evidence = File(args[0]); val web = File(args[1])
    var checks = 0
    fun verify(condition: Boolean, name: String) { check(condition) { name }; checks++ }
    val public = NovelChapter("https://sunovels.com/novel/birth-of-the-demonic-sword/0", "ولادة نوح", 0)
    val target = NovelChapter("https://sunovels.com/novel/i-have-an-apocalypse-city/1", "1 الفصل", 0,
        available = false, unavailableReason = SunovelsSource.LOCKED_MESSAGE,
        sunovelsState = SunovelsChapterState.EXPLICITLY_LOCKED, sunovelsEvidence = SunovelsChapterEvidence.PUBLIC_READER)
    val targetWeb = File(web, "locked.html").readText(); val publicWeb = File(web, "public.html").readText()
    val targetJson = File(evidence, "locked-reader-fixture.json").readText()
    val publicJson = File(evidence, "public-reader-fixture.json").readText()
    val calls = mutableListOf<String>(); var apiCode = 200; var apiBody = targetJson; var delayMs = 0L
    val client = OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
        val u = chain.request().url.toString(); synchronized(calls) { calls.add(u) }
        check(chain.request().header("Authorization") == null && chain.request().header("Cookie") == null)
        if (delayMs > 0) Thread.sleep(delayMs)
        val mobile = chain.request().url.host == "adminx.sunovels.com"
        check(mobile || u == target.url || u == public.url) { "NO GATEWAY, D1, CATALOG CRAWL OR OTHER HOST" }
        val body = if (mobile) apiBody else if (u == target.url) targetWeb else publicWeb
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("captured authorized response")
            .header("Content-Type", if (mobile) "application/json; charset=utf-8" else "text/html; charset=utf-8")
            .header("Retry-After", "60").code(if (mobile) apiCode else 200)
            .body(body.toResponseBody((if (mobile) "application/json" else "text/html").toMediaType())).build()
    }.build()
    fun source() = SunovelsSource(NovelHttp(client))
    val source = source()
    val mapping = source.mobileIdentity(targetWeb, target)
    verify(mapping.chapterId == 314849L && mapping.novelId == 1914L && mapping.number == "1", "exact public route to official numeric chapter identity")
    verify(source.mobileIdentity(publicWeb, public).chapterId == 45919L, "chapter zero mapping preserved")
    verify(runCatching { source.mobileIdentity(targetWeb, public) }.isFailure, "foreign novel mapping rejected")
    val model = SunovelsMobileApi.parse(targetJson, mapping)
    verify(model.html.length == 12776 && model.title.trim() == "الدخول الى اللعبة", "actual authorized mobile story and subtitle decoded without private key")
    verify(SunovelsMobileApi.parse(publicJson, source.mobileIdentity(publicWeb, public)).number == "0", "actual mobile positive control handles chapter zero")
    for ((field,value) in listOf("id" to JsonPrimitive(1), "chapterNumber" to JsonPrimitive(2), "novel" to buildJsonObject {put("id",1914);put("url","wrong-novel")})) {
        val root = Json.parseToJsonElement(targetJson).jsonObject
        val changed = JsonObject(root + ("chapter" to JsonObject(root["chapter"]!!.jsonObject + (field to value))))
        verify(runCatching { SunovelsMobileApi.parse(changed.toString(), mapping) }.isFailure, "mismatched mobile $field rejected")
    }
    for (replacement in listOf("", "<p>السابق</p><p>التالي</p>")) {
        val root = Json.parseToJsonElement(targetJson).jsonObject
        apiBody = JsonObject(root + ("chapter" to JsonObject(root["chapter"]!!.jsonObject + ("content" to JsonPrimitive(replacement))))).toString()
        verify(runCatching { source().resolvePublicChapter(target, availabilityCheck = true) }.isFailure, "empty/navigation content cannot become readable")
    }
    apiBody = targetJson
    val before = calls.size
    val resolved = source.resolvePublicChapter(target, availabilityCheck = true)
    verify(calls.size == before + 2 && calls.takeLast(2) == listOf(target.url, mapping.endpoint), "integrated web-locked reader uses exactly one identity page and one mobile GET")
    verify(resolved.report.provenance == "MOBILE_READER" && resolved.report.transport?.status == 200 && resolved.report.transport?.finalUrl == mapping.endpoint && resolved.report.webTransport?.status == 200, "correlated web and real mobile transport evidence")
    verify(resolved.text.paragraphs.sumOf { it.length } > 5000 && resolved.report.strategy == "MOBILE_JSON_HTML", "actual Arabic story reaches existing NovelText reader model")
    verify(resolved.report.chapter.id == target.id && !target.available, "persisted URL and original cached lock row never mutated")
    verify(SunovelsReaderDiagnostics.get(target.id)!!.copyReport().contains("requestedUrl=${mapping.endpoint}"), "diagnostic reports actual API request URL")
    // Regression: a verified mobile read supersedes the same URL's cached web lock and later index hints.
    val verified = SunovelsAccess.readable(target)
    val indexLock = target.copy(sunovelsEvidence = SunovelsChapterEvidence.INDEX)
    val projected = SunovelsAccess.mergeIndex(indexLock, verified)
    verify(projected.available && projected.unavailableReason == null &&
        SunovelsAccess.state(projected) == SunovelsChapterState.VERIFIED_READABLE,
        "cached explicit lock -> verified mobile -> Start Reading remains eligible after index refresh")
    val owner = Novel(SunovelsSource.SOURCE_ID, target.url.substringBeforeLast('/'), "test")
    val readerRow = SunovelsAccess.readerCandidate(target, verified)
    val beforeMemoryOpen = calls.size
    SunovelsSource.requireResolutionOwnership(owner, verified)
    SunovelsSource.requireResolutionOwnership(owner, readerRow)
    SunovelsSource.requireResolutionOwnership(owner, projected)
    SunovelsSource.validateText(resolved.text)
    verify(readerRow.id == target.id && projected.id == target.id && calls.size == beforeMemoryOpen,
        "saved Resume and refreshed index pass real reader ownership gate with verified memory and no refetch")
    verify(SunovelsAccess.mergeIndex(indexLock, verified.copy(url = public.url)) == indexLock,
        "foreign chapter proof cannot change access projection")
    verify(!SunovelsAccess.mergeIndex(indexLock, target).available,
        "confirmed reader lock stays locked without newer readable proof")
    val ambiguousIndex = indexLock.copy(publishedRows = listOf(NovelPublishedChapterRow(target.url,"a"), NovelPublishedChapterRow(target.url,"b")))
    verify(!SunovelsAccess.mergeIndex(ambiguousIndex, verified).available,
        "positive proof never resolves ambiguous published rows")
    val missingIndex = indexLock.copy(sunovelsState = SunovelsChapterState.MISSING)
    verify(SunovelsAccess.mergeIndex(missingIndex, verified) == missingIndex,
        "source-backed missing index row remains protected")
    verify(SunovelsAccess.failed(verified, java.io.IOException("offline")) == verified,
        "transient failure preserves verified mobile access")
    val persistedProof = Json.decodeFromString<NovelChapter>(Json.encodeToString(verified))
    verify(SunovelsAccess.mergeIndex(target, persistedProof).available,
        "restored reader evidence repairs a persisted lock-only index projection")
    val countPublic = calls.size
    val positive = source.resolvePublicChapter(public, availabilityCheck = true)
    verify(calls.size == countPublic + 1 && positive.report.deliveryUrl == null && positive.text.paragraphs.size == 14, "public HTML positive control unchanged and avoids unnecessary mobile request")
    val eligibleIndex = SunovelsAccess.mobileIndexCandidate(indexLock)
    verify(eligibleIndex.available && eligibleIndex.unavailableReason == null &&
        SunovelsAccess.state(eligibleIndex) != SunovelsChapterState.VERIFIED_READABLE,
        "web lock hint permits first-tap preflight without fabricating readable proof")
    val oldReaderLock = SunovelsAccess.mobileIndexCandidate(target)
    verify(oldReaderLock.available && oldReaderLock.sunovelsState == target.sunovelsState &&
        oldReaderLock.sunovelsEvidence == target.sunovelsEvidence && oldReaderLock.unavailableReason == target.unavailableReason &&
        SunovelsAccess.mobileIndexCandidate(ambiguousIndex) == ambiguousIndex,
        "legacy cached web lock permits automatic mobile preflight, retaining denial proof; ambiguity never enabled")
    val firstTap = source().resolvePublicChapter(indexLock) // No availabilityCheck/manualRetry.
    verify(firstTap.text == resolved.text && firstTap.report.outcome in setOf(SunovelsOutcome.VERIFIED_READABLE, SunovelsOutcome.IDENTITY_MISMATCH_REPAIRED),
        "indexed web-locked chapter opens naturally via mobile reader with no manual check")
    verify(source().resolvePublicChapter(oldReaderLock).text == resolved.text,
        "cached explicit web lock does not require a manual availability operation before first opening")
    val authorizedDownload = source().chapterForDownload(indexLock)
    verify(authorizedDownload == resolved.text && calls.none { "/v2/" in it || "isDownload" in it },
        "owner-authorized offline download reuses verified reader path, never protected download API")
    val currentEvidence = File(args.getOrElse(2) { "tools/sunovels-direct/fixtures" })
    run {
        val ownerIndex = source.parseIndex(org.jsoup.Jsoup.parse(currentEvidence.resolve("apocalypse-web-index.html").readText(), owner.url), owner, 1)
        val official = Json.parseToJsonElement(currentEvidence.resolve("apocalypse-mobile-index.json").readText()).jsonObject
        val rows = official["chaptersList"]!!.jsonArray
        verify(ownerIndex.declaredTotal == 1 && ownerIndex.chapters.size == 1 && ownerIndex.nextPage == null &&
            rows.size == 1 && official["total"]!!.jsonPrimitive.int == 1,
            "live web and official mobile catalog independently confirm one available index row, no invented chapters")
        verify(rows.single().jsonObject["id"]!!.jsonPrimitive.long == mapping.chapterId &&
            rows.single().jsonObject["novel"]!!.jsonObject["id"]!!.jsonPrimitive.long == mapping.novelId &&
            ownerIndex.chapters.single().id == target.id,
            "official chapter server ID, novel owner and saved canonical identity agree")
    }
    val countDownload = calls.size
    val deniedDownload = runCatching { SunovelsSource(NovelHttp(client), offlineStorageAuthorized = false).chapterForDownload(target) }.exceptionOrNull() as? NovelSourceFailure
    verify(deniedDownload?.code == "SUN_MOBILE_DOWNLOAD_AUTH_REQUIRED" && calls.size == countDownload + 1 && calls.last() == target.url, "reader content never reused to bypass separately forbidden download endpoint")
    verify(source.chapterForDownload(public.copy(title = positive.report.pageTitle!!)).paragraphs == positive.text.paragraphs, "existing public downloads preserve exact story text")
    apiCode = 403
    val denied = runCatching { source().resolvePublicChapter(target, availabilityCheck = true) }.exceptionOrNull() as? NovelSourceFailure
    val denial = SunovelsReaderDiagnostics.get(target.id)!!
    verify(denied?.code == "HTTP_403" && !denied.sourceVerified && denial.transport?.status == 403 && denial.provenance == "MOBILE_API_DIAGNOSTIC", "server authorization failure preserved and never reclassified as readable")
    apiCode = 200
    val root = Json.parseToJsonElement(targetJson).jsonObject
    apiBody = JsonObject(root + ("chapter" to JsonObject(root["chapter"]!!.jsonObject + ("isLocked" to JsonPrimitive(true))))).toString()
    val lock = runCatching { source().resolvePublicChapter(target, availabilityCheck = true) }.exceptionOrNull() as? NovelSourceFailure
    verify(lock?.code == "SUN_LOCKED" && lock.sourceVerified && SunovelsReaderDiagnostics.get(target.id)?.provenance == "LIVE_MOBILE_LOCK", "genuine mobile lock remains protected even with content-shaped fields")
    apiBody = targetJson; delayMs = 100
    val concurrent = source(); val beforeConcurrent = calls.size
    val a = async { concurrent.resolvePublicChapter(target, availabilityCheck = true) }; val b = async { concurrent.resolvePublicChapter(target, availabilityCheck = true) }
    verify(a.await().text == b.await().text && calls.size == beforeConcurrent + 2, "coalesced same-chapter requests share the web and mobile fetch")
    val ambiguous = target.copy(publishedRows = listOf(NovelPublishedChapterRow(target.url,"a"),NovelPublishedChapterRow(target.url,"b")))
    val beforeAmbiguous = calls.size
    verify(runCatching { source.resolvePublicChapter(ambiguous) }.isFailure && calls.size == beforeAmbiguous, "ambiguous addresses never fetched")
    for (url in listOf(mapping.endpoint.replace("https:","http:"),mapping.endpoint.replace("adminx.sunovels.com","evil.invalid"),mapping.endpoint.replace("/chapter/", "/v2/chapter/"), mapping.endpoint+"&token=fake", mapping.endpoint.replace("314849","../users")))
        verify(!NovelHttp.sunovelsMobileUrl(url), "unapproved endpoint rejected: $url")
    val item = NovelLibraryItem(Novel(SunovelsSource.SOURCE_ID,target.url.substringBeforeLast('/'),"test"),position=NovelReadingPosition(target,8,17,anchor="stable"),bookmarks=setOf(target.id))
    verify(Json.decodeFromString<NovelLibraryItem>(Json.encodeToString(item)) == item, "saved edition, Resume, bookmark and paragraph offsets unchanged")
    println("PASS $checks mobile integration contracts; ${calls.size} replay HTTP requests; target paragraphs=${resolved.text.paragraphs.size}, characters=${resolved.text.paragraphs.sumOf {it.length}}; no Android runtime claimed")
    client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()
}
