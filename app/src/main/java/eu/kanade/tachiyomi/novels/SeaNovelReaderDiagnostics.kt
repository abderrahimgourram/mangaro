package eu.kanade.tachiyomi.novels

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal enum class SeaReaderStage { READER_ENTRY, LOCAL_RESTORE, REPOSITORY_OWNERSHIP, CACHE_LOOKUP,
    HTTP_REQUEST, HTTP_HEADERS, HTTP_BODY, HTTP_RETRY_WAIT, SOURCE_DOCUMENT, SOURCE_CANONICAL, SOURCE_CONTAINER,
    SOURCE_COMPLETENESS, SOURCE_ACCESS, SOURCE_EXTRACTION, SOURCE_VALIDATION, SOURCE_ACCEPTED,
    REPOSITORY_VALIDATION, REPOSITORY_ACCEPTED, READER_RENDER, READER_RESTORE, READER_VISIBLE }

/** Ephemeral metadata from the original call. Never contains response bodies, headers or exception messages. */
internal data class SeaReaderReport(val actionId: Long, val requestId: Long, val editionId: String,
    val chapterId: String, val displayTitle: String, val stage: SeaReaderStage = SeaReaderStage.READER_ENTRY,
    val requestedUrl: String? = null, val finalUrl: String? = null, val http: Int? = null,
    val contentType: String? = null, val bytes: Int = 0, val declaredBytes: Long? = null,
    val bodyReadComplete: Boolean = false, val attempts: Int = 0,
    val cache: String = "NOT_CHECKED", val parserOutcome: String = "NOT_REACHED",
    val rawBlocks: Int = 0, val extractedBlocks: Int = 0, val acceptedBlocks: Int = 0,
    val rejectedBlocks: Int = 0, val characters: Int = 0, val sourceAcceptedBlocks: Int = 0,
    val repositoryAcceptedBlocks: Int = 0, val twoBlockFixReached: Boolean = false,
    val failureCode: String = "NONE", val exceptionClass: String? = null, val causeClass: String? = null,
    val events: List<String> = emptyList()) {
    fun copyReport() = listOf("diagnosticVersion=SEA_READER_TRACE_V1", "sourceId=novel.seanovel",
        "readerContract=${SeaNovelSource.READER_CONTRACT}", "editionId=$editionId",
        "persistedChapterId=$chapterId", "displayTitle=$displayTitle", "actionId=$actionId", "requestId=$requestId",
        "stage=$stage", "requestedUrl=${requestedUrl ?: "NOT_SENT_IN_THIS_ATTEMPT"}",
        "finalUrl=${finalUrl ?: "NO_RESPONSE_IN_THIS_ATTEMPT"}", "http=$http", "contentType=$contentType",
        "responseBytesRead=$bytes", "declaredResponseBytes=$declaredBytes", "bodyReadComplete=$bodyReadComplete", "attempts=$attempts", "coalesced=${actionId != requestId}", "cache=$cache", "parserOutcome=$parserOutcome",
        "rawBlocks=$rawBlocks", "extractedBlocks=$extractedBlocks", "acceptedBlocks=$acceptedBlocks",
        "rejectedBlocks=$rejectedBlocks", "characters=$characters", "sourceAcceptedBlocks=$sourceAcceptedBlocks",
        "repositoryAcceptedBlocks=$repositoryAcceptedBlocks", "twoBlockFixReached=$twoBlockFixReached",
        "failure=$failureCode", "exceptionClass=$exceptionClass", "causeClass=$causeClass",
        "events=${events.joinToString(" -> ")}").joinToString("\n")
}

