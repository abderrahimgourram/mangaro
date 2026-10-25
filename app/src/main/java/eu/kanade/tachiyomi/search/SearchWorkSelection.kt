package eu.kanade.tachiyomi.search

import tachiyomi.domain.manga.service.CatalogIdentity
import tachiyomi.domain.manga.service.VerifiedCatalogIdentity
import java.text.Normalizer
import java.util.Locale

/** Search presentation only: these keys never replace source, library or chapter identities. */
internal enum class SearchWorkKind { COMIC, NOVEL }
internal data class SearchChapterEvidence(
    val distinct: Int? = null,
    val complete: Boolean = false,
    val readable: Boolean? = null,
    val checkedAt: Long = 0,
    val indexUsable: Boolean? = null,
    val indexUncertain: Boolean = false,
) {
    init {
        require(distinct == null || distinct >= 0)
        require(!complete || distinct != null)
    }
    val verifiedCount get() = distinct?.takeIf { complete }
    fun label(): String? = distinct?.let { if (complete) "$it فصل موثّق" else "$it فصل مفهرس على الأقل" }
}
internal data class SearchWorkCandidate(
    val id: String,
    val source: String,
    val kind: SearchWorkKind,
    val title: String,
    val aliases: Set<String> = emptySet(),
    val externalIds: Map<String, String> = emptyMap(),
    val author: String? = null,
    val artist: String? = null,
    val format: String? = null,
    val edition: String? = null,
    val year: Int? = null,
    val priority: Int = Int.MAX_VALUE,
    val chapters: SearchChapterEvidence = SearchChapterEvidence(),
    val coverDigest: String? = null,
    val catalogIdentity: CatalogIdentity? = null,
    val originalTitle: String? = null,
    val metadataOrigin: String = "source-result",
    val queryMatchRank: Long = Long.MAX_VALUE,
    val queryMatchReason: String = "",
)
internal data class SearchWorkGroup(
    val key: String,
    val members: List<SearchWorkCandidate>,
    val winner: SearchWorkCandidate,
    val provisional: Boolean,
    val identityDecision: WorkIdentityDecision? = null,
)

internal object SearchWorkIdentity {
    data class Thresholds(
        val exactCharacters: Int = 10,
        val exactTokens: Int = 2,
        val fuzzySimilarity: Double = .92,
        val sameSourceTitleTokens: Int = 6,
        val sameSourceTokenDice: Double = .83,
    )
    private val unknown = setOf("unknown", "anonymous", "غير معروف", "مجهول", "n a", "none")
    private val generic = setOf("the", "a", "of", "king", "dark", "الملك", "المظلم", "رواية", "novel")
    private val qualifiers =
        Regex(
            "\\b(?:\\d+|ii|iii|iv|v|vi|wn|ln|sequel|prequel|remake|reboot|adaptation|season|part|volume|vol|side story|fan fiction)\\b|(?:الجزء|الموسم|المجلد|تكملة|اقتباس|جانبية)\\s*\\S*",
        )
    private val trusted = setOf("anilist", "myanimelist", "mangadex", "mangaupdates", "kitsu", "novelupdates")
    private val marks = Regex("\\p{M}+")
    private val alefs = Regex("[أإآٱ]")
    private val punctuation = Regex("[^\\p{L}\\p{N}]+")
    private val spaces = Regex("\\s+")
    private val normalized = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun normalize(value: String): String {
        if (normalized.size > 2048) normalized.clear()
        return normalized.getOrPut(value) {
            Normalizer.normalize(value, Normalizer.Form.NFKD)
                .replace(marks, "").replace("ـ", "").replace(alefs, "ا").replace('ى', 'ي')
                .lowercase(Locale.ROOT).replace(punctuation, " ").trim().replace(spaces, " ")
        }
    }
    private fun name(value: String, kind: SearchWorkKind) = normalize(value).let {
        if (kind ==
            SearchWorkKind.NOVEL
        ) {
            it.removePrefix("رواية ")
        } else {
            it
        }
    }
    private fun names(c: SearchWorkCandidate) = (c.aliases + c.title).flatMap { listOf(it) + it.split('|') }
        .map { name(it, c.kind) }.filter { it.isNotBlank() }.toSet()
    private val titleGlue = setOf("a", "an", "the", "of", "in", "to", "and", "for")
    internal fun possibleSameSourceTitles(
        a: SearchWorkCandidate,
        b: SearchWorkCandidate,
        t: Thresholds = Thresholds(),
    ): Boolean {
        if (a.id == b.id || a.source != b.source || a.kind != b.kind) return false
        val x = name(a.title, a.kind).split(' ').filter { it !in titleGlue }.toSet()
        val y = name(b.title, b.kind).split(' ').filter { it !in titleGlue }.toSet()
        return minOf(x.size, y.size) >= t.sameSourceTitleTokens &&
            2.0 * x.intersect(y).size / (x.size + y.size) >= t.sameSourceTokenDice
    }
    fun enrichmentCandidates(input: List<SearchWorkCandidate>): Set<String> = (
        input.flatMap { c ->
            names(c).filter { it.length >= 5 }.map { (c.kind to it) to c }
        }.groupBy { it.first }
            .values.filter { rows -> rows.map { it.second.id }.distinct().size > 1 }.flatten().map { it.second.id } +
            input.take(128).let { bounded ->
                bounded.filter { a -> bounded.any { b -> possibleSameSourceTitles(a, b) } }.map { it.id }
            }
        ).toSet()
    private fun declared(s: String?) = s?.let(::normalize)?.takeIf { it.isNotBlank() && it !in unknown }
    private fun creator(s: String?) = declared(s)?.takeIf { it.length >= 3 }
    private fun contradictory(a: String?, b: String?) = declared(a)?.let { x -> declared(b)?.let { x != it } } == true
    internal fun catalog(c: SearchWorkCandidate) = c.catalogIdentity ?: CatalogIdentity(
        c.source,
        c.id,
        c.id,
        c.externalIds,
        format = c.format,
        edition = c.edition,
    )

