package eu.kanade.tachiyomi.novels

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import java.math.BigDecimal

internal data class ChapterIdentitySpec(
    val baseNumber: BigDecimal,
    val suffix: String? = null
)

internal fun parseChapterIdentitySpec(title: String): ChapterIdentitySpec? {
    val trimmed = title.trim()
    val match = Regex("^(?:الفصل\\s*)?([0-9]+(?:[.,][0-9]+)?)(?:-([0-9]+))?(?:\\s*الفصل)?$").find(trimmed)
        ?: return null
    val baseStr = match.groupValues[1].replace(',', '.')
    val baseNum = baseStr.toBigDecimalOrNull() ?: return null
    val suf = match.groupValues[2].ifEmpty { null }
    return ChapterIdentitySpec(baseNum, suf)
}

class SunovelsSource(http: NovelHttp, private val mobileDelivery: Boolean = mobileDeliveryEnabled, private val offlineStorageAuthorized: Boolean = mobileOfflineAuthorized) : HtmlNovelSource(http) {
    override val id = SOURCE_ID
    override val name = "شمس الروايات"
    override val baseUrl = "https://sunovels.com/"
    internal fun parseCatalog(d: Document, page: Int, search: Boolean = false): NovelPage {
        if (page !in 1..2000) invalid("Invalid Sun catalog page")
        rejectChallenge(d)
        val flight = PublicFlight(d)
        val links = d.select(selector("catalogCards", ".list-item a[href^='/novel/']"))
        val cards = links.map { a ->
            val address = a.absUrl("href")
            val slug = path(address, false).first
            val title = a.selectFirst("h4,h3")?.text()?.takeIf { it.isNotBlank() && it.length <= 1000 }
                ?: invalid("Missing Sun catalog title")
            val rendered = flight.objects.firstOrNull { it.string("href") == a.attr("href") }
            val image = rendered?.objects()?.firstOrNull { it.string("src").startsWith("/uploads/") }
            val status = a.selectFirst(".top")?.classNames()?.singleOrNull { it in setOf("Ongoing", "Completed", "New") }
            Novel(id, canonicalNovelUrl(slug), title, image?.string("src")?.let { checkedCover(url(it)) },
                originalTitle = cleanOriginalTitle(image?.string("alt")?.takeIf { it.length <= 1000 }), status = status)
        }
        if (cards.size > 24 || cards.distinctBy { it.id }.size != cards.size) invalid("Invalid Sun catalog rows")
        val numbers = d.select("a[aria-label^=Page]").mapNotNull { Regex("[0-9]+").find(it.attr("aria-label"))?.value?.toIntOrNull() }
        val arrow = d.selectFirst("a[aria-label='Next page']")
        val next = if (arrow == null && search) {
            // The inspected public search returns a closed result list without pagination.
            if (page != 1 || numbers.isNotEmpty()) invalid("Unverified Sun search pagination")
            null
        } else {
            val total = numbers.maxOrNull()?.takeIf { it in page..2000 } ?: invalid("Missing Sun catalog pagination")
            val current = d.selectFirst("a[aria-label*='is your current page']")
                ?.attr("aria-label")?.let { Regex("[0-9]+").find(it)?.value?.toIntOrNull() }
            if (current != page || arrow?.attr("aria-disabled") !in listOf("true", "false") ||
                (arrow?.attr("aria-disabled") == "false") != (page < total)) invalid("Sun catalog pagination changed")
            if (!search && (cards.isEmpty() || page < total && cards.size != 24)) invalid("Sun incomplete catalog page")
            if (page < total) page + 1 else null
        }
        val genres = d.select("a[href*='category=']").mapNotNull { a ->
            val address = a.absUrl("href").toHttpUrlOrNull() ?: return@mapNotNull null
            val value = address.queryParameter("category")
            if (address.host != "sunovels.com" || value.isNullOrBlank() || a.text().isBlank()) null else NovelGenre(a.text(), value)
        }.distinctBy { it.value }.take(100)
        return NovelPage(cards, next, genres = genres)
    }
    override suspend fun catalog(page: Int, latest: Boolean, genre: String?) = withContext(Dispatchers.IO) {
        if (page !in 1..2000 || (genre?.length ?: 0) > 200) invalid("Invalid Sun catalog page")
        val link = query("library", "page" to (page - 1).toString(), "category" to genre.orEmpty())
        parseCatalog(doc(http.get(link, cache = true), link), page)
    }
    override suspend fun search(query: String, page: Int) = withContext(Dispatchers.IO) {
        if (query.isBlank() || query.length > 128 || page !in 1..2000) invalid("Invalid Sun search page")
        // Source's own public form uses /search?title= and advertises English/Arabic titles.
        val link = query("search", "title" to query.trim(), "page" to (page - 1).toString())
        parseCatalog(doc(http.get(link, cache = true), link), page, search = true)
    }
    internal fun parseDetails(d: Document, novel: Novel): Novel {
        val slug = requireNovel(novel)
        rejectChallenge(d)
        val flight = PublicFlight(d)
        val matches = flight.objects.filter { it.string("url") == slug && "arabicTitle" in it &&
            (it["id"] as? JsonPrimitive)?.intOrNull?.let { value -> value > 0 } == true }
        if (matches.isEmpty() || matches.map { it["id"] }.distinct().size != 1) invalid("Sun novel ownership missing")
        val book = matches.first()
        fun optional(key: String, bound: Int) = book.string(key).takeIf { it.isNotBlank() }?.also {
            if (it.length > bound) invalid("Sun metadata bound exceeded") }
        fun labels(key: String) = (flight.resolve(book[key]) as? JsonArray).orEmpty().mapNotNull {
            (flight.resolve(it) as? JsonObject)?.string("name")?.takeIf(String::isNotBlank)
        }.distinct().also { if (it.size > 100 || it.any { label -> label.length > 1000 }) invalid("Sun metadata array bound exceeded") }
        val cover = flight.resolve(book["coverImage"])?.objects()?.firstOrNull { it.string("url").startsWith("/uploads/") }
            ?.string("url")?.let { checkedCover(url(it)) }
        val count = flight.objects.mapNotNull { value ->
            val owner = flight.resolve(value["novel"]) as? JsonObject
            (value["chaptersCount"] as? JsonPrimitive)?.intOrNull?.takeIf { owner?.string("url") == slug }
        }.distinct().singleOrNull()?.takeIf { it in 0..100_000 }
        val title = optional("arabicTitle", 1000) ?: optional("title", 1000) ?: invalid("Missing Sun metadata title")
        val updated = optional("lastUpdate", 64)?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
        return novel.copy(title = title, cover = cover ?: novel.cover, originalTitle = cleanOriginalTitle(optional("title", 1000)) ?: cleanOriginalTitle(novel.originalTitle),
            description = optional("about", 12000) ?: optional("summary", 12000) ?: novel.description,
            author = labels("authors").joinToString(", ").takeIf(String::isNotBlank) ?: novel.author,
            genres = labels("categories").ifEmpty { novel.genres }, status = optional("status", 80) ?: novel.status,
            chapterCount = count ?: novel.chapterCount, reliableUpdatedAt = updated ?: novel.reliableUpdatedAt)
    }
    override suspend fun details(novel: Novel) = withContext(Dispatchers.IO) {
        val address = canonicalNovelUrl(requireNovel(novel))
        parseDetails(doc(http.get(address, cache = true), address), novel)
    }
    override suspend fun chapters(novel: Novel, page: Int) = chapterIndexPage(novel, page, refresh = false)
    internal suspend fun checkpointPage(novel: Novel, page: Int) = chapterIndexPage(novel, page, refresh = true)
    private suspend fun chapterIndexPage(novel: Novel, page: Int, refresh: Boolean) = withContext(Dispatchers.IO) {
        SunovelsDiagnostics.log("REQUEST_SELECTED", novel.url, "DIRECT_INDEX", page = page)
        val slug = requireNovel(novel)
        if (page !in 1..2000) invalid("Invalid Sun chapter page")
        val link = canonicalNovelUrl(slug).toHttpUrl().newBuilder().addQueryParameter("activeTab", "chapters")
            .addQueryParameter("page", (page - 1).toString()).build().toString()
        try {
            parseIndex(doc(http.get(link, cache = true, refresh = refresh), link), novel, page).also {
                SunovelsDiagnostics.log("PAGE_ACCEPTED", novel.url, "DIRECT_INDEX", page = page, checkpoint = it.nextPage)
            }
        } catch (c: kotlinx.coroutines.CancellationException) { throw c }
        catch (e: Exception) {
            SunovelsDiagnostics.log("PARSER_REJECTED", novel.url, "DIRECT_INDEX", page = page, code = SunovelsDiagnostics.category(e))
            throw e
        }
    }
    internal fun parseIndex(d: Document, novel: Novel, page: Int): ChapterPage {
        val slug = requireNovel(novel)
        rejectChallenge(d)
        val current = d.selectFirst("a[aria-label*='is your current page']")?.attr("aria-label")
            ?.let { Regex("[0-9]+").find(it)?.value?.toIntOrNull() }
        if (current != page) invalid("Sun chapter pagination cursor mismatch")
        val flight = PublicFlight(d)
        val novelRecords = flight.objects.filter { it.string("url") == slug && it["id"]?.jsonPrimitive?.intOrNull != null }
        if (novelRecords.isEmpty() || novelRecords.map { it["id"] }.distinct().size != 1) invalid("Sun novel ownership missing")
        val declared = flight.objects.mapNotNull { obj ->
            val owner = flight.resolve(obj["novel"]) as? JsonObject
            (obj["chaptersCount"] as? JsonPrimitive)?.intOrNull?.takeIf { owner?.string("url") == slug }
        }.distinct().singleOrNull()?.takeIf { it in 0..100_000 } ?: invalid("Sun official chapter count missing")
        val total = flight.objects.mapNotNull { (it["totalPages"] as? JsonPrimitive)?.intOrNull }
            .distinct().singleOrNull()?.takeIf { it in 1..2000 } ?: invalid("Sun pagination total missing")
        if (page > total) invalid("Sun chapter page out of range")
        val next = d.selectFirst("a[aria-label='Next page']") ?: invalid("Sun pagination termination missing")
        if (next.attr("aria-disabled") !in listOf("true", "false") || (next.attr("aria-disabled") == "false") != (page < total))
            invalid("Sun contradictory pagination")
        val availability = LinkedHashMap<String, String>()
        val rows = d.select(selector("chapterLinks","ul.chaptersList a")).map { a ->
            val address = a.absUrl("href")
            if (path(address, true).first != slug) invalid("Foreign Sun chapter index link")
            // The public listing explicitly distinguishes lock from lock-open. Unknown stays unknown.
            val locked = a.selectFirst("svg[data-icon=lock]") != null
            val open = a.selectFirst("svg[data-icon=lock-open]") != null
            if (locked && open) invalid("Sun contradictory row availability")
            if (locked) availability[address] = "LOCKED"
            val title = a.attr("title").ifBlank { a.selectFirst(selector("chapterTitle","strong.chapter-title"))?.text().orEmpty() }
            NovelPublishedChapterRow(address, title, a.selectFirst("time[datetime]")?.attr("datetime"))
        }
        return indexPage(novel, page, rows, declared, total, availability)
    }

