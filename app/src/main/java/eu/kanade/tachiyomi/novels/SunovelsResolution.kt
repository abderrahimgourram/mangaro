package eu.kanade.tachiyomi.novels

import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Ephemeral diagnostics, deliberately outside the persisted six-state chapter schema. No body/headers. */
enum class SunovelsOutcome { INDEXED_UNVERIFIED, VERIFIED_READABLE, EXPLICITLY_LOCKED, AMBIGUOUS_IDENTITY, PARSER_EMPTY,
    IDENTITY_MISMATCH_REPAIRED, IDENTITY_MISMATCH, NETWORK_TRANSIENT, RATE_LIMITED, ACCESS_RESTRICTED, NOT_FOUND, CACHED_VALID }
data class SunovelsTransport(val finalUrl: String, val status: Int? = null, val bytes: Int = 0,
    val contentType: String? = null, val cooldownMs: Long = 0)
internal data class SunovelsResolvedChapter(val text: NovelText, val report: SunovelsResolutionReport)
data class SunovelsResolutionReport(val chapter: NovelChapter, val outcome: SunovelsOutcome,
    val failureCode: String = "NONE", val pageTitle: String? = null, val pageNumber: String? = null,
    val strategy: String = "NONE", val characters: Int = 0, val paragraphs: Int = 0,
    val transport: SunovelsTransport? = null, val attempts: Int = 0, val provenance: String = "PUBLIC_READER",
    val previousAccessEvidence: String? = null, val deliveryUrl: String? = null, val webTransport: SunovelsTransport? = null, val indexRepair: ChapterPage? = null, val epoch: Long = 0, val recordedAtNanos: Long = System.nanoTime()) {
    fun failureTag(): String = "REASON: " + when {
        failureCode == "SUN_LOCKED" -> provenance
        failureCode in setOf("NUMBER_MISMATCH", "SUN_OWNERSHIP", "IDENTITY_MISMATCH", "SUN_DESTINATION") ->
            "OWNER_OR_NUMBER_MISMATCH (expected=${chapter.url.toHttpUrlOrNull()?.pathSegments?.lastOrNull()}, actual=$pageNumber)"
        failureCode in setOf("CONTAINER_EMPTY", "RESPONSE_INTERRUPTED") ->
            "PARSER_CONTAINER_EMPTY (http=${transport?.status}, bytes=${transport?.bytes ?: 0}, strategy=$strategy)"
        failureCode.startsWith("HTTP_") -> "HTTP_ERROR (status=${transport?.status})"
        failureCode in setOf("TIMEOUT", "NETWORK_IO", "NETWORK_FAILURE") -> "NETWORK_TIMEOUT_OR_IO"
        failureCode in setOf("AMBIGUOUS_ROW", "SUN_UNAVAILABLE") -> "AMBIGUOUS_CHAPTER_ID"
        else -> failureCode
    }
    fun copyReport(downloaded: Boolean = false): String {
        val remaining = ((transport?.cooldownMs ?: 0) - java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - recordedAtNanos)).coerceAtLeast(0)
        return listOf(
        "source=novel.sunovels", "novelSlug=${chapter.url.toHttpUrlOrNull()?.pathSegments?.getOrNull(1)}",
        "chapterId=${chapter.id}", "displayTitle=${chapter.title}", "pageTitle=$pageTitle", "pageNumber=$pageNumber",
        "indexNumber=${chapter.url.toHttpUrlOrNull()?.pathSegments?.lastOrNull()}", failureTag(),
        "canonicalUrl=${chapter.url}", "requestedUrl=${deliveryUrl ?: chapter.url}", "webHttp=${webTransport?.status}", "webBytes=${webTransport?.bytes}", "finalUrl=${transport?.finalUrl}", "http=${transport?.status}",
        "bytes=${transport?.bytes ?: 0}", "contentType=${transport?.contentType}", "strategy=$strategy",
        "characters=$characters", "paragraphs=$paragraphs", "outcome=$outcome", "failure=$failureCode",
        "access=${if (provenance == "INDEX_LOCK_HINT") SunovelsChapterState.INDEXED_UNVERIFIED else when(outcome) { SunovelsOutcome.VERIFIED_READABLE, SunovelsOutcome.IDENTITY_MISMATCH_REPAIRED, SunovelsOutcome.CACHED_VALID -> SunovelsChapterState.VERIFIED_READABLE
            SunovelsOutcome.INDEXED_UNVERIFIED -> SunovelsChapterState.INDEXED_UNVERIFIED
            SunovelsOutcome.EXPLICITLY_LOCKED -> SunovelsChapterState.EXPLICITLY_LOCKED
            SunovelsOutcome.AMBIGUOUS_IDENTITY -> SunovelsChapterState.AMBIGUOUS
            SunovelsOutcome.NOT_FOUND -> SunovelsChapterState.MISSING
            else -> SunovelsChapterState.TEMPORARILY_UNAVAILABLE }}",
        "evidence=$provenance", "accessEvidence=${when { outcome in setOf(SunovelsOutcome.VERIFIED_READABLE, SunovelsOutcome.IDENTITY_MISMATCH_REPAIRED, SunovelsOutcome.CACHED_VALID) -> "VERIFIED_READABLE"; provenance in setOf("INDEX_LOCK_HINT", "CACHED_EXPLICIT_LOCK", "LIVE_EXPLICIT_LOCK") -> provenance; else -> "UNVERIFIED" }}", "previousAccessEvidence=$previousAccessEvidence", "cache=$provenance", "attempts=$attempts", "automaticRetries=0",
        "cooldownMs=$remaining", "cooldownActive=${remaining > 0}", "downloaded=$downloaded"
    ).joinToString("\n")
    }
}
object SunovelsReaderDiagnostics {
    private val reports = LinkedHashMap<String, SunovelsResolutionReport>()
    @Synchronized fun get(url: String) = reports[url]
    @Synchronized internal fun retainedDiagnosticCharacters() = reports.values.sumOf {
        it.copyReport().length + (it.indexRepair?.chapters?.sumOf { chapter -> chapter.url.length + chapter.title.length } ?: 0)
    }
    @Synchronized internal fun record(report: SunovelsResolutionReport) {
        reports[report.chapter.id] = report
        while (reports.size > 32) reports.remove(reports.keys.first())
    }
    internal fun cached(chapter: NovelChapter, text: NovelText, origin: String) = record(SunovelsResolutionReport(
        chapter, SunovelsOutcome.CACHED_VALID, strategy = "OFFLINE_CACHE", characters = text.paragraphs.sumOf { it.length },
        paragraphs = text.paragraphs.size, provenance = origin))
    internal fun lockEvidence(chapter: NovelChapter): String? =
        if (SunovelsAccess.state(chapter) != SunovelsChapterState.EXPLICITLY_LOCKED) null
        else if (chapter.sunovelsEvidence == SunovelsChapterEvidence.PUBLIC_READER) "CACHED_EXPLICIT_LOCK" else "INDEX_LOCK_HINT"
    internal fun failed(chapter: NovelChapter, error: Exception, transport: SunovelsTransport? = null, attempts: Int = 0) {
        val e = error as? NovelSourceFailure
        val code = e?.code ?: if (error is java.net.SocketTimeoutException) "TIMEOUT" else "NETWORK_FAILURE"
        val cachedLock = if (code == "SUN_LOCKED" && e?.sourceVerified != true && transport == null) lockEvidence(chapter) else null
        val outcome = when {
            cachedLock == "INDEX_LOCK_HINT" -> SunovelsOutcome.INDEXED_UNVERIFIED
            cachedLock == "CACHED_EXPLICIT_LOCK" -> SunovelsOutcome.EXPLICITLY_LOCKED
            e?.sourceVerified == true && code == "SUN_LOCKED" -> SunovelsOutcome.EXPLICITLY_LOCKED
            code == "AMBIGUOUS_ROW" || code == "SUN_UNAVAILABLE" -> SunovelsOutcome.AMBIGUOUS_IDENTITY
            code in setOf("CONTAINER_EMPTY", "RESPONSE_INTERRUPTED") -> SunovelsOutcome.PARSER_EMPTY
            code in setOf("TITLE_MISMATCH", "NUMBER_MISMATCH", "SUN_OWNERSHIP", "SUN_DESTINATION", "IDENTITY_MISMATCH") -> SunovelsOutcome.IDENTITY_MISMATCH
            e?.httpStatus in setOf(404, 410) -> SunovelsOutcome.NOT_FOUND
            e?.httpStatus == 429 || code == "SUN_BACKOFF" -> SunovelsOutcome.RATE_LIMITED
            e?.httpStatus in setOf(401, 403) || code in setOf("ACCESS_RESTRICTED", "SUN_BLOCKED") -> SunovelsOutcome.ACCESS_RESTRICTED
            else -> SunovelsOutcome.NETWORK_TRANSIENT
        }
        val parsed = get(chapter.id)
        record(SunovelsResolutionReport(chapter, outcome, code, pageTitle = parsed?.pageTitle, pageNumber = parsed?.pageNumber,
            strategy = parsed?.strategy ?: "NONE", characters = parsed?.characters ?: 0, paragraphs = parsed?.paragraphs ?: 0,
            transport = transport?.copy(cooldownMs = e?.retryAfterMs ?: transport.cooldownMs), attempts = attempts,
            provenance = cachedLock ?: if (e?.sourceVerified == true && code == "SUN_LOCKED") "LIVE_EXPLICIT_LOCK" else if (e?.sourceVerified == true) "PUBLIC_READER" else "TEMPORARY_DIAGNOSTIC"))
    }
}

/** Shares manual revalidation and ordinary reader/download public GETs; last waiter cancels real I/O. */
internal class SunovelsChapterFlights<T> {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class Flight<T>(val result: Deferred<T>, var readers: Int = 1)
    private val flights = mutableMapOf<String, Flight<T>>()
    suspend fun get(url: String, load: suspend () -> T): T {
        val task = currentCoroutineContext()[eu.kanade.tachiyomi.focus.FocusTask] ?: kotlin.coroutines.EmptyCoroutineContext
        val flight = synchronized(flights) {
            flights[url]?.also { it.readers++ } ?: Flight(scope.async(context = task, start = CoroutineStart.LAZY) { load() }).also { flights[url] = it }
        }
        try { flight.result.start(); return flight.result.await() }
        finally { synchronized(flights) {
            if (--flight.readers == 0) {
                flights.remove(url)
                if (!flight.result.isCompleted) flight.result.cancel()
            }
        } }
    }
}