    /** Veto contradictory independent evidence even in an established presentation group. */
    private fun medium(c: SearchWorkCandidate): SearchWorkKind = when (c.format?.let(::normalize)) {
        "novel", "light novel", "web novel", "ln", "wn", "رواية", "روايه" -> SearchWorkKind.NOVEL
        "comic", "manga", "manhwa", "manhua", "مانهوا", "مانجا" -> SearchWorkKind.COMIC
        else -> c.kind
    }
    internal fun conflicts(a: SearchWorkCandidate, b: SearchWorkCandidate): Boolean =
        a.kind != b.kind || medium(a) != medium(b) ||
            tachiyomi.domain.manga.service.CatalogFormatCompatibility.conflicts(a.format, b.format) ||
            contradictory(a.edition, b.edition) ||
            a.externalIds.keys.intersect(b.externalIds.keys).intersect(trusted).any { key ->
                val x = a.externalIds[key]
                val y = b.externalIds[key]
                !x.isNullOrBlank() && !y.isNullOrBlank() && normalize(x) !in unknown && normalize(y) !in unknown &&
                    x != "0" &&
                    y != "0" &&
                    x != y
            }

    /** Candidate lookup only; matches() remains the authority/conflict decision. */
    internal fun verifiedLookupKeys(c: SearchWorkCandidate): Set<String> {
        val value = catalog(c)
        return buildSet {
            add("route:${value.source}:${value.route}")
            value.providerId?.takeIf { it.isNotBlank() }?.let { add("provider:${value.source}:$it") }
            value.reviewedWorkKey?.takeIf { Regex("[a-f0-9]{64}").matches(it) }?.let { add("work:$it") }
            value.externalIds.forEach { (authority, id) ->
                if (authority in setOf("anilist", "myanimelist", "mangadex", "mangaupdates", "kitsu") &&
                    id.isNotBlank()
                ) {
                    add("external:$authority:${id.trim()}")
                }
            }
        }
    }
    fun matches(a: SearchWorkCandidate, b: SearchWorkCandidate, t: Thresholds = Thresholds()): Boolean {
        if (conflicts(a, b)) return false
        if (a.kind == SearchWorkKind.COMIC && b.kind == SearchWorkKind.COMIC) {
            return VerifiedCatalogIdentity.matches(catalog(a), catalog(b))
        }
        if (a.id == b.id && a.source == b.source && a.kind == b.kind) return true
        if (a.kind != b.kind || a.title.isBlank() || b.title.isBlank()) return false
        if (tachiyomi.domain.manga.service.CatalogFormatCompatibility.conflicts(a.format, b.format) ||
            contradictory(a.edition, b.edition) ||
            (a.year != null && b.year != null && a.year != b.year)
        ) {
            return false
        }
        val qa = names(a).flatMap { qualifiers.findAll(it).map { m -> m.value }.toList() }.toSet()
        val qb = names(b).flatMap { qualifiers.findAll(it).map { m -> m.value }.toList() }.toSet()
        if (qa != qb) return false
        val ids = a.externalIds.keys.intersect(b.externalIds.keys).intersect(trusted).filter { k ->
            listOf(a.externalIds[k], b.externalIds[k]).all { v ->
                !v.isNullOrBlank() && normalize(v) !in unknown && v != "0"
            }
        }
        if (ids.any { a.externalIds[it] != b.externalIds[it] }) return false
        if (ids.any { !a.externalIds[it].isNullOrBlank() && a.externalIds[it] == b.externalIds[it] }) return true
        val mainA = name(a.title, a.kind)
        val mainB = name(b.title, b.kind)
        // A shared franchise alias cannot erase a meaningful subtitle/sequel suffix.
        if (mainA != mainB && (mainA.startsWith(mainB + " ") || mainB.startsWith(mainA + " ")) &&
            !(a.aliases.any { name(it, a.kind) == mainB } && b.aliases.any { name(it, b.kind) == mainA })
        ) {
            return false
        }
        if (contradictory(a.author, b.author) || contradictory(a.artist, b.artist)) return false
        val verifiedArt = a.coverDigest?.matches(Regex("[0-9a-f]{64}")) == true && a.coverDigest == b.coverDigest &&
            declared(a.format) != null && declared(a.format) == declared(b.format)
        // A byte-verified identical cover only corroborates a long, distinctive title.
        // Neither a shared URL/filename nor cover resemblance is identity authority.
        if (possibleSameSourceTitles(a, b, t) && verifiedArt) return true
        val supported = listOf(a.author to b.author, a.artist to b.artist).any { (x, y) ->
            creator(x) != null &&
                creator(x) == creator(y)
        }
        val an = names(a)
        val bn = names(b)
        val exact = an.intersect(bn)
        if (exact.any {
                supported ||
                    (
                        it.length >= t.exactCharacters && it.split(' ').count { word ->
                            word !in generic
                        } >= t.exactTokens
                        )
            }
        ) {
            // A provider can catalogue genuinely different editions under one name.
            // Same-provider equality alone needs a creator or independently verified art.
            return a.source != b.source || mainA != mainB || supported ||
                verifiedArt
        }
        // Fuzzy titles require a corroborated creator AND an independent matching year/format.
        if (!supported ||
            !(
                (a.year != null && a.year == b.year) ||
                    (creator(a.format) != null && creator(a.format) == creator(b.format))
                )
        ) {
            return false
        }
        return an.any { x ->
            bn.any { y ->
                val xx = x.split(' ').toSet()
                val yy = y.split(' ').toSet()
                xx.size >= 3 && yy.size >= 3 &&
                    xx.intersect(yy).size.toDouble() / xx.union(yy).size >= t.fuzzySimilarity
            }
        }
    }
}

