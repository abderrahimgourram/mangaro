package eu.kanade.tachiyomi.novels

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.serialization.json.*
import org.jsoup.Jsoup

/** Host JVM replay of production source/parser code. No Android process, UI, device or server. */
fun main(args: Array<String>) = runBlocking {
    val fixtures = File(args[0])
    val replay = ReplaySource(fixtures)
    val http = NovelHttp(replay.client)
    val source = SunovelsSource(http, mobileDelivery = false)
    var checks = 0
    fun verify(condition: Boolean, label: String) { check(condition) { label }; checks++ }
    fun rejected(label: String, code: String? = null, action: () -> Unit) {
        val error = runCatching(action).exceptionOrNull()
        verify(error != null && (code == null || (error as? NovelSourceFailure)?.code == code), label)
    }
    fun raw(name: String) = File(fixtures, "$name.html").readText()
    fun novel(slug: String) = Novel(SunovelsSource.SOURCE_ID, "https://sunovels.com/novel/$slug", slug)
    val birth = novel("birth-of-the-demonic-sword")
    val reverend = novel("reverend-insanity")
    val shadow = novel("shadow-crown-the-rebirth-of-the-cursed-prince")
    val started = System.nanoTime()
    val catalog = source.catalog(1, true, null)
    verify(catalog.novels.size == 24 && catalog.nextPage == 2, "catalog cursor and bound")
    verify(catalog.novels.all { it.originalTitle == SunovelsSource.cleanOriginalTitle(it.originalTitle) }, "catalog original titles are nullable and never image filenames")
    val english = source.search("Reverend", 1)
    val arabic = source.search("القس", 1)
    verify(english.novels.any { it.url == reverend.url } && english.nextPage == null, "public English search")
    verify(arabic.novels.any { it.url == reverend.url }, "public Arabic search")
    for (n in listOf(birth, reverend, shadow)) {
        val detail = source.details(n)
        verify(detail.id == n.id && detail.title.isNotBlank() && !detail.originalTitle.isNullOrBlank(), "independent metadata ${n.title}")
    }
    verify(source.parseCatalog(Jsoup.parse(raw("search-broad"), source.baseUrl), 1, search = true).novels.size == 12, "broad public search")
    verify(source.parseCatalog(Jsoup.parse(raw("search-empty"), source.baseUrl), 1, search = true).novels.isEmpty(), "public no-results search remains empty")
    val first = source.chapters(birth, 1)
    verify(first.chapters.first().url == birth.url + "/0" && first.chapters.first().available, "chapter zero eligible")
    verify(first.nextPage == 2 && first.declaredTotal == 2336 && first.chapters.size == 50, "Birth verified first page")
    val text = source.chapter(first.chapters.first())
    verify(text.paragraphs.isNotEmpty() && text.paragraphs.sumOf { it.length } > 0, "Birth zero actual text")
    val second = source.chapters(birth, 2)
    verify(second.chapters.size == 50 && second.nextPage == 3, "Birth second page")
    var saved = NovelChapterIndex(birth.id)
    val index = NovelChapterIndexer.collect(source, birth, maxPages = 2, onPage = { saved = it })
    verify(index.chapters.size == 100 && !index.complete && index.nextPage == 3 && index.pageFingerprints.size == 2, "bounded checkpoint")
    replay.failPage = 3
    val failed = runCatching { NovelChapterIndexer.collect(source, birth, index, maxPages = 2, onPage = { saved = it }) }.exceptionOrNull()
    verify(failed != null && saved.chapters.size == 100, "later failure preserves verified rows")
    replay.failPage = null
    kotlinx.coroutines.delay(1600) // Actual transport cooldown after the injected network failure.
    replay.mutateBoundary = true
    val changed = runCatching { NovelChapterIndexer.collect(source, birth, index, maxPages = 2) }.exceptionOrNull()
    verify((changed as? NovelSourceFailure)?.code == "SUN_INDEX_CHECKPOINT_CHANGED", "checkpoint mutation rejected")
    replay.mutateBoundary = false
    val locked = source.chapters(shadow, 1)
    verify(locked.chapters.size == 2 && locked.chapters.none { it.available }, "Shadow Start Reading must remain disabled")
    val shadowIndex = NovelChapterIndexer.collect(source, shadow, maxPages = 2)
    verify(shadowIndex.complete && shadowIndex.availableCount == 0, "coverage independent of public access")
    val before = replay.calls.size
    val shadowFailure = runCatching { source.chapter(locked.chapters.first()) }.exceptionOrNull()
    verify((shadowFailure as? NovelSourceFailure)?.code == "SUN_LOCKED" && replay.calls.size == before, "locked listing causes zero reader requests")
    val ri = source.chapters(reverend, 1)
    verify(source.chapter(ri.chapters.first()).paragraphs.isNotEmpty(), "Reverend public reader")
    val ambiguous = source.chapters(reverend, 24)
    val collisions = ambiguous.chapters.filter { it.publishedRows.size > 1 }
    verify(collisions.isNotEmpty() && collisions.none { it.available }, "ambiguous published evidence retained")
    verify(ambiguous.chapters.none { it.available }, "Reverend locked page")
    rejected("known ambiguous address refused", "SUN_UNAVAILABLE") {
        SunovelsSource.requireOwnership(reverend, NovelChapter(reverend.url + "/1153", "1153 الفصل", 0))
    }
    rejected("foreign novel ownership refused", "SUN_OWNERSHIP") { SunovelsSource.requireOwnership(shadow, first.chapters.first()) }
    rejected("foreign destination refused") { source.parseIndex(Jsoup.parse(raw("birth-of-the-demonic-sword-index1").replace(birth.url.substringAfter("sunovels.com"), shadow.url.substringAfter("sunovels.com")), birth.url), birth, 1) }
    rejected("wrong source cursor refused") { source.parseIndex(Jsoup.parse(raw("birth-of-the-demonic-sword-index2"), birth.url), birth, 1) }
    rejected("empty reader refused") { source.parseChapter(raw("birth-of-the-demonic-sword-reader0").replace("chapter-content", "missing-content"), first.chapters.first()) }
    rejected("wrong chapter reader refused") { source.parseChapter(raw("birth-of-the-demonic-sword-reader0"), second.chapters.first()) }
    rejected("interrupted reader refused") { source.parseChapter(raw("birth-of-the-demonic-sword-reader0").replace(Regex("</(?:div|article)>"), ""), first.chapters.first()) }
    rejected("locked reader actual state refused", "SUN_LOCKED") { source.parseChapter(raw("shadow-crown-the-rebirth-of-the-cursed-prince-reader1"), SunovelsAccess.probeCandidate(locked.chapters.first())) }
    rejected("challenge refused", "SUN_BLOCKED") { source.parseCatalog(Jsoup.parse("<form id='challenge-form'></form>", source.baseUrl), 1) }
    val decimalRow = NovelPublishedChapterRow(birth.url + "/1.5", "1.5 الفصل")
    val decimals = SunovelsSource.indexPage(birth, 1, listOf(decimalRow), 1, 1)
    verify(decimals.chapters.single().url.endsWith("/1.5") && decimals.chapters.single().available, "verified decimal row retained (synthetic contract, not live evidence)")
    val policy = SunovelsRequestPolicy()
    verify(policy.reserve("one", 1000) == 0L && policy.reserve("two", 1000) == 1500L, "source start pacing")
    policy.failed("one", 429, 2000, 60000)
    rejected("429 backoff enforced", "SUN_BACKOFF") { policy.reserve("two", 3000) }
    verify(policy.reserve("two", 62001) >= 0, "429 backoff expires")
    policy.failed("blocked", 403, 63000)
    rejected("blocked request never retried", "SUN_BACKOFF") { policy.reserve("blocked", 64000) }
    val root = File(args[1]); root.mkdirs()
    val store = NovelMetadataStore(root)
    store.write("index.json", "{\"generation\":1}")
    store.write("index.json", "{\"generation\":2}")
    File(root, "index.json").writeText("interrupted")
    verify(store.read("index.json") == "{\"generation\":1}", "atomic metadata last-good recovery")
    val bootstrap = Json.parseToJsonElement(java.util.zip.GZIPInputStream(File(args[2]).inputStream()).use { it.readBytes().decodeToString() }).jsonObject
    val parseStart = System.nanoTime()
    val novels = bootstrap["records"]!!.jsonArray.map { SunovelsSource.bootstrapNovel(it.jsonObject) }
    val parseMs = (System.nanoTime() - parseStart) / 1e6
    verify(novels.size == 1461 && novels.distinctBy { it.id }.size == 1461, "bootstrap canonical identities")
    val ingestStart = System.nanoTime()
    val state = NovelWorkReconciler.ingest(NovelCatalogState(), novels)
    val ingestMs = (System.nanoTime() - ingestStart) / 1e6
    val local = SunovelsLocalSearch()
    check(local.page(state, "Reverend", 1, null) != null) // Build the derived index once.
    val searchStart = System.nanoTime()
    repeat(100) { check(local.page(state, if (it % 2 == 0) "Reverend" else "القس", 1, null) != null) }
    val searchMs = (System.nanoTime() - searchStart) / 1e6 / 100
    verify(local.page(state, "Reverend", 1, null)!!.novels.any { it.url == reverend.url }, "offline original title search")
    val aliases = NovelCatalogState(works = listOf(UnifiedNovelWork("test", listOf(birth.copy(author = "مؤلف تجريبي", alternativeTitles = listOf("عنوان بديل"))), birth.id)))
    verify(local.page(aliases, "عنوان بديل", 1, null) != null && local.page(aliases, "مؤلف", 1, null) != null, "offline alias and author search")
    verify(replay.calls.all { it.startsWith("https://sunovels.com/") }, "gateway fully unavailable: every replay request is source-only")
    verify(http.get("https://sunovels.com/conditional-contract", cache = true) == "conditional body", "HTTP metadata first response")
    val conditionalCount = replay.calls.size
    verify(http.get("https://sunovels.com/conditional-contract", cache = true) == "conditional body" && replay.calls.size == conditionalCount, "HTTP TTL hit has no request")
    verify(http.get("https://sunovels.com/conditional-contract", cache = true, refresh = true) == "conditional body" && replay.calls.size == conditionalCount + 1, "HTTP 304 reuses last valid body")
    val blockCount = replay.calls.size
    verify(runCatching { http.get("https://sunovels.com/blocked-contract") }.exceptionOrNull() != null, "HTTP block rejected")
    verify(runCatching { http.get("https://sunovels.com/blocked-contract") }.exceptionOrNull() != null && replay.calls.size == blockCount + 1, "HTTP block never immediately retried")
    verify(runCatching { http.get("https://sunovels.com/redirect-contract") }.exceptionOrNull() != null && replay.calls.none { it.startsWith("https://gateway.invalid") }, "HTTP foreign redirect refused before request")
    verify(!NovelHttp.allowed("https://gateway.invalid/catalog"), "gateway excluded from direct transport allowlist")
    kotlinx.coroutines.delay(1600) // Clear the deliberately injected redirect/network cooldown before new public reader contracts.
    // Report review regressions: narrow metadata cleanup and additive, conservative access evidence.
    verify(SunovelsSource.cleanOriginalTitle("thumbnail_300 (7).jpg") == null, "image filename originalTitle rejected")
    verify(SunovelsSource.cleanOriginalTitle("Small Worlds") == "Small Worlds" && SunovelsSource.cleanOriginalTitle("Cover Story") == "Cover Story", "legitimate title prefixes preserved")
    verify(SunovelsSource.cleanOriginalTitle("86") == "86", "numeric original title preserved")
    verify(novels.none { it.originalTitle?.let { title -> Regex(".*\\.(jpg|jpeg|png|webp|gif|avif|bmp|svg)", RegexOption.IGNORE_CASE).matches(title) } == true }, "bootstrap filename pollution removed after integrity verification")
    val oldNovel = birth.copy(originalTitle = "thumbnail_300 (7).jpg")
    val oldState = NovelCatalogState(works = listOf(UnifiedNovelWork("stable-work", listOf(oldNovel), oldNovel.id)))
    val cleanedState = SunovelsSource.cleanCatalog(oldState)
    verify(cleanedState.works.single().id == "stable-work" && cleanedState.works.single().editions.single().id == oldNovel.id && cleanedState.works.single().editions.single().originalTitle == null, "metadata cleanup preserves work and edition identities")
    val merged = NovelWorkReconciler.ingest(oldState, listOf(birth))
    verify(merged.works.single().editions.single().originalTitle == null, "incoming null never restores polluted prior title")
    verify(SunovelsAccess.state(first.chapters.first()) == SunovelsChapterState.INDEXED_UNVERIFIED, "index absence of content is not a lock or readable proof")
    val unknown = first.chapters[30]
    val legacyLock = unknown.copy(available = false, unavailableReason = SunovelsSource.LOCKED_MESSAGE, sunovelsState = null, sunovelsEvidence = null)
    verify(!SunovelsAccess.mergeIndex(unknown, legacyLock).available, "legacy lock never mass-unlocked")
    val verifiedLock = legacyLock.copy(sunovelsState = SunovelsChapterState.EXPLICITLY_LOCKED, sunovelsEvidence = SunovelsChapterEvidence.PUBLIC_READER)
    verify(SunovelsAccess.mergeIndex(unknown, verifiedLock).sunovelsEvidence == SunovelsChapterEvidence.PUBLIC_READER && !SunovelsAccess.mergeIndex(unknown, verifiedLock).available, "reader lock survives incomplete fresh index")
    verify(SunovelsAccess.failed(unknown, java.io.IOException("offline")).sunovelsState == SunovelsChapterState.TEMPORARILY_UNAVAILABLE, "network failure is not a lock")
    verify(SunovelsAccess.failed(verifiedLock, java.io.IOException("offline")) == verifiedLock, "network failure preserves explicit lock evidence")
    verify(SunovelsAccess.failed(unknown, NovelSourceFailure("empty", "empty", 422)).sunovelsState != SunovelsChapterState.VERIFIED_READABLE, "empty content never readable")
    verify(SunovelsAccess.failed(unknown, NovelSourceFailure("missing", "missing", 404)).sunovelsState == SunovelsChapterState.MISSING, "404 is missing, not locked")
    val ri30 = ri.chapters.single { it.url.endsWith("/30") }
    val readable30 = source.revalidate(reverend, ri30.copy(available = false, unavailableReason = SunovelsSource.LOCKED_MESSAGE, sunovelsState = null, sunovelsEvidence = null))
    verify(readable30.paragraphs.isNotEmpty(), "legacy false lock revalidated through actual public reader 30")
    val nowReadable = SunovelsAccess.readable(ri30)
    verify(SunovelsAccess.readerCandidate(legacyLock.copy(url = ri30.url, title = ri30.title), nowReadable).available, "positive reader proof repairs stale saved reference eligibility")
    val lockedRecheck = runCatching { source.revalidate(shadow, locked.chapters.first()) }.exceptionOrNull() as? NovelSourceFailure
    verify(lockedRecheck?.code == "SUN_LOCKED" && lockedRecheck.sourceVerified, "Shadow revalidation remains explicitly locked")
    verify(SunovelsAccess.failed(locked.chapters.first(), lockedRecheck!!).sunovelsEvidence == SunovelsChapterEvidence.PUBLIC_READER, "actual locked reader evidence persisted")
    val ambiguousBefore = replay.calls.size
    verify(runCatching { source.revalidate(reverend, collisions.first()) }.isFailure && replay.calls.size == ambiguousBefore, "ambiguous revalidation rejected before any GET")
    val watermark = "7e54528eba7d5060شمس الروايات1e22a89e48adc5d1d9607d957ef4afe2"
    verify(!text.paragraphs.joinToString("\n").contains(Regex("[0-9a-fA-F]{16}شمس الروايات[0-9a-fA-F]{32}")), "actual hidden source artifact already removed")
    val dom = Jsoup.parse(raw("birth-of-the-demonic-sword-reader0"), birth.url + "/0")
    dom.selectFirst(".chapter-content")!!.append("<p>ترجمة ونشر: شمس الروايات</p><p>السابق</p><p>" + watermark + "</p><p class='d-none'>" + watermark + "</p>")
    val attributed = source.parseChapter(dom.outerHtml(), first.chapters.first())
    verify(attributed.paragraphs.any { it.contains("ترجمة ونشر: شمس الروايات") } && attributed.paragraphs.any { it == "السابق" }, "visible attribution and story words preserved")
    verify(attributed.paragraphs.count { it.contains(watermark) } == 1, "visible hex text preserved; only hidden element removed")
    val storedJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    val originalItem = NovelLibraryItem(oldNovel, saved = true, favorite = true, bookmarks = setOf(ri30.id), position = NovelReadingPosition(ri30, paragraph = 8, offset = 13, anchor = "stable-anchor"))
    val restoredItem = storedJson.decodeFromString<NovelLibraryItem>(storedJson.encodeToString(NovelLibraryItem.serializer(), originalItem))
    verify(restoredItem == originalItem && restoredItem.position!!.chapter.id == ri30.id, "library bookmarks/progress JSON round trip unchanged")
    val legacyChapterJson = "{\"url\":\"" + ri30.url + "\",\"title\":\"" + ri30.title + "\",\"order\":29,\"available\":false,\"unavailableReason\":\"" + SunovelsSource.LOCKED_MESSAGE + "\"}"
    val legacy = storedJson.decodeFromString<NovelChapter>(legacyChapterJson)
    verify(legacy.sunovelsState == null && SunovelsAccess.state(legacy) == SunovelsChapterState.EXPLICITLY_LOCKED && !legacy.available, "old cache schema decodes conservatively with no migration")
    verify(SunovelsChapterState.entries.toSet() == setOf(SunovelsChapterState.INDEXED_UNVERIFIED, SunovelsChapterState.VERIFIED_READABLE, SunovelsChapterState.EXPLICITLY_LOCKED, SunovelsChapterState.AMBIGUOUS, SunovelsChapterState.MISSING, SunovelsChapterState.TEMPORARILY_UNAVAILABLE), "six distinct access states")
    val otherSource = oldNovel.copy(sourceId = "novel.seanovel")
    verify(SunovelsSource.cleanMetadata(otherSource) == otherSource, "metadata cleanup never changes other sources")
    val lockedRequests = replay.calls.size
    verify(runCatching { source.revalidate(shadow, locked.chapters.first()) }.isFailure && replay.calls.size == lockedRequests, "confirmed reader lock recheck respects URL cooldown")
    val evidenceStore = NovelMetadataStore(File(root, "reader-evidence"))
    evidenceStore.write("one.json", storedJson.encodeToString(NovelChapter.serializer(), verifiedLock))
    val recoveredProof = storedJson.decodeFromString<NovelChapter>(evidenceStore.read("one.json")!!)
    verify(recoveredProof == verifiedLock && SunovelsAccess.state(recoveredProof) == SunovelsChapterState.EXPLICITLY_LOCKED, "reader evidence survives existing atomic-cache round trip")
    val riOwner30 = ri.chapters.single { it.url.endsWith("/30") }
    val foreignProof = nowReadable.copy(url = shadow.url + "/1")
    verify(!SunovelsAccess.readerCandidate(legacyLock, foreignProof).available, "foreign reader evidence cannot unlock a cached chapter")
    verify(SunovelsAccess.probeCandidate(riOwner30).id == riOwner30.id && riOwner30.id != SunovelsAccess.probeCandidate(riOwner30.copy(url = reverend.url + "/30.0")).id, "URL identities remain distinct even when numeric values are equal")
    val sampled135 = NovelChapter(birth.url + "/135", "135 الفصل", 134)
    verify(source.chapter(sampled135).paragraphs.isNotEmpty(), "confirmed Birth 135 uses actual native reader validation")
    val sampled675 = NovelChapter(birth.url + "/675", "675 الفصل", 674)
    val error675 = runCatching { source.chapter(sampled675) }.exceptionOrNull() as? NovelSourceFailure
    verify(error675?.code == "SUN_LOCKED" && error675.sourceVerified, "confirmed Birth 675 cannot be unlocked from missing content")
    verify(SunovelsAccess.failed(sampled675, error675!!).sunovelsState == SunovelsChapterState.EXPLICITLY_LOCKED, "675 records explicit reader lock")
    val lostTitle = ri30.copy(available = false, unavailableReason = SunovelsSource.MISSING_TITLE_MESSAGE,
        publishedRows = listOf(NovelPublishedChapterRow(ri30.url, "")), sunovelsState = SunovelsChapterState.MISSING)
    rejected("missing title never eligible for fresh remote reading") { SunovelsSource.requireOwnership(reverend, lostTitle) }
    verify(runCatching { SunovelsSource.requireOwnership(reverend, SunovelsAccess.storedCandidate(lostTitle)) }.isSuccess && lostTitle.publishedRows.single().title.isEmpty(), "valid stored-copy validation tolerates later incomplete metadata without mutating the index")
    verify(SunovelsAccess.storedCandidate(collisions.first()) == collisions.first(), "stored-copy projection cannot erase ambiguity")
    // Failure injection uses real source/transport/coalescer; all destination URLs originate in accepted rows.
    val zero = first.chapters.first()
    val rawZero = raw("birth-of-the-demonic-sword-reader0")
    val savedNoah = zero.copy(title = "ولادة نوح")
    val resumeItem = NovelLibraryItem(birth.copy(chapterCount = 2335), position = NovelReadingPosition(savedNoah, 7, 31), bookmarks = setOf(savedNoah.id))
    val restoredResume = storedJson.decodeFromString<NovelLibraryItem>(storedJson.encodeToString(NovelLibraryItem.serializer(), resumeItem))
    verify(restoredResume.position!!.chapter.title == "ولادة نوح" && restoredResume.position!!.chapter.id == zero.id, "synthetic legacy Resume stores exact URL independently from descriptive title")
    verify(source.parseChapter(rawZero, restoredResume.position!!.chapter).paragraphs.isNotEmpty(), "descriptive Resume title vs generic verified zero header safely reconciles")
    verify(SunovelsReaderDiagnostics.get(zero.id)?.outcome == SunovelsOutcome.IDENTITY_MISMATCH_REPAIRED, "typed cosmetic identity repair proof")
    verify(restoredResume.position!!.paragraph == 7 && restoredResume.position!!.offset == 31 && restoredResume.bookmarks == resumeItem.bookmarks, "reader resolution cannot reset persisted offsets or bookmarks")
    verify(first.declaredTotal == 2336 && first.chapters.first().id.endsWith("/0") && novels.single { it.id == birth.id }.chapterCount == null, "2335 seed hypothesis excluded; source total 2336 includes zero")
    val bareZero = rawZero.replace("0 الفصل", "0")
    verify(source.parseChapter(bareZero, savedNoah).paragraphs.isNotEmpty(), "bare numeric reader title never rejects descriptive Resume subtitle")
    val divDom = Jsoup.parse(rawZero, zero.url)
    divDom.selectFirst(".chapter-content")!!.html("<div>" + "هذه قصة عربية مرئية<br>تستمر القصة هنا<span> مع نص صحيح</span>".repeat(4) + "</div>")
    verify(source.parseChapter(divDom.outerHtml(), zero).paragraphs.joinToString().contains("تستمر القصة"), "non-p div/span/br story uses shared extractor")
    verify(SunovelsReaderDiagnostics.get(zero.id)?.strategy == "HTML_BR_BLOCKS", "non-p strategy reported")
    val flightDom = Jsoup.parse(rawZero, zero.url)
    flightDom.select(".chapter-content").remove()
    verify(source.parseChapter(flightDom.outerHtml(), zero).paragraphs.isNotEmpty() && SunovelsReaderDiagnostics.get(zero.id)?.strategy == "RSC_FLIGHT", "public unlocked owner-bound byte-framed Flight content fallback")
    val blankDom = Jsoup.parse(rawZero, zero.url)
    blankDom.selectFirst(".chapter-content")!!.empty()
    blankDom.select("script").forEach { script ->
        val data = script.data()
        val push = runCatching { Json.parseToJsonElement(data.substringAfter("self.__next_f.push(").substringBeforeLast(")")) as? JsonArray }.getOrNull()
        val chunk = push?.getOrNull(1)?.let { it as? JsonPrimitive }?.contentOrNull
        if (push != null && chunk != null && chunk.contains("\"chapterNumber\":0")) {
            val changed = chunk.replace(Regex("\"content\":\"[^\"]*\""), "\"content\":\"\"")
            script.html("self.__next_f.push(" + JsonArray(listOf(push[0], JsonPrimitive(changed))) + ")")
        }
    }
    val blankFailure = runCatching { source.parseChapter(blankDom.outerHtml(), zero) }.exceptionOrNull() as? NovelSourceFailure
    verify(blankFailure?.code == "CONTAINER_EMPTY", "empty story produces precise parser error")
    val poisoned = SunovelsAccess.failed(zero, blankFailure!!)
    verify(!SunovelsAccess.durableEvidence(poisoned) && SunovelsAccess.retryCandidate(poisoned).available, "parser failure is retryable and ineligible for durable evidence persistence")
    val previous = SunovelsAccess.readable(zero)
    verify(SunovelsAccess.failed(previous, java.net.SocketTimeoutException()) == previous, "timeout after positive load retains last-good proof")
    verify(SunovelsAccess.retryCandidate(SunovelsAccess.failed(zero, NovelSourceFailure("missing", "missing", 404))).available, "legacy transport 404 negative cache cannot permanently refuse Retry")
    verify(SunovelsAccess.retryCandidate(verifiedLock) == verifiedLock && SunovelsAccess.durableEvidence(verifiedLock), "verified locks never automatically cleared")
    fun injected(): Pair<ReplaySource,SunovelsSource> {
        val transport = ReplaySource(fixtures)
        return transport to SunovelsSource(NovelHttp(transport.client), mobileDelivery = false)
    }
    var injectedRequests = 0
    fun stop(replaySource:ReplaySource) { injectedRequests += replaySource.calls.size; replaySource.client.dispatcher.executorService.shutdown(); replaySource.client.connectionPool.evictAll() }
    for ((status,expected) in listOf(403 to SunovelsOutcome.ACCESS_RESTRICTED, 404 to SunovelsOutcome.NOT_FOUND,
        429 to SunovelsOutcome.RATE_LIMITED, 503 to SunovelsOutcome.NETWORK_TRANSIENT)) {
        val (transport,direct)=injected();transport.statuses[zero.url]=status
        val failure=runCatching { direct.chapter(zero) }.exceptionOrNull()
        verify((failure as? NovelSourceFailure)?.code == "HTTP_$status" && SunovelsReaderDiagnostics.get(zero.id)?.outcome == expected, "HTTP $status typed classification")
        verify(!SunovelsAccess.durableEvidence(SunovelsAccess.failed(zero,failure!! as Exception)), "HTTP $status cannot persist permanent lock/readable evidence")
        if(status==429) {
            verify((failure as NovelSourceFailure).retryAfterMs == 60000L, "Retry-After seconds respected")
            runCatching { direct.chapter(zero) }
            verify(transport.calls.size==1, "429 immediate Retry invokes no HTTP during cooldown")
        }
        stop(transport)
    }
    for (destination in listOf("https://gateway.invalid/catalog", birth.url, "https://sunovels.com/login")) {
        val (transport,direct)=injected();transport.statuses[zero.url]=302;transport.redirects[zero.url]=destination
        // A same-host nonchapter response may contain valid HTML; identity must still fail before accepting it.
        transport.bodies[destination]=rawZero
        val error=runCatching { direct.chapter(zero) }.exceptionOrNull()
        verify(error != null, "redirect to $destination refused")
        verify(transport.calls.none { it.startsWith("https://gateway.invalid") }, "redirect never invokes another host")
        stop(transport)
    }
    val (interrupted,interruptSource)=injected();interrupted.interruptBody=true
    verify(runCatching { interruptSource.chapter(zero) }.isFailure && SunovelsReaderDiagnostics.get(zero.id)?.outcome==SunovelsOutcome.NETWORK_TRANSIENT, "interrupted body stream is transient")
    stop(interrupted)
    val (coalesced,coalescedSource)=injected();coalesced.delayMs=150
    val one=async { coalescedSource.chapter(zero) }
    val two=async { coalescedSource.revalidate(birth,zero) }
    verify(one.await()==two.await() && coalesced.calls.size==1, "two reader/revalidation calls share one public HTTP GET")
    stop(coalesced)
    val (stale,staleSource)=injected()
    val staleRow = zero.copy(title="1 الفصل", sourcePage=1)
    verify(staleSource.chapter(staleRow).paragraphs.isNotEmpty(), "wrong legacy numeric label repaired by exact published URL on targeted page")
    verify(stale.calls.size==2 && stale.calls.single { it.contains("activeTab") }.contains("page=0") && SunovelsReaderDiagnostics.get(zero.id)?.indexRepair?.declaredTotal==2336, "stale reader repair costs one index page and one reader GET")
    stop(stale)
    val (subtitleTransport,subtitleSource)=injected()
    verify(subtitleSource.chapter(savedNoah).paragraphs.isNotEmpty() && subtitleTransport.calls.size==2, "descriptive saved Resume subtitle refreshes only its published index page")
    verify(SunovelsReaderDiagnostics.get(zero.id)?.chapter?.title=="ولادة نوح" && SunovelsReaderDiagnostics.get(zero.id)?.pageTitle=="0 الفصل", "diagnostics preserve displayed saved title separately from public title")
    stop(subtitleTransport)
    val oldBody = text
    store.write("last-good-body.json", storedJson.encodeToString(NovelText.serializer(),oldBody))
    verify(!SunovelsAccess.durableEvidence(poisoned) && storedJson.decodeFromString<NovelText>(store.read("last-good-body.json")!!) == oldBody, "parser failure leaves stored last-good chapter untouched")
    val copy = SunovelsReaderDiagnostics.get(zero.id)!!.copyReport()
    verify(!copy.contains(oldBody.paragraphs.first()) && !copy.contains("Authorization:") && !copy.contains("Cookie:"), "copyable diagnostics excludes body and private transport headers")
    val preservedRows = saved.chapters
    verify(source.parseChapter(rawZero,zero).paragraphs.isNotEmpty() && saved.chapters==preservedRows, "reader open after interrupted indexing independent from index completeness")
    val (timeoutTransport,timeoutSource)=injected()
    val validBeforeTimeout=timeoutSource.chapter(zero)
    timeoutTransport.timeoutUrl=zero.url
    val timeoutError=runCatching { timeoutSource.chapter(zero) }.exceptionOrNull() as? NovelSourceFailure
    verify(timeoutError?.code=="TIMEOUT" && SunovelsReaderDiagnostics.get(zero.id)?.outcome==SunovelsOutcome.NETWORK_TRANSIENT, "actual transport timeout after successful load has precise retryable outcome")
    verify(SunovelsAccess.failed(previous,timeoutError!!)==previous && validBeforeTimeout==text, "transport timeout preserves earlier valid content and access proof")
    stop(timeoutTransport)
    val headDom = Jsoup.parse(rawZero, zero.url)
    headDom.selectFirst("h2")!!.text("الفصل 0")
    verify(source.parseChapter(headDom.outerHtml(), savedNoah).paragraphs.isNotEmpty(), "generic Arabic header word-order cosmetic variation allowed")
    val wrongHead = Jsoup.parse(rawZero, zero.url)
    wrongHead.selectFirst("h2")!!.text("الفصل 1")
    rejected("a generic header with wrong number remains unsafe") { source.parseChapter(wrongHead.outerHtml(), savedNoah) }
    val hiddenRoot = Jsoup.parse(rawZero, zero.url)
    hiddenRoot.selectFirst(".chapter-content")!!.addClass("d-none")
    rejected("hidden whole-container text never exposed through fallback") { source.parseChapter(hiddenRoot.outerHtml(), zero) }
    verify(SunovelsReaderDiagnostics.retainedDiagnosticCharacters() < 100_000, "bounded diagnostic metadata holds no story payloads")
    // v3 title, multi-format, stale-cache and explicit-Retry regressions.
    fun readerVariant(title: String, chapterNumber: Int = 0): String {
        val changed = Jsoup.parse(rawZero, zero.url)
        changed.select("h2").forEach { it.text(title) }
        changed.select("script").forEach { script ->
            val data = script.data()
            val push = runCatching { Json.parseToJsonElement(data.substringAfter("self.__next_f.push(").substringBeforeLast(")")) as? JsonArray }.getOrNull()
            val chunk = push?.getOrNull(1)?.let { it as? JsonPrimitive }?.contentOrNull
            if (push != null && chunk != null) {
                val wire = chunk.replace("\"title\":\"0 الفصل\"", "\"title\":\"" + title + "\"")
                    .replace(Regex("\"chapterNumber\":0(?=[,}])"), "\"chapterNumber\":" + chapterNumber)
                script.html("self.__next_f.push(" + JsonArray(listOf(push[0], JsonPrimitive(wire))) + ")")
            }
        }
        return changed.outerHtml()
    }
    verify(source.parseChapter(readerVariant("عنوان صفحة وصفي مختلف"), savedNoah).paragraphs.isNotEmpty(), "different descriptive titles cannot override verified owner/route/number/content")
    verify(SunovelsSource.bestTitle(savedNoah.title, "0") == savedNoah.title && SunovelsSource.bestTitle("0", "عنوان وصفي") == "عنوان وصفي", "best descriptive title survives numeric page labels")
    val bare1743 = zero.copy(url = birth.url + "/1743", title = "عنوان محفوظ", publishedRows = emptyList())
    verify(source.parseChapter(readerVariant("1743", 1743), bare1743).paragraphs.isNotEmpty() &&
        SunovelsReaderDiagnostics.get(bare1743.id)?.pageNumber == "1743", "bare 1743 header plus verified 1743 structured identity accepted (synthetic)")
    val liveOne = File(fixtures, "birth-of-the-demonic-sword-reader1.html")
    if (liveOne.isFile) {
        val oneRow = first.chapters.single { it.id.endsWith("/1") }.copy(title = "ولادة نوح")
        verify(source.parseChapter(liveOne.readText(), oneRow).paragraphs.isNotEmpty(), "fresh live /1 sample with descriptive saved subtitle accepted without assigning phone identity")
        verify(SunovelsReaderDiagnostics.get(oneRow.id)?.pageNumber == "1", "live /1 reader number verified")
    }
    val singleBlock = Jsoup.parse(rawZero, zero.url)
    singleBlock.selectFirst(".chapter-content")!!.html("<p>" + "هذه قصة عربية طويلة مرئية ومحفوظة كما نشرها المصدر. ".repeat(5) + "</p>")
    verify(source.parseChapter(singleBlock.outerHtml(), savedNoah).paragraphs.size == 1, "single meaningful Arabic paragraph above 120 characters accepted")
    val emptyDomWithFlight = Jsoup.parse(rawZero, zero.url)
    emptyDomWithFlight.selectFirst(".chapter-content")!!.empty()
    verify(source.parseChapter(emptyDomWithFlight.outerHtml(), zero).paragraphs.isNotEmpty() && SunovelsReaderDiagnostics.get(zero.id)?.strategy == "RSC_FLIGHT", "DOM placeholder recovers from ownership-bound public Flight field")
    val splitFlight = Jsoup.parse(rawZero, zero.url)
    splitFlight.select(".chapter-content").remove()
    val chunks = splitFlight.select("script").mapNotNull { script ->
        runCatching { (Json.parseToJsonElement(script.data().substringAfter("self.__next_f.push(").substringBeforeLast(")")) as? JsonArray)
            ?.getOrNull(1)?.let { it as? JsonPrimitive }?.contentOrNull }.getOrNull()
    }.joinToString("")
    splitFlight.select("script").filter { it.data().contains("self.__next_f.push") }.forEach { it.remove() }
    val payloads = chunks.chunked(997).map { chunk -> "self.__next_f.push(" + JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(chunk))).toString().replace("<", "\\u003c") + ");" }
    splitFlight.body().appendElement("script").html(payloads.joinToString(""))
    verify(source.parseChapter(splitFlight.outerHtml(), zero).paragraphs.isNotEmpty(), "all consecutive Flight pushes in one script reassembled without truncating scan")
    val bodyFieldFlight = splitFlight.clone()
    bodyFieldFlight.select("script").filter { it.data().contains("self.__next_f.push") }.forEach { it.remove() }
    val bodyWire = chunks.replace("\"content\":", "\"body\":")
    bodyFieldFlight.body().appendElement("script").html("self.__next_f.push(" + JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(bodyWire))).toString().replace("<", "\\u003c") + ")")
    verify(source.parseChapter(bodyFieldFlight.outerHtml(), zero).paragraphs.isNotEmpty(), "owner-bound unlocked public body field supported without scanning unrelated story strings")
    val explicitBanner = Jsoup.parse(rawZero, zero.url)
    explicitBanner.selectFirst(".chapter-content")!!.prepend("<h3>الفصل المطلوب مقفل</h3>")
    rejected("explicit visible lock notice wins over unlocked metadata/content", "SUN_LOCKED") { source.parseChapter(explicitBanner.outerHtml(), zero) }
    val legacyIndex = index.copy(complete = true, declaredTotal = 2335, nextPage = null, indexParserVersion = 0)
    store.write("legacy-index.json", storedJson.encodeToString(NovelChapterIndex.serializer(), legacyIndex))
    store.write("library-preserved.json", storedJson.encodeToString(NovelLibraryItem.serializer(), restoredResume))
    val libraryBytes = store.read("library-preserved.json")!!
    val bodyBytes = store.read("last-good-body.json")!!
    val oldSnapshot = storedJson.decodeFromString<NovelChapterIndex>(store.read("legacy-index.json")!!)
    verify(SunovelsIndexSnapshots.stale(oldSnapshot), "old snapshot version invalid even when parser hash and complete flag look current")
    val (versionTransport,versionSource) = injected()
    var display = oldSnapshot
    val refreshed = NovelChapterIndexer.collect(versionSource, birth, oldSnapshot, maxPages = 2, onPage = { incoming ->
        display = SunovelsIndexSnapshots.visible(oldSnapshot, incoming)
        store.write("refreshed-index.json", storedJson.encodeToString(NovelChapterIndex.serializer(), incoming))
    })
    verify(refreshed.indexParserVersion == 3 && refreshed.declaredTotal == 2336 && refreshed.fetchedPages == setOf(1,2), "stale 2335 snapshot automatically restarts public pages 0 and 1 and accepts source 2336 total")
    verify(versionTransport.calls.size == 2 && versionTransport.calls.first().endsWith("page=0"), "stale snapshot refresh costs only two bounded index GETs")
    verify(display.declaredTotal == 2336 && oldSnapshot.chapters.all { old -> display.chapters.any { it.id == old.id } }, "old rows retained in live refresh projection")
    verify(store.read("library-preserved.json") == libraryBytes && store.read("last-good-body.json") == bodyBytes &&
        storedJson.decodeFromString<NovelLibraryItem>(libraryBytes).position == restoredResume.position, "snapshot parser upgrade never rewrites progress/bookmarks/valid downloaded text")
    verify(!SunovelsIndexSnapshots.stale(refreshed) && SunovelsIndexSnapshots.visible(oldSnapshot.copy(chapters = listOf(verifiedLock)), refreshed).chapters.first { it.id == verifiedLock.id }.sunovelsState == SunovelsChapterState.EXPLICITLY_LOCKED,
        "fresh checkpoint version and previously verified lock evidence preserved")
    stop(versionTransport)
    val (manualTransport,manualSource) = injected()
    manualTransport.timeoutUrl = zero.url
    runCatching { manualSource.chapter(zero) }
    manualTransport.timeoutUrl = null
    val healed = manualSource.resolvePublicChapter(SunovelsAccess.retryCandidate(poisoned), manualRetry = true)
    verify(healed.text.paragraphs.isNotEmpty() && manualTransport.calls.size == 2, "explicit Retry clears local timeout backoff and transient parser state immediately with one GET")
    stop(manualTransport)
    val (mappingTransport,mappingSource) = injected()
    mappingTransport.oneShotStatuses[zero.url] = 404
    val mappingResult = mappingSource.resolvePublicChapter(savedNoah, manualRetry = true)
    verify(mappingResult.text.paragraphs.isNotEmpty() && mappingResult.report.indexRepair?.declaredTotal == 2336,
        "manual 404 repairs metadata from a source-published exact-URL row before one reader retry")
    verify(mappingTransport.calls.size == 3 && mappingTransport.calls.count { it.contains("page=0") } == 1,
        "targeted recovery bounded to two chapter GETs and one index page, no guessed address")
    stop(mappingTransport)
    val manualPolicy = SunovelsRequestPolicy()
    manualPolicy.failed("rate", 429, 1000, 60000); manualPolicy.manualRetry("rate")
    rejected("explicit Retry still honours server Retry-After", "SUN_BACKOFF") { manualPolicy.reserve("rate", 1001) }
    manualPolicy.failed("lock", 403, 62000); manualPolicy.manualRetry("lock")
    rejected("explicit Retry cannot bypass confirmed lock/access cooldown", "SUN_BACKOFF") { manualPolicy.reserve("lock", 62001) }
    val localPolicy = SunovelsRequestPolicy()
    localPolicy.failed("timeout", null, 1000); localPolicy.manualRetry("timeout")
    verify(localPolicy.reserve("timeout", 1001) == 0L, "manual local-failure retry retains no transient cooldown")
    verify(SunovelsReaderDiagnostics.get(zero.id)?.copyReport()?.contains("indexNumber=0") == true, "copy diagnostic includes exact persisted URL-derived expected number")
    // SeaNovel source parsing alone is insufficient: the repository gate must also accept valid two-block text.
    val seaTwoBlocks = NovelText(listOf("ولادة نوح", "هذه قصة عربية طويلة ذات محتوى سردي واضح كما نشرها المصدر. ".repeat(100)))
    verify(runCatching { validateNovelRepositoryContent(SeaNovelSource.SOURCE_ID, seaTwoBlocks) }.isSuccess, "Sea repository accepts validated long two-block chapters")
    verify(runCatching { validateNovelRepositoryContent("novel.kolnovel", seaTwoBlocks) }.isFailure &&
        runCatching { validateNovelRepositoryContent("novel.cenele", seaTwoBlocks) }.isFailure, "other-source repository three-block policy unchanged")
    verify(runCatching { validateNovelRepositoryContent(SeaNovelSource.SOURCE_ID, NovelText(listOf("السابق", "التالي"))) }.isFailure, "Sea repository rejects two-block navigation pages")
    verify(runCatching { validateNovelRepositoryContent(SeaNovelSource.SOURCE_ID, NovelText(listOf("الفصل المطلوب مقفل", seaTwoBlocks.paragraphs.last()))) }.isFailure, "Sea repository retains explicit lock protection")
    val result = buildJsonObject {
        put("checks", checks); put("gatewayRequests", 0); put("sourceReplayRequests", replay.calls.size); put("failureInjectionRequests", injectedRequests); put("diagnosticRetainedCharacters", SunovelsReaderDiagnostics.retainedDiagnosticCharacters())
        put("bootstrapRecords", novels.size); put("bootstrapParseMs", parseMs); put("registryIngestMs", ingestMs)
        put("localSearchMeanMs", searchMs); put("hostJvmMaxHeapBytes", Runtime.getRuntime().maxMemory())
        put("hostJvmUsedHeapBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
        put("totalMs", (System.nanoTime() - started) / 1e6); put("androidRuntimeTested", false)
    }
    println(result)
    replay.client.dispatcher.executorService.shutdown()
    replay.client.connectionPool.evictAll()
}