    private val readerFlights = SunovelsChapterFlights<SunovelsResolvedChapter>()
    private val readerEpoch = java.util.concurrent.atomic.AtomicLong()
    override suspend fun chapter(chapter: NovelChapter): NovelText = resolvePublicChapter(chapter).text
    internal suspend fun revalidate(novel: Novel, chapter: NovelChapter): NovelText {
        val candidate = SunovelsAccess.probeCandidate(chapter)
        requireResolutionOwnership(novel, candidate, allowMobile = mobileDelivery)
        return resolvePublicChapter(candidate).text
    }
    internal suspend fun resolvePublicChapter(chapter: NovelChapter, manualRetry: Boolean = false, availabilityCheck: Boolean = false): SunovelsResolvedChapter {
        try { requireResolutionOwnership(Novel(id, chapter.url.substringBeforeLast('/'), ""), chapter, allowMobile = mobileDelivery) }
        catch (e: Exception) { SunovelsReaderDiagnostics.failed(chapter, e); throw e }
        return readerFlights.get(chapter.url) {
            if (availabilityCheck) http.prepareSunovelsAvailabilityCheck(chapter.url)
            else if (manualRetry) http.prepareManualSunovelsRetry(chapter.url)
            val attempts = if (availabilityCheck) 1 else (SunovelsReaderDiagnostics.get(chapter.id)?.attempts ?: 0) + 1
            val epoch = readerEpoch.incrementAndGet()
            SunovelsReaderDiagnostics.record(SunovelsResolutionReport(chapter, SunovelsOutcome.NETWORK_TRANSIENT,
                attempts = attempts, epoch = epoch, provenance = "REQUEST_PENDING"))
            var candidate = resolutionCandidate(chapter, mobileDelivery)
            var repair: ChapterPage? = null
            try {
                // Refresh only a published page, using its exact matching URL; never translate a number into a URL.
                val routeNumber = path(chapter.url, true).second!!.toBigDecimal()
                if (!availabilityCheck && (number(chapter.title)?.compareTo(routeNumber)?.let { it != 0 } == true || chapter.publishedRows.any { it.title.isBlank() })) {
                    repair = checkpointPage(Novel(id, canonicalNovelUrl(path(chapter.url, true).first), ""), chapter.sourcePage)
                    candidate = resolutionCandidate(repair.chapters.singleOrNull { it.id == chapter.id } ?: invalid("Sun reader ownership missing from targeted index"), mobileDelivery)
                }
                requireChapter(candidate)
                suspend fun load(row: NovelChapter): NovelText {
                    val raw = http.get(row.url)
                    val transport = http.observation(row.url)
                    if (transport != null && path(transport.finalUrl, true) != path(row.url, true))
                        throw NovelSourceFailure("تعذّر التحقق من رابط الفصل.", "Reader redirected to another chapter", code = "IDENTITY_MISMATCH")
                    return try { parseChapter(raw, row) } catch (e: NovelSourceFailure) {
                        if (!mobileDelivery || !e.sourceVerified || e.code != "SUN_LOCKED") throw e
                        val identity = mobileIdentity(raw, row)
                        val endpoint = identity.endpoint
                        if (manualRetry || availabilityCheck) http.prepareManualSunovelsRetry(endpoint)
                        val web = http.observation(row.url)
                        // Public mobile access is checked independently; a web lock is never treated as mobile entitlement.
                        try {
                            val body = http.sunovelsMobileGet(endpoint)
                            val observed = http.observation(endpoint)
                            if (observed?.finalUrl != endpoint) throw NovelSourceFailure("تعذّر التحقق من رابط الفصل.", "Mobile reader redirected", code = "SUN_DESTINATION")
                            val mobile = SunovelsMobileApi.parse(body, identity)
                            val document = doc("<html><body><article class='sun-mobile-story'></article></body></html>", row.url)
                            document.selectFirst(".sun-mobile-story")!!.html(mobile.html)
                            val parsed = text(document, ".sun-mobile-story", allowShortText = true)
                            if (parsed.paragraphs.sumOf { it.trim().length } < 120 || parsed.paragraphs.none { para -> para.any { it.isLetter() && it in '\u0600'..'\u06ff' } })
                                throw NovelSourceFailure("تعذّر استخراج نص الفصل.", "Mobile story empty or incomplete", code = "CONTAINER_EMPTY")
                            validateText(parsed)
                            SunovelsReaderDiagnostics.record(SunovelsResolutionReport(chapter, SunovelsOutcome.VERIFIED_READABLE,
                                pageTitle = mobile.title, pageNumber = mobile.number, strategy = "MOBILE_JSON_HTML", provenance = "MOBILE_READER",
                                characters = parsed.paragraphs.sumOf { it.length }, paragraphs = parsed.paragraphs.size,
                                deliveryUrl = endpoint, transport = observed, webTransport = web))
                            parsed
                        } catch (c: kotlinx.coroutines.CancellationException) { throw c }
                        catch (failure: Exception) {
                            SunovelsReaderDiagnostics.failed(chapter, failure, http.observation(endpoint), attempts)
                            SunovelsReaderDiagnostics.get(chapter.id)?.let { SunovelsReaderDiagnostics.record(it.copy(
                                deliveryUrl = endpoint, webTransport = web, strategy = if ((failure as? NovelSourceFailure)?.code?.startsWith("HTTP_") == true) "MOBILE_HTTP_REJECTED" else "MOBILE_JSON_VALIDATION", provenance = if ((failure as? NovelSourceFailure)?.sourceVerified == true) "LIVE_MOBILE_LOCK" else "MOBILE_API_DIAGNOSTIC")) }
                            throw failure
                        }
                    }
                }
                val text = try { load(candidate) } catch (e: NovelSourceFailure) {
                    if (availabilityCheck || !manualRetry || repair != null || e.code !in setOf("NUMBER_MISMATCH", "IDENTITY_MISMATCH", "HTTP_404", "HTTP_410")) throw e
                    // One published page and at most one extra reader GET for explicit identity repair.
                    // No title-to-URL guessing or replacing the persisted progress identity.
                    val page = try { checkpointPage(Novel(id, canonicalNovelUrl(path(chapter.url, true).first), ""), chapter.sourcePage) }
                        catch (c: kotlinx.coroutines.CancellationException) { throw c }
                        catch (_: Exception) { throw e }
                    val row = page.chapters.singleOrNull { it.id == chapter.id } ?: throw e
                    candidate = resolutionCandidate(row, mobileDelivery)
                    requireChapter(candidate)
                    repair = page
                    http.prepareManualSunovelsRetry(candidate.url)
                    load(candidate)
                }
                val parsed = SunovelsReaderDiagnostics.get(candidate.id)!!
                val transport = parsed.transport ?: http.observation(candidate.url)
                if (!availabilityCheck && parsed.provenance != "MOBILE_READER" && repair == null && parsed.pageTitle != chapter.title) {
                    // The public reader already proved ownership and content. An optional page repair cannot erase that proof.
                    try {
                        val page = checkpointPage(Novel(id, canonicalNovelUrl(path(chapter.url, true).first), ""), chapter.sourcePage)
                        if (page.chapters.count { it.id == chapter.id } == 1) repair = page
                    } catch (c: kotlinx.coroutines.CancellationException) { throw c }
                    catch (_: Exception) { /* Keep valid reader content and retry metadata repair on a later explicit open. */ }
                }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val report = parsed.copy(chapter = chapter, transport = transport, attempts = attempts, indexRepair = repair, epoch = epoch,
                    outcome = if (repair != null || parsed.pageTitle != chapter.title) SunovelsOutcome.IDENTITY_MISMATCH_REPAIRED else parsed.outcome)
                SunovelsReaderDiagnostics.record(report)
                SunovelsResolvedChapter(text, report)
            } catch (c: kotlinx.coroutines.CancellationException) { throw c }
            catch (e: Exception) {
                if ((e as? NovelSourceFailure)?.sourceVerified == true && e.code == "SUN_LOCKED") http.rememberExplicitSunovelsLock(chapter.url)
                val failure = e as? NovelSourceFailure ?: NovelSourceFailure("تعذّر الاتصال بالمصدر. حاول لاحقًا؛ بياناتك المحفوظة تبقى متاحة.",
                    "Sun public request failed", code = if (e is java.net.SocketTimeoutException) "TIMEOUT" else "NETWORK_IO").also { it.initCause(e) }
                val mobileFailure = SunovelsReaderDiagnostics.get(chapter.id)?.takeIf { it.deliveryUrl != null }
                if (mobileFailure == null) SunovelsReaderDiagnostics.failed(chapter, failure, http.observation(chapter.url), attempts)
                SunovelsReaderDiagnostics.get(chapter.id)?.let { SunovelsReaderDiagnostics.record(it.copy(epoch = epoch)) }
                throw failure
            }
        }
    }
    /** Owner-approved offline reader storage. Never invokes the separately protected v2 download endpoint. */
    internal suspend fun chapterForDownload(chapter: NovelChapter): NovelText {
        if (mobileDelivery && offlineStorageAuthorized)
            return resolvePublicChapter(chapter).text.also { validateText(it) }
        val candidate = resolutionCandidate(chapter, mobileDelivery)
        requireResolutionOwnership(Novel(id, chapter.url.substringBeforeLast('/'), ""), candidate, allowMobile = mobileDelivery)
        val raw = http.get(chapter.url)
        if (http.observation(chapter.url)?.finalUrl != chapter.url)
            throw NovelSourceFailure("تعذّر التحقق من رابط التنزيل.", "Download chapter redirected", code = "SUN_DESTINATION")
        return try { parseChapter(raw, candidate) } catch (e: NovelSourceFailure) {
            if (e.sourceVerified && e.code == "SUN_LOCKED" && mobileDelivery)
                throw NovelSourceFailure("تنزيل هذا الفصل عبر واجهة التطبيق يتطلب تفويضًا رسميًا. القراءة وحدها لا تمنح إذن التنزيل.",
                    "Official mobile download requires authorized account", 403, code = "SUN_MOBILE_DOWNLOAD_AUTH_REQUIRED")
            throw e
        }
    }
    internal fun mobileIdentity(raw: String, chapter: NovelChapter): SunovelsMobileIdentity {
        val (slug, address) = path(chapter.url, true)
        requireChapter(SunovelsAccess.probeCandidate(chapter))
        if (!raw.contains("</html>")) invalid("Incomplete mobile identity document")
        val flight = PublicFlight(doc(raw, chapter.url))
        val record = flight.objects.filter { "chapterNumber" in it && "isLocked" in it && ("content" in it || "body" in it) }
            .distinctBy { it.toString() }.singleOrNull() ?: invalid("Missing or ambiguous mobile chapter identity")
        val owner = flight.resolve(record["novel"]) as? JsonObject ?: invalid("Missing mobile novel identity")
        val novelId = (owner["id"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: invalid("Missing mobile novel ID")
        val chapterId = (record["id"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: invalid("Missing mobile chapter ID")
        if (owner.string("url") != slug || (record["chapterNumber"] as? JsonPrimitive)?.contentOrNull?.toBigDecimalOrNull()?.compareTo(address!!.toBigDecimal()) != 0)
            throw NovelSourceFailure("تعذّر التحقق من هوية الفصل.", "Mobile identity mismatch", code = "SUN_OWNERSHIP")
        return SunovelsMobileIdentity(chapterId, novelId, slug, address)
    }
    internal fun parseChapter(raw: String, chapter: NovelChapter): NovelText {
        requireChapter(chapter)
        val (slug, address) = path(chapter.url, true)
        val d = doc(raw, chapter.url)
        rejectChallenge(d)
        // Only a visible source notice is lock evidence; never infer a lock from empty text.
        if (d.select(".chapter-content, .error-message, .lock-message, [role=alert], h1, h2, h3").any { element ->
            element.parents().plus(element).none { it.hasClass("d-none") || it.hasAttr("hidden") || it.attr("aria-hidden") == "true" } &&
                (element.text().trim().startsWith("الفصل المطلوب مقفل") || element.text().trim() == "جميع الفصول مفتوحة في التطبيق")
        }) {
            SunovelsReaderDiagnostics.record(SunovelsResolutionReport(chapter, SunovelsOutcome.EXPLICITLY_LOCKED,
                failureCode = "SUN_LOCKED", strategy = "EXPLICIT_LOCK_BANNER", provenance = "LIVE_EXPLICIT_LOCK"))
            throw NovelSourceFailure(LOCKED_MESSAGE, "Sun explicit public lock banner", 403, code = "SUN_LOCKED", sourceVerified = true)
        }
        val flight = PublicFlight(d)
        val records = flight.objects.filter { "isLocked" in it && ("content" in it || "body" in it) }
            .distinctBy { it.toString() }
        val record = records.singleOrNull() ?: invalid("Sun reader identity missing or ambiguous")
        SunovelsReaderDiagnostics.record(SunovelsResolutionReport(chapter, SunovelsOutcome.NETWORK_TRANSIENT,
            pageTitle = record.string("title"), pageNumber = record["chapterNumber"]?.jsonPrimitive?.content))
        val owner = flight.resolve(record["novel"]) as? JsonObject ?: invalid("Sun reader owner missing")
        if (owner.string("url") != slug || (owner["id"] as? JsonPrimitive)?.intOrNull?.let { it > 0 } != true ||
            (record["id"] as? JsonPrimitive)?.intOrNull?.let { it > 0 } != true)
            throw NovelSourceFailure("تعذّر التحقق من هوية الرواية.", "Sun reader owner mismatch", code = "SUN_OWNERSHIP")
        if ((record["chapterNumber"] as? JsonPrimitive)?.contentOrNull?.toBigDecimalOrNull()?.compareTo(address!!.toBigDecimal()) != 0)
            throw NovelSourceFailure("تعذّر التحقق من رقم الفصل.", "Sun reader number mismatch", code = "NUMBER_MISMATCH")
        val locked = (record["isLocked"] as? JsonPrimitive)?.booleanOrNull ?: invalid("Sun reader access state missing")
        if (locked) {
            SunovelsReaderDiagnostics.get(chapter.id)?.let { SunovelsReaderDiagnostics.record(it.copy(
                outcome = SunovelsOutcome.EXPLICITLY_LOCKED, failureCode = "SUN_LOCKED", strategy = "RSC_EXPLICIT_LOCK", provenance = "LIVE_EXPLICIT_LOCK")) }
            throw NovelSourceFailure(LOCKED_MESSAGE, "Sun public chapter locked", 403, code = "SUN_LOCKED", sourceVerified = true)
        }
        val contentField = record["content"]?.takeUnless { it == JsonNull } ?: record["body"]
        if (contentField == null || contentField == JsonNull) throw NovelSourceFailure("تعذّر استخراج نص الفصل. أعد المحاولة.", "Missing public reader content", code = "CONTAINER_EMPTY")
        var target = selector("text",".chapter-content")
        val containers = d.select(target)
        var container = containers.singleOrNull()
        var flightFallback = false
        if (container == null && containers.isEmpty() && raw.contains("</html>") &&
            flight.objects.any { it.string("className").split(' ').contains("chapter-content") }) {
            // Only the verified unlocked reader record's referenced public content, never arbitrary Flight strings.
            val publicHtml = (flight.resolve(contentField) as? JsonPrimitive)?.contentOrNull
            if (publicHtml != null && publicHtml.length <= 4 * 1024 * 1024 && !publicHtml.startsWith('$')) {
                d.body().appendElement("div").addClass("sun-public-flight").html(publicHtml)
                target = ".sun-public-flight"; container = d.selectFirst(target); flightFallback = true
            }
        }
        if (container == null) throw NovelSourceFailure("تعذّر استخراج نص الفصل. أعد المحاولة.", "Missing or ambiguous reader container", code = "CONTAINER_EMPTY")
        if (container.hasClass("d-none") || container.hasAttr("hidden") || container.attr("aria-hidden") == "true")
            throw NovelSourceFailure("تعذّر استخراج نص الفصل. أعد المحاولة.", "Hidden public reader container", code = "CONTAINER_EMPTY")
        if (container.selectFirst("form, input[type=password], .error-message, .login-message, [role=alert]") != null)
            invalid("Sun reader contains an interface/error state")
        // Jsoup repairs incomplete HTML. Require a real closure of the selected public container.
        val tag = Regex.escape(container.normalName())
        val className = container.classNames().firstOrNull()?.let(Regex::escape) ?: invalid("Sun reader class missing")
        val opening = if (flightFallback) null else Regex("<$tag\\b[^>]*\\bclass\\s*=\\s*([\"'])[^\"']*\\b$className\\b[^\"']*\\1[^>]*>", RegexOption.IGNORE_CASE).find(raw)
            ?: if (!flightFallback) invalid("Sun reader opening missing") else null
        var depth = 1
        val closed = opening != null && Regex("</?$tag\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(raw, opening.range.last+1).any { match ->
            depth += if (match.value.startsWith("</")) -1 else 1
            depth == 0
        }
        if (!closed && !flightFallback)
            throw NovelSourceFailure("استجابة الفصل غير مكتملة. أعد المحاولة.", "Sun reader response interrupted", code = "RESPONSE_INTERRUPTED")
        val title = record.string("title")
        val headers = d.select("h2").map { it.text() }
        val expectedSpec = chapter.publishedRows.mapNotNull { parseChapterIdentitySpec(it.title) }
            .firstOrNull { it.baseNumber.compareTo(address.toBigDecimal()) == 0 }
            ?: parseChapterIdentitySpec(chapter.title)?.takeIf { it.baseNumber.compareTo(address.toBigDecimal()) == 0 }
        val expectedSuffix = expectedSpec?.suffix

        val recordSpec = parseChapterIdentitySpec(title)
        if (recordSpec != null) {
            if (recordSpec.baseNumber.compareTo(address.toBigDecimal()) != 0 ||
                (recordSpec.suffix != null && recordSpec.suffix != expectedSuffix)) {
                throw NovelSourceFailure("تعذّر التحقق من رقم الفصل.", "Sun reader title suffix mismatch", code = "NUMBER_MISMATCH")
            }
        }

        for (h2 in headers) {
            val h2Spec = parseChapterIdentitySpec(h2)
            if (h2Spec != null) {
                if (h2Spec.baseNumber.compareTo(address.toBigDecimal()) != 0 ||
                    (h2Spec.suffix != null && h2Spec.suffix != expectedSuffix)) {
                    throw NovelSourceFailure("تعذّر التحقق من رقم الفصل.", "Sun page number mismatch", code = "NUMBER_MISMATCH")
                }
            } else {
                val h2Num = number(h2)
                if (h2Num != null && h2Num.compareTo(address.toBigDecimal()) != 0) {
                    throw NovelSourceFailure("تعذّر التحقق من رقم الفصل.", "Sun page number mismatch", code = "NUMBER_MISMATCH")
                }
            }
        }
        val cleaned = d.clone()
        cleaned.select("$target header, $target footer, $target aside, $target .navigation").remove()
        // The shared walker already supports p/div/article/span/br and single-block stories.
        // Fall back only to the same ownership-verified unlocked record's public content.
        var extracted = try { text(cleaned, target, allowShortText = true) } catch (_: NovelSourceFailure) { null }
        fun meaningful(value: NovelText?) = value != null && value.paragraphs.sumOf { it.trim().length } >= 120 &&
            value.paragraphs.any { p -> p.any { it in '\u0600'..'\u06ff' && it.isLetter() } }
        if (!meaningful(extracted) && !flightFallback && raw.contains("</html>") &&
            flight.objects.any { it.string("className").split(' ').contains("chapter-content") }) {
            val publicHtml = (flight.resolve(contentField) as? JsonPrimitive)?.contentOrNull
            if (publicHtml != null && publicHtml.length <= 4 * 1024 * 1024 && !publicHtml.startsWith('$')) {
                val fallback = cleaned.clone()
                fallback.body().appendElement("div").addClass("sun-public-flight").html(publicHtml)
                val alternate = try { text(fallback, ".sun-public-flight", allowShortText = true) } catch (_: NovelSourceFailure) { null }
                if (meaningful(alternate)) { extracted = alternate; flightFallback = true }
            }
        }
        val strategy = if (flightFallback) "RSC_FLIGHT" else if (container.select("p:not(.d-none)").isNotEmpty()) "HTML_PARAGRAPHS" else "HTML_BR_BLOCKS"
        SunovelsReaderDiagnostics.get(chapter.id)?.let { SunovelsReaderDiagnostics.record(it.copy(
            strategy = strategy, characters = extracted?.paragraphs?.sumOf { p -> p.length } ?: 0, paragraphs = extracted?.paragraphs?.size ?: 0)) }
        if (!meaningful(extracted)) throw NovelSourceFailure("تعذّر استخراج نص الفصل. أعد المحاولة.", "Sun story container empty or too short", code = "CONTAINER_EMPTY")
        val verifiedText = checkNotNull(extracted)
        validateText(verifiedText)
        SunovelsReaderDiagnostics.record(SunovelsResolutionReport(chapter,
            if (title == chapter.title) SunovelsOutcome.VERIFIED_READABLE else SunovelsOutcome.IDENTITY_MISMATCH_REPAIRED,
            pageTitle = title, pageNumber = record["chapterNumber"]?.jsonPrimitive?.content,
            strategy = strategy,
            characters = verifiedText.paragraphs.sumOf { it.length }, paragraphs = verifiedText.paragraphs.size))
        return verifiedText
    }

    private fun rejectChallenge(d: Document) {
        if (d.selectFirst("#challenge-form, #cf-wrapper, .challenge-running, .cf-browser-verification") != null) {
            http.rememberSourceFailure(d.location(), 403)
            throw NovelSourceFailure("المصدر يطلب تحققًا في موقعه؛ لا يمكن تجاوزه داخل التطبيق.", "Sun access challenge", 403, code = "SUN_BLOCKED")
        }
    }

    /** JSON-only public Flight metadata; content is read from rendered HTML, never executed. */
    private class PublicFlight(d: Document) {
        private val roots = LinkedHashMap<String, JsonElement>()
        val objects = ArrayList<JsonObject>()
        init {
            // Scan every push, including several pushes per script and records split between scripts.
            // Parse JSON only. Keep the HTTP/body, recursion and field budgets; never execute source JS.
            val chunks = buildString {
                d.select("script").forEach { script ->
                    val wire = script.data()
                    Regex("self\\.__next_f\\.push\\s*\\(").findAll(wire).forEach { push ->
                        val start = push.range.last + 1
                        var quoted = false; var escaped = false; var depth = 0; var end = -1
                        for (i in start until wire.length) {
                            val char = wire[i]
                            if (quoted) {
                                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
                            } else when (char) {
                                '"' -> quoted = true
                                '[', '{' -> depth++
                                ']', '}' -> { depth--; if (depth == 0) { end = i + 1; break } }
                                ')' -> break
                            }
                        }
                        if (end > start) runCatching { Json.parseToJsonElement(wire.substring(start, end)) as? JsonArray }
                            .getOrNull()?.getOrNull(1)?.let { it as? JsonPrimitive }?.contentOrNull?.let { append(it) }
                    }
                }
            }
            val wire = chunks.toByteArray(Charsets.UTF_8)
            val textHeader = Regex("(?:^|\\n)([0-9a-z]+):T([0-9a-fA-F]+),")
            textHeader.findAll(chunks).forEach { match ->
                val length = match.groupValues[2].toIntOrNull(16) ?: invalid("Invalid Sun public text length")
                if (length !in 0..4 * 1024 * 1024) invalid("Sun public text exceeds bound")
                val start = chunks.substring(0, match.range.last + 1).toByteArray(Charsets.UTF_8).size
                if (start + length > wire.size) invalid("Sun public text interrupted")
                val value = String(wire, start, length, Charsets.UTF_8)
                if (value.toByteArray(Charsets.UTF_8).size != length) invalid("Sun public text boundary mismatch")
                if (roots.put(match.groupValues[1], JsonPrimitive(value)) != null) invalid("Duplicate Sun public text record")
            }
            chunks.lineSequence().forEach { line ->
                val value = line.substringAfter(':', "")
                if (value.startsWith("{") || value.startsWith("[")) {
                    val key = line.substringBefore(':')
                    val parsed = runCatching { Json.parseToJsonElement(value) }.getOrNull()
                    if (parsed != null) {
                        if (roots.put(key, parsed) != null) invalid("Duplicate Sun public metadata record")
                    }
                }
            }
            var visited = 0
            fun visit(value: JsonElement, depth: Int) {
                if (++visited > 200_000 || depth > 128) invalid("Sun public metadata exceeds bound")
                when (value) {
                    is JsonObject -> { objects += value; value.values.forEach { visit(it, depth+1) } }
                    is JsonArray -> value.forEach { visit(it, depth+1) }
                    else -> Unit
                }
            }
            roots.values.forEach { visit(it, 0) }
        }
        fun resolve(value: JsonElement?): JsonElement? {
            var resolved = value
            val seen = HashSet<String>()
            while (resolved is JsonPrimitive && resolved.isString && resolved.content.startsWith('$') && roots.containsKey(resolved.content.removePrefix("$"))) {
                if (!seen.add(resolved.content) || seen.size > 128) invalid("Sun metadata reference cycle")
                resolved = roots[resolved.content.removePrefix("$")]
            }
            return resolved
        }
    }

    companion object {
        internal const val SOURCE_ID = "novel.sunovels"
        internal const val PARSER_VERSION = "sun-public-reader-v3"
        internal const val INDEX_PARSER_VERSION = "sun-direct-index-v7"
        internal const val AMBIGUOUS_MESSAGE = "المصدر يربط فصولًا مختلفة بالرابط نفسه؛ لا يمكن قراءة هذه الصفوف أو تنزيلها بأمان حتى يصحح روابطها."
        internal const val LOCKED_MESSAGE = "هذا الفصل مقفل في موقع شمس الروايات. لا يمكن قراءته أو تنزيله من المصدر العام؛ يمكنك فتح الموقع الأصلي."
        internal const val MISSING_TITLE_MESSAGE = "المصدر نشر رابطًا دون بيانات فصل كافية. لا يمكن التحقق منه أو تنزيله بأمان."
        private fun invalid(reason: String): Nothing {
            val code = when {
                reason.contains("ownership", true) || reason.contains("Foreign", true) -> "SUN_OWNERSHIP"
                reason.contains("destination", true) -> "SUN_DESTINATION"
                reason.contains("count", true) || reason.contains("total", true) -> "SUN_INDEX_COUNT"
                reason.contains("pagination", true) || reason.contains("page", true) -> "SUN_PAGINATION"
                reason.contains("row", true) || reason.contains("ordering", true) -> "SUN_INDEX_ROWS"
                reason.contains("metadata", true) -> "SUN_PUBLIC_METADATA"
                else -> "SUN_READER_DOCUMENT"
            }
            val message = when (code) {
                "SUN_OWNERSHIP", "SUN_DESTINATION" -> "تعذّر التحقق من هوية الرواية أو روابط فصولها في المصدر."
                "SUN_INDEX_COUNT" -> "بيانات عدد الفصول في المصدر ناقصة أو متعارضة. أعد تحميل الفهرس."
                "SUN_PAGINATION" -> "تعذّر التحقق من صفحات فهرس المصدر. أعد تحميل الفهرس."
                "SUN_INDEX_ROWS" -> "صفحة الفصول ناقصة أو تحتوي بيانات متعارضة. الفصول المحفوظة تبقى متاحة."
                "SUN_PUBLIC_METADATA" -> "تعذّر قراءة بيانات الفهرس من صفحة المصدر. افتح الموقع الأصلي أو أعد المحاولة."
                else -> "تعذّر التحقق من محتوى الفصل. أعد المحاولة أو افتح الموقع الأصلي."
            }
            throw NovelSourceFailure(message, reason, 422, code = code)
        }
        // Reject only recognizable image filenames/paths; never broad prefixes such as "Small" or "Cover".
        private val imageFilename = Regex("[^\\r\\n]+\\.(?:jpe?g|png|webp|gif|avif|bmp|svg)(?:[?#].*)?", RegexOption.IGNORE_CASE)
        internal fun cleanOriginalTitle(value: String?): String? = value?.takeIf {
            it.isNotBlank() && !imageFilename.matches(it.trim()) && !it.trim().startsWith("/uploads/")
        }
        internal fun cleanMetadata(novel: Novel): Novel = if (novel.sourceId != SOURCE_ID) novel else
            novel.copy(originalTitle = cleanOriginalTitle(novel.originalTitle))
        internal fun cleanCatalog(state: NovelCatalogState): NovelCatalogState = state.copy(works = state.works.map {
            it.copy(editions = it.editions.map(::cleanMetadata))
        })
        internal const val REMOVED_MESSAGE = "هذا الفصل مفقود من فهرس المصدر المحدّث. يبقى التنزيل المحفوظ متاحًا؛ يمكنك مراجعة الموقع الأصلي."
        val diagnosticsEnabled get() = eu.kanade.tachiyomi.BuildConfig.DEBUG || eu.kanade.tachiyomi.BuildConfig.SUNOVELS_RUNTIME_AUDIT
        internal val directEnabled get() = diagnosticsEnabled || eu.kanade.tachiyomi.BuildConfig.SUNOVELS_PRIVATE_ACCEPTANCE
        internal val mobileDeliveryEnabled get() = directEnabled
        internal val mobileOfflineAuthorized get() = mobileDeliveryEnabled && eu.kanade.tachiyomi.BuildConfig.SUNOVELS_OFFLINE_AUTHORIZED
        internal fun canonicalNovelUrl(slug: String) = "https://sunovels.com/novel/" + okhttp3.HttpUrl.Builder()
            .scheme("https").host("sunovels.com").addPathSegment(slug).build().encodedPath.removePrefix("/")
        private fun checkedCover(value: String): String {
            val u = value.toHttpUrlOrNull() ?: invalid("Invalid Sun cover destination")
            if (!u.isHttps || u.host != "sunovels.com" || u.port != 443 || !u.encodedPath.startsWith("/uploads/") ||
                u.username.isNotEmpty() || u.password.isNotEmpty() || u.query != null || u.fragment != null) invalid("Foreign Sun cover destination")
            return value
        }
        internal fun bootstrapNovel(row: JsonObject): Novel {
            val slug = row.string("id")
            val address = row.string("canonicalUrl")
            if (path(address, false).first != slug || address != canonicalNovelUrl(slug)) invalid("Sun bootstrap ownership mismatch")
            fun optional(key: String, bound: Int) = row.string(key).takeIf(String::isNotBlank)?.also {
                if (it.length > bound) invalid("Sun bootstrap metadata bound") }
            fun labels(key: String) = (row[key] as? JsonArray).orEmpty().map {
                (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: invalid("Sun bootstrap metadata array")
            }.also { if (it.size > 100 || it.any { label -> label.length !in 1..1000 }) invalid("Sun bootstrap metadata bound") }
            val title = optional("title", 1000) ?: invalid("Missing Sun bootstrap title")
            val count = (row["chapterCount"] as? JsonPrimitive)?.intOrNull?.also { if (it !in 0..100000) invalid("Sun bootstrap count") }
            return Novel(SOURCE_ID, address, title, optional("cover", 2000)?.let(::checkedCover),
                description = optional("description", 12000).orEmpty(), author = optional("author", 1000), status = optional("status", 80),
                originalTitle = cleanOriginalTitle(optional("originalTitle", 1000)), alternativeTitles = labels("aliases"), genres = labels("categories"), chapterCount = count,
                reliableUpdatedAt = optional("sourceUpdatedAt", 64)?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() })
        }
        internal fun parseChapterIdentitySpec(title: String) = eu.kanade.tachiyomi.novels.parseChapterIdentitySpec(title)
        internal fun genericTitle(title: String): Boolean =
            Regex("^(?:الفصل\\s*)?[0-9]+(?:[.,][0-9]+)?(?:-[0-9]+)?(?:\\s*الفصل)?$").matches(title.trim())
        internal fun bestTitle(saved: String, page: String?): String =
            saved.takeIf { it.isNotBlank() && !genericTitle(it) } ?: page?.takeIf { it.isNotBlank() } ?: saved
        internal fun number(title: String): BigDecimal? = Regex("^(?:الفصل\\s*)?([0-9]+(?:[.,][0-9]+)?)(?:-[0-9]+)?(?=\\s|الفصل|$)").find(title)
            ?.groupValues?.get(1)?.replace(',', '.')?.toBigDecimalOrNull()
        internal fun normalizeDisplayTitle(rawTitle: String, routeNumber: BigDecimal): String {
            val trimmed = rawTitle.trim()
            if (trimmed.isBlank()) {
                val numString = if (routeNumber.scale() <= 0) routeNumber.toBigInteger().toString() else routeNumber.toPlainString()
                return "الفصل $numString"
            }
            val extractedNumber = number(trimmed)
            if (extractedNumber != null && extractedNumber.compareTo(routeNumber) == 0) {
                val isSuffixedGeneric = Regex("^(?:الفصل\\s*)?[0-9]+(?:[.,][0-9]+)?(?:-[0-9]+)?(?:\\s*الفصل)?$").matches(trimmed)
                if (isSuffixedGeneric) {
                    val numString = if (routeNumber.scale() <= 0) routeNumber.toBigInteger().toString() else routeNumber.toPlainString()
                    return "الفصل $numString"
                }
            }
            return trimmed
        }
        private fun path(url: String, chapter: Boolean): Pair<String, String?> {
            val u = url.toHttpUrlOrNull() ?: invalid("Malformed Sun destination")
            val p = u.pathSegments.filter { it.isNotEmpty() }
            if (!u.isHttps || u.host !in setOf("sunovels.com", "www.sunovels.com") || u.port != 443 ||
                u.username.isNotEmpty() || u.password.isNotEmpty() || u.query != null || u.fragment != null ||
                p.size != (if (chapter) 3 else 2) || p[0] != "novel" ||
                p[1].length !in 1..240 || p[1].any { !it.isLetterOrDigit() && it !in "-_" } ||
                chapter && (p[2].length !in 1..32 || !Regex("[0-9]+(?:\\.[0-9]+)?").matches(p[2]) || p[2].toBigDecimal() < BigDecimal.ZERO))
                invalid("Invalid Sun novel/chapter destination")
            return p[1] to if (chapter) p[2] else null
        }
        private fun knownAmbiguous(url: String): Boolean {
            val p = path(url, true)
            // Confirmed public evidence; no fabricated fractional URL or equivalence assumption.
            return p.first == "reverend-insanity" && p.second in setOf("1153", "1154")
        }

        internal fun areRowsEquivalent(row1: NovelPublishedChapterRow, row2: NovelPublishedChapterRow, routeNumber: BigDecimal): Boolean {
            val t1 = row1.title.trim()
            val t2 = row2.title.trim()
            if (t1.equals(t2, ignoreCase = true)) return true

            val s1 = parseChapterIdentitySpec(t1)
            val s2 = parseChapterIdentitySpec(t2)
            if (s1 != null && s2 != null) {
                return s1.baseNumber.compareTo(routeNumber) == 0 &&
                       s2.baseNumber.compareTo(routeNumber) == 0 &&
                       s1.suffix == s2.suffix
            }
            return false
        }

        internal fun reconcilePublishedRows(
            url: String,
            rows: List<NovelPublishedChapterRow>,
            availability: Map<String, String> = emptyMap(),
            order: Int = 0,
            sourcePage: Int = 1
        ): NovelChapter {
            val routeNumber = path(url, true).second!!.toBigDecimal()
            val knownAmb = knownAmbiguous(url)
            val stateAttr = availability[url]
            val isLocked = stateAttr == "LOCKED"
            val isMissingState = stateAttr in setOf("MISSING_IDENTITY", "REMOVED") || rows.any { it.title.isBlank() }
            val isTempUnavailable = stateAttr in setOf("UNAVAILABLE", "TEMPORARILY_UNAVAILABLE")

            val distinctRows = rows.distinctBy { Pair(it.url, it.title) }

            val isAmbiguous = when {
                knownAmb || stateAttr == "AMBIGUOUS_CHAPTER" -> true
                distinctRows.isEmpty() -> false
                distinctRows.size == 1 -> number(distinctRows.first().title)?.let { it.compareTo(routeNumber) != 0 } == true
                else -> {
                    val first = distinctRows.first()
                    !distinctRows.all { row -> areRowsEquivalent(first, row, routeNumber) }
                }
            }

            val missingTitle = distinctRows.any { it.title.isBlank() }
            val blocked = stateAttr in setOf("LOCKED", "MISSING_IDENTITY", "REMOVED", "AMBIGUOUS_CHAPTER")

            val finalAvailable = when {
                isAmbiguous || missingTitle || isLocked || isMissingState || isTempUnavailable -> false
                else -> !blocked
            }

            val finalState = when {
                isAmbiguous -> SunovelsChapterState.AMBIGUOUS
                missingTitle || isMissingState -> SunovelsChapterState.MISSING
                isLocked -> SunovelsChapterState.EXPLICITLY_LOCKED
                isTempUnavailable -> SunovelsChapterState.TEMPORARILY_UNAVAILABLE
                else -> SunovelsChapterState.INDEXED_UNVERIFIED
            }

            val finalReason = when {
                isAmbiguous -> AMBIGUOUS_MESSAGE
                missingTitle || stateAttr == "MISSING_IDENTITY" -> MISSING_TITLE_MESSAGE
                isLocked -> LOCKED_MESSAGE
                stateAttr == "REMOVED" -> "هذا الفصل لم يعد متاحًا في فهرس المصدر."
                else -> null
            }

            val displayTitle = if (distinctRows.isNotEmpty()) normalizeDisplayTitle(distinctRows.first().title, routeNumber)
                               else "الفصل ${if (routeNumber.scale() <= 0) routeNumber.toBigInteger() else routeNumber}"

            return NovelChapter(
                url = url,
                title = displayTitle,
                order = order,
                sourcePage = sourcePage,
                available = finalAvailable,
                publishedRows = distinctRows,
                sunovelsState = finalState,
                sunovelsEvidence = SunovelsChapterEvidence.INDEX,
                unavailableReason = finalReason
            )
        }

        internal fun reconcileChapter(
            old: NovelChapter,
            incoming: NovelChapter,
            combinedRows: List<NovelPublishedChapterRow>
        ): NovelChapter {
            val url = old.url
            val routeNumber = path(url, true).second!!.toBigDecimal()
            val knownAmb = knownAmbiguous(url)

            val isLocked = old.sunovelsState == SunovelsChapterState.EXPLICITLY_LOCKED ||
                    incoming.sunovelsState == SunovelsChapterState.EXPLICITLY_LOCKED ||
                    old.unavailableReason == LOCKED_MESSAGE ||
                    incoming.unavailableReason == LOCKED_MESSAGE

            val isMissingState = old.sunovelsState == SunovelsChapterState.MISSING ||
                    incoming.sunovelsState == SunovelsChapterState.MISSING ||
                    old.unavailableReason == REMOVED_MESSAGE ||
                    incoming.unavailableReason == REMOVED_MESSAGE ||
                    old.unavailableReason == MISSING_TITLE_MESSAGE ||
                    incoming.unavailableReason == MISSING_TITLE_MESSAGE ||
                    combinedRows.any { it.title.isBlank() }

            val distinctRows = combinedRows.distinctBy { Pair(it.url, it.title) }

            val isAmbiguous = when {
                knownAmb -> true
                distinctRows.isEmpty() -> false
                distinctRows.size == 1 -> number(distinctRows.first().title)?.let { it.compareTo(routeNumber) != 0 } == true
                else -> {
                    val first = distinctRows.first()
                    !distinctRows.all { row -> areRowsEquivalent(first, row, routeNumber) }
                }
            }

            val finalAvailable = when {
                isAmbiguous || isMissingState || isLocked -> false
                SunovelsAccess.durableEvidence(old) -> old.available
                else -> true
            }

            val finalState = when {
                isAmbiguous -> SunovelsChapterState.AMBIGUOUS
                isMissingState -> SunovelsChapterState.MISSING
                isLocked -> SunovelsChapterState.EXPLICITLY_LOCKED
                SunovelsAccess.durableEvidence(old) -> old.sunovelsState
                else -> SunovelsChapterState.INDEXED_UNVERIFIED
            }

            val finalReason = when {
                isAmbiguous -> AMBIGUOUS_MESSAGE
                isMissingState -> if (old.unavailableReason == REMOVED_MESSAGE || incoming.unavailableReason == REMOVED_MESSAGE) "هذا الفصل لم يعد متاحًا في فهرس المصدر." else MISSING_TITLE_MESSAGE
                isLocked -> LOCKED_MESSAGE
                else -> null
            }

            val displayTitle = if (distinctRows.isNotEmpty()) normalizeDisplayTitle(distinctRows.first().title, routeNumber) else old.title

            return old.copy(
                title = displayTitle,
                available = finalAvailable,
                publishedRows = distinctRows,
                sunovelsState = finalState,
                sunovelsEvidence = if (finalState == old.sunovelsState) old.sunovelsEvidence else SunovelsChapterEvidence.INDEX,
                unavailableReason = finalReason
            )
        }

        /** Same source-backed URL/progress identities for direct HTML and staging metadata. */
        internal fun indexPage(novel: Novel, page: Int, rows: List<NovelPublishedChapterRow>, declared: Int, total: Int,
            availability: Map<String, String> = emptyMap()): ChapterPage {
            val slug = requireNovel(novel)
            if (declared !in 0..100_000 || total !in 1..2000 || page !in 1..total ||
                rows.any { path(it.url, true).first != slug }) invalid("Sun index page ownership/count mismatch")
            if (rows.isEmpty() && declared != 0 || rows.size > 50 || page < total && rows.size != 50)
                invalid("Sun incomplete or repeated published rows")
            val numbered = rows.mapNotNull { row -> number(row.title) }
            if (numbered.zipWithNext().any { (a,b) -> a > b }) invalid("Sun chapter ordering changed")
            val chapters = rows.groupBy { it.url }.values.mapIndexed { i, group ->
                reconcilePublishedRows(group.first().url, group, availability, order = (page - 1) * 50 + i, sourcePage = page)
            }
            val evidence = buildJsonObject {
                put("page", page); put("declared", declared); put("totalPages", total)
                put("rows", buildJsonArray { rows.forEach { row -> add(buildJsonObject {
                    put("url", row.url); put("title", row.title); row.publishedAt?.let { put("publishedAt", it) }
                    put("availability", availability[row.url] ?: "UNKNOWN")
                }) } })
            }.toString()
            val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(evidence.toByteArray()).joinToString("") { "%02x".format(it) }
            return ChapterPage(chapters, if (page < total) page+1 else null,
                notice = if (chapters.any { !it.available }) "بعض الفصول مقفلة أو ذات روابط ملتبسة أو بيانات ناقصة. الفصول السليمة تبقى متاحة." else null,
                allowEmpty = declared == 0, declaredTotal = declared, sourceFingerprint = fingerprint)
        }

        internal fun requireNovel(novel: Novel): String {
            if (novel.sourceId != SOURCE_ID) invalid("Foreign Sun edition")
            return path(novel.url, false).first
        }
        private fun requireChapter(chapter: NovelChapter) {
            val p = path(chapter.url, true)
            if (chapter.title.isBlank() || chapter.publishedRows.any { it.title.isBlank() })
                throw NovelSourceFailure(MISSING_TITLE_MESSAGE, "Sun published chapter identity missing", 422)
            if (!chapter.available || SunovelsAccess.state(chapter) in setOf(SunovelsChapterState.EXPLICITLY_LOCKED, SunovelsChapterState.AMBIGUOUS, SunovelsChapterState.MISSING) || knownAmbiguous(chapter.url) ||
                number(chapter.title)?.let { it.compareTo(p.second!!.toBigDecimal()) != 0 } == true)
                throw NovelSourceFailure(chapter.unavailableReason ?: AMBIGUOUS_MESSAGE, "Sun unavailable/ambiguous chapter",
                    if (SunovelsAccess.state(chapter) == SunovelsChapterState.EXPLICITLY_LOCKED) 403 else 422,
                    code = if (SunovelsAccess.state(chapter) == SunovelsChapterState.EXPLICITLY_LOCKED) "SUN_LOCKED" else "SUN_UNAVAILABLE")
        }
        private fun resolutionCandidate(chapter: NovelChapter, allowMobile: Boolean): NovelChapter =
            if (allowMobile && SunovelsAccess.state(chapter) == SunovelsChapterState.EXPLICITLY_LOCKED)
                SunovelsAccess.probeCandidate(chapter) else SunovelsAccess.retryCandidate(chapter)
        internal fun requireResolutionOwnership(novel: Novel, chapter: NovelChapter, allowMobile: Boolean = mobileDeliveryEnabled) {
            if (requireNovel(novel) != path(chapter.url, true).first) invalid("Foreign Sun chapter edition")
            if (knownAmbiguous(chapter.url) || SunovelsAccess.state(chapter) == SunovelsChapterState.AMBIGUOUS)
                throw NovelSourceFailure(AMBIGUOUS_MESSAGE, "Ambiguous chapter", code = "AMBIGUOUS_ROW")
            val candidate = resolutionCandidate(chapter, allowMobile)
            if (!candidate.available || SunovelsAccess.state(candidate) in setOf(SunovelsChapterState.EXPLICITLY_LOCKED, SunovelsChapterState.MISSING)) requireChapter(chapter)
            if (candidate.title.isBlank()) requireChapter(chapter)
        }
        internal fun requireOwnership(novel: Novel, chapter: NovelChapter) {
            if (requireNovel(novel) != path(chapter.url, true).first) invalid("Foreign Sun chapter edition")
            requireChapter(chapter)
        }
        internal fun validateText(text: NovelText) {
            if (text.paragraphs.isEmpty() || text.paragraphs.none { p -> p.any(Char::isLetter) })
                invalid("Sun empty or incomplete chapter text")
            if (text.paragraphs.take(2).any { it.trim().startsWith("الفصل المطلوب مقفل") })
                throw NovelSourceFailure(LOCKED_MESSAGE, "Sun cached lock notice", 403)
            if (text.paragraphs.take(2).any { p -> listOf("يرجى تسجيل الدخول", "يجب تسجيل الدخول", "تسجيل الدخول لقراءة").any { p.trim().startsWith(it) } })
                throw NovelSourceFailure("هذا الفصل يتطلب تسجيل الدخول في موقع المصدر. يمكنك فتح الموقع الأصلي.", "Sun login notice", 401)
            if (text.paragraphs.take(2).any { p -> listOf("الفصل غير موجود", "هذا الفصل غير متاح", "حدث خطأ أثناء تحميل الفصل", "Chapter not found").any { p.trim().startsWith(it, ignoreCase = true) } })
                invalid("Sun error notice is not chapter text")
        }
    }
}