/** One session per accepted query, retained across pages. Complete-link clustering prevents alias bridges. */
internal class SearchWorkSelection(private val readableFirst: Boolean = false) {
    private data class Previous(val key: String, val ids: Set<String>, val winner: String)
    private var previous = emptyList<Previous>()
    private var current = emptyList<SearchWorkGroup>()

    @Synchronized fun snapshot(): List<SearchWorkGroup> = current

    @Synchronized fun restore(groups: List<SearchWorkGroup>) {
        current = groups
        previous = groups.map { Previous(it.key, it.members.map { c -> c.id }.toSet(), it.winner.id) }
    }

    @Synchronized fun select(
        input: List<SearchWorkCandidate>,
        established: Map<String, List<SearchWorkCandidate>>? = null,
    ): List<SearchWorkGroup> {
        val clusters = mutableListOf<MutableList<SearchWorkCandidate>>()
        val lookup = mutableMapOf<String, MutableSet<Int>>()
        val establishedKeys = mutableMapOf<String, String>()
        if (established != null) {
            established.forEach { (key, members) ->
                if (members.isNotEmpty()) {
                    clusters += members.distinctBy { it.id }.toMutableList()
                    members.forEach { establishedKeys[it.id] = key }
                }
            }
        }
        if (established == null) {
            input.distinctBy { it.id }.sortedWith(
                compareBy<SearchWorkCandidate>(
                    { it.kind.ordinal },
                    { if (it.kind == SearchWorkKind.COMIC) 0 else it.priority },
                    {
                        if (it.kind ==
                            SearchWorkKind.COMIC
                        ) {
                            SearchWorkIdentity.catalog(it).editionKey
                        } else {
                            it.source
                        }
                    },
                    { it.id },
                ),
            ).forEach { c ->
                val keys = if (c.kind == SearchWorkKind.COMIC) SearchWorkIdentity.verifiedLookupKeys(c) else emptySet()
                val possible = if (c.kind == SearchWorkKind.COMIC) {
                    keys.flatMap { lookup[it].orEmpty() }.distinct().sorted()
                } else {
                    clusters.indices.toList()
                }
                val found = possible.firstOrNull { index -> clusters[index].all { SearchWorkIdentity.matches(it, c) } }
                val index = found ?: clusters.size.also { clusters += mutableListOf<SearchWorkCandidate>() }
                clusters[index] += c
                keys.forEach { lookup.getOrPut(it) { linkedSetOf() }.add(index) }
            }
        }
        val comicKeys = clusters.filter { it.first().kind == SearchWorkKind.COMIC }
            .map { VerifiedCatalogIdentity.key(it.map(SearchWorkIdentity::catalog)) }.groupingBy { it }.eachCount()
        val oldOrder = previous.mapIndexed { i, p -> p.key to i }.toMap()
        val inputOrder = input.mapIndexed { i, c -> c.id to i }.toMap()
        val previousByMember = previous.flatMap { p -> p.ids.map { it to p } }.toMap()
        val claimed = mutableSetOf<String>()
        val result = clusters.map { members ->
            val old = members.mapNotNull { previousByMember[it.id] }.filter { it.key !in claimed }.minByOrNull {
                oldOrder[it.key]
                    ?: Int.MAX_VALUE
            }
            val readable = members.filter {
                it.chapters.readable != false && it.chapters.indexUsable != false &&
                    it.chapters.verifiedCount != 0
            }
            val eligible = readable.filterNot { it.chapters.indexUncertain }.ifEmpty { readable }.ifEmpty { members }
            fun quality(c: SearchWorkCandidate) = tachiyomi.domain.manga.service.CatalogEditionEvidence(
                indexedChapters = c.chapters.distinct,
                readable = c.chapters.readable,
                complete = c.chapters.complete,
                indexUsable = c.chapters.indexUsable,
                uncertain = c.chapters.indexUncertain,
            )
            val ranked = eligible.sortedWith { a, b ->
                val compared = tachiyomi.domain.manga.service.CatalogEditionSelection.compareQuality(
                    quality(b),
                    quality(a),
                )
                if (compared != 0) compared else compareValuesBy(a, b, { it.source }, { it.id })
            }
            val best = ranked.first()
            // Same evidence has the same default everywhere. Do not pin an early source
            // ahead of the shared stable source-qualified reference tie-breaker.
            val winner = best
            val comicBase = if (winner.kind ==
                SearchWorkKind.COMIC
            ) {
                VerifiedCatalogIdentity.key(members.map(SearchWorkIdentity::catalog))
            } else {
                null
            }
            val retainedKey = old?.key?.takeIf {
                readableFirst && winner.kind == SearchWorkKind.COMIC && members.any { itKey ->
                    itKey.let {
                        SearchWorkIdentity.verifiedLookupKeys(it) +
                            "edition:${SearchWorkIdentity.catalog(it).editionKey}"
                    }.contains(old.key)
                }
            }
            val base = if (winner.kind ==
                SearchWorkKind.COMIC
            ) {
                retainedKey ?: establishedKeys[members.first().id] ?: comicBase!!
            } else {
                establishedKeys[members.first().id] ?: old?.key ?: "search:${winner.kind}:${members.first().id}"
            }
            val key = if (winner.kind == SearchWorkKind.COMIC && comicKeys.getValue(comicBase!!) > 1) {
                base + ":separate:" + members.minOf { SearchWorkIdentity.catalog(it).editionKey }
            } else if (base !in claimed) {
                base
            } else {
                base + ":separate:" + members.first().id
            }
            claimed += key
            SearchWorkGroup(
                key,
                members,
                winner,
                readable.isEmpty() || members.any { it.chapters.indexUncertain } ||
                    eligible.any { it.chapters.verifiedCount == null },
            )
        }.sortedWith(
            // Preserve arrival stability within query quality, not above stronger matches.
            // A group's best matching alias survives selection of a different, fuller edition.
            compareBy<SearchWorkGroup> { g -> g.members.minOf { it.queryMatchRank } }
                .thenBy { g -> oldOrder[g.key] ?: Int.MAX_VALUE }
                .thenBy { g -> g.members.minOf { member -> inputOrder[member.id] ?: Int.MAX_VALUE } },
        )
        previous = result.map { Previous(it.key, it.members.map { c -> c.id }.toSet(), it.winner.id) }
        current = result
        return result
    }

    @Synchronized fun reset() {
        previous = emptyList()
        current = emptyList()
    }
}

/** Source-local identity only. Numbers and URL suffixes are deliberately absent. */
internal object DistinctSearchChapters {
    data class Identity(val url: String, val remoteIds: Set<String> = emptySet())
    fun count(rows: List<Identity>): Int = tachiyomi.domain.chapter.service.ChapterListIntegrity.uniqueIndexedCount(
        rows.map { row ->
            tachiyomi.domain.chapter.model.Chapter.create().copy(
                url = row.url,
                name = "Indexed chapter",
                memo = kotlinx.serialization.json.JsonObject(
                    row.remoteIds.mapIndexed { index, id ->
                        "$index.id" to kotlinx.serialization.json.JsonPrimitive(id)
                    }.toMap(),
                ),
            )
        },
        0L,
    )
}