internal class SeaReaderTrace(initial: SeaReaderReport) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SeaReaderTrace>
    private var report = initial
    @Synchronized fun snapshot() = report
    fun fork() = SeaReaderTrace(snapshot())
    @Synchronized fun stage(value: SeaReaderStage) {
        report = report.copy(stage = value, events = (report.events + value.name).takeLast(24))
    }
    @Synchronized fun cache(value: String, text: NovelText? = null) {
        report = report.copy(cache = value)
        if (text != null) report = report.copy(parserOutcome = "CACHED_VALID", extractedBlocks = text.paragraphs.size,
            acceptedBlocks = text.paragraphs.size, characters = text.paragraphs.sumOf { it.length })
    }
    @Synchronized fun httpStart(url: String) {
        stage(SeaReaderStage.HTTP_REQUEST)
        report = report.copy(requestedUrl = report.requestedUrl ?: safeUrl(url), finalUrl = null, http = null, bytes = 0, declaredBytes = null, bodyReadComplete = false, contentType = null,
            attempts = report.attempts + 1, failureCode = "NONE", exceptionClass = null, causeClass = null)
    }
    @Synchronized fun response(url: String, status: Int, type: String?, declared: Long?) {
        stage(SeaReaderStage.HTTP_HEADERS)
        report = report.copy(finalUrl = safeUrl(url), http = status, contentType = type?.take(200), declaredBytes = declared?.takeIf { it >= 0 },
            events = (report.events + "HTTP_$status").takeLast(24))
    }
    @Synchronized fun bytes(count: Int) { report = report.copy(bytes = count) }
    @Synchronized fun bodyComplete() { report = report.copy(bodyReadComplete = true) }
    @Synchronized fun container(count: Int) { report = report.copy(rawBlocks = count) }
    @Synchronized fun extractionStarted() { stage(SeaReaderStage.SOURCE_EXTRACTION); report = report.copy(twoBlockFixReached = true) }
    @Synchronized fun extracted(text: NovelText) {
        report = report.copy(parserOutcome = "EXTRACTED", extractedBlocks = text.paragraphs.size,
            characters = text.paragraphs.sumOf { it.length }, rejectedBlocks = (report.rawBlocks - text.paragraphs.size).coerceAtLeast(0))
    }
    @Synchronized fun accepted(text: NovelText, source: Boolean) {
        report = report.copy(parserOutcome = "ACCEPTED", acceptedBlocks = text.paragraphs.size,
            sourceAcceptedBlocks = if (source) text.paragraphs.size else report.sourceAcceptedBlocks,
            repositoryAcceptedBlocks = if (source) report.repositoryAcceptedBlocks else text.paragraphs.size)
    }
    @Synchronized fun failed(error: Exception, force: Boolean = false) {
        if (!force && report.failureCode != "NONE") return // Retain the first failing stage through unwinding.
        val failure = error as? NovelSourceFailure
        val code = failure?.code ?: when {
            error is kotlinx.coroutines.CancellationException -> "SEA_CANCELLED"
            report.stage == SeaReaderStage.LOCAL_RESTORE || report.stage == SeaReaderStage.READER_RESTORE -> "SEA_STATE_RESTORE_FAILED"
            error is java.net.SocketTimeoutException -> "SEA_TIMEOUT"
            error is java.io.IOException -> if (report.stage.name.startsWith("HTTP_")) "SEA_NETWORK_IO" else "SEA_${report.stage}_IO"
            report.stage == SeaReaderStage.REPOSITORY_VALIDATION -> "SEA_REPOSITORY_CONTENT_REJECTED"
            report.stage == SeaReaderStage.SOURCE_EXTRACTION -> "SEA_EXTRACTION_EMPTY"
            else -> "SEA_${report.stage}_FAILED"
        }
        val validation = report.stage.name.startsWith("SOURCE_") || report.stage == SeaReaderStage.REPOSITORY_VALIDATION
        report = report.copy(failureCode = code, exceptionClass = error.javaClass.name, causeClass = error.cause?.javaClass?.name,
            parserOutcome = if (report.stage.name.startsWith("SOURCE_")) "REJECTED" else report.parserOutcome,
            acceptedBlocks = if (validation) 0 else report.acceptedBlocks,
            rejectedBlocks = if (validation) maxOf(report.rawBlocks, report.extractedBlocks) else report.rejectedBlocks)
    }
    fun adopt(actual: SeaReaderTrace?) {
        if (actual == null || actual === this) return
        val captured = actual.snapshot()
        synchronized(this) { report = captured.copy(actionId = report.actionId, editionId = report.editionId,
            chapterId = report.chapterId, displayTitle = report.displayTitle) }
    }
    private fun safeUrl(url: String) = url.toHttpUrlOrNull()?.newBuilder()?.username("")?.password("")?.query(null)?.fragment(null)?.build()?.toString()
}

internal object SeaNovelReaderDiagnostics {
    private val sequence = java.util.concurrent.atomic.AtomicLong()
    private val reports = LinkedHashMap<String, SeaReaderReport>()
    fun begin(novel: Novel, chapter: NovelChapter): SeaReaderTrace? {
        if (!(eu.kanade.tachiyomi.BuildConfig.DEBUG || eu.kanade.tachiyomi.BuildConfig.SUNOVELS_RUNTIME_AUDIT) || novel.sourceId != SeaNovelSource.SOURCE_ID) return null
        val id = sequence.incrementAndGet()
        return SeaReaderTrace(SeaReaderReport(id, id, novel.id, chapter.id, chapter.title))
    }
    suspend fun current() = currentCoroutineContext()[SeaReaderTrace]
    @Synchronized fun publish(trace: SeaReaderTrace) {
        val report = trace.snapshot(); val key = report.editionId + "|" + report.chapterId
        if (report.actionId < (reports[key]?.actionId ?: 0)) return
        reports[key] = report
        while (reports.size > 32) reports.remove(reports.keys.first())
    }
    @Synchronized fun get(novel: Novel, chapter: NovelChapter) = reports[novel.id + "|" + chapter.id]
}
