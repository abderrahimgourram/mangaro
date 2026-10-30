package eu.kanade.tachiyomi.novels

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLDecoder
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

/** Matching keys only; source titles, names, URLs and saved positions are never rewritten. */
object NovelIdentity {
    private val marks = Regex("\\p{M}+")
    private val alefs = Regex("[أإآٱ]")
    private val punctuation = Regex("[^\\p{L}\\p{N}]+")
    private val spaces = Regex("\\s+")
    private val editionQualifier =
        Regex(
            "(?:\\b[0-9]+\\b|\\b(?:wn|ln|sequel|adaptation|side story|fan fiction|fanfic)\\b|(?:الجزء|المجلد)\\s+\\S+|\\b(?:part|volume|vol)\\s+\\S+|نسخه الفان|قصه جانبيه|قصص جانبيه|تكمله|اقتباس|الارك الاخير)",
        )
    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(marks, "").replace("ـ", "")
        .replace(alefs, "ا").replace('ى', 'ي').replace('ة', 'ه')
        .lowercase(Locale.ROOT).replace(punctuation, " ").trim()
        .replace(spaces, " ")

    fun titleKey(value: String) = normalize(value).removePrefix("روايه ")

    // Explicit transliteration corroborated by the two provider records documented in docs/novels.
    // No fuzzy author matching and no inference from a novel title.
    private fun authorKey(value: String): String = when (val key = normalize(value)) {
        "gu zhen ren", "guzhenren", "غو زين رن" -> "gu zhen ren"
        else -> key
    }
    private fun creatorKey(raw: String?) = raw?.let(::authorKey)?.takeIf {
        it.isNotBlank() && it !in setOf("unknown", "anonymous", "none", "null", "n a", "غير معروف", "مجهول", "غير محدد")
    }
    private fun identifiers(novel: Novel): Map<String, String> = novel.workIdentifiers.mapNotNull { value ->
        val fields = value.split(':', limit = 2)
        fields.takeIf {
            it.size == 2 &&
                it[0] in setOf("anilist", "myanimelist", "mangadex", "mangaupdates", "kitsu", "novelupdates") &&
                eu.kanade.tachiyomi.search.AtlasCandidateIndex.validId(it[1])
        }?.let { it[0] to it[1].trim() }
    }.toMap()
    internal fun searchAuthorKey(value: String): String = authorKey(value)

    /** Legacy Cenele parsed the nationality kicker as an original title. This is field
     * provenance cleanup only, never a title matcher or a rewrite of source identities. */
    internal fun originalTitle(novel: Novel): String? = novel.originalTitle?.takeUnless {
        novel.sourceId == "novel.cenele" && normalize(it).removePrefix("روايه ") in
            setOf("كوريه", "كوري", "صينيه", "صيني", "يابانيه", "ياباني", "عربيه", "عربي")
    }
    private fun originalNames(
        novel: Novel,
    ) = originalTitle(novel).orEmpty().split('|').map(::titleKey).filter { it.isNotBlank() }.toSet()
    private fun names(novel: Novel) = (
        listOf(novel.title) + novel.alternativeTitles +
            listOfNotNull(originalTitle(novel)) +
            originalTitle(novel).orEmpty().split('|')
        )
        .map(::titleKey).filter { it.length >= 6 }.toSet()
    private fun qualifiers(novel: Novel): Set<String> = names(novel).flatMap { name ->
        editionQualifier.findAll(name).map { it.value }.toList()
    }.toSet()

    // Necessary matching keys only; the existing corroboration/conflict rules still decide.
    internal fun matchingKeys(novel: Novel) =
        names(novel).map { "title:$it" }.toSet() + novel.workIdentifiers.map { "work:$it" }

    fun conflicts(a: Novel, b: Novel): Boolean {
        if (a.id == b.id) return false
        if ((NovelContentTypeClassifier.classify(a).type == NovelContentType.COMIC) !=
            (NovelContentTypeClassifier.classify(b).type == NovelContentType.COMIC)
        ) {
            return true
        }
        val idsA = identifiers(a)
        val idsB = identifiers(b)
        if (idsA.keys.intersect(idsB.keys).any { idsA[it] != idsB[it] }) return true
        if (a.sourceId == b.sourceId && idsA.none { (key, value) -> idsB[key] == value }) return true
        val authorA = creatorKey(a.author)
        val authorB = creatorKey(b.author)
        return (authorA != null && authorB != null && authorA != authorB) || qualifiers(a) != qualifiers(b)
    }
    fun matches(a: Novel, b: Novel): Boolean {
        if (a.id == b.id) return true
        if (conflicts(a, b)) return false
        val authorA = creatorKey(a.author)
        val authorB = creatorKey(b.author)
        if (identifiers(a).any { (key, value) -> identifiers(b)[key] == value }) return true
        val common = names(a).intersect(names(b))
        if (common.isEmpty()) return false
        if (authorA != null && authorA == authorB) return true
        if (originalNames(a).intersect(originalNames(b)).any { it.length >= 10 && ' ' in it }) return true
        // Missing authors require a distinctive exact title AND corroborating synopsis, never fuzzy title alone.
        val distinctive = common.any { it.length >= 14 && it.split(' ').size >= 3 }
        if (!distinctive || a.description.isBlank() || b.description.isBlank()) return false
        val tokensA = normalize(a.description).split(' ').filter { it.length > 2 }.toSet()
        val tokensB = normalize(b.description).split(' ').filter { it.length > 2 }.toSet()
        val union = tokensA.union(tokensB)
        return union.size >= 15 && tokensA.intersect(tokensB).size.toDouble() / union.size >= .65
    }

    fun initialWorkId(editionId: String): String = "novel.work." + UUID.nameUUIDFromBytes(editionId.toByteArray())

    fun belongsToNovel(novel: Novel, chapterUrl: String): Boolean {
        if (!NovelHttp.allowed(chapterUrl)) return false
        val novelUri = novel.url.toHttpUrlOrNull() ?: return false
        val chapterUri = chapterUrl.toHttpUrlOrNull() ?: return false
        val novelHost = novelUri.host.removePrefix("www.")
        val chapterHost = chapterUri.host.removePrefix("www.")
        if (novelHost != chapterHost) return false

        return when (novel.sourceId) {
            "novel.mknov" -> {
                val novelWorkId = novelUri.pathSegments.dropWhile { it != "work" && it != "novel" }.getOrNull(1)?.trim()
                val chapterWorkId = chapterUri.pathSegments.dropWhile { it != "work" && it != "novel" }.getOrNull(1)?.trim()
                if (novelWorkId != null && chapterWorkId != null) {
                    novelWorkId == chapterWorkId
                } else {
                    true
                }
            }
            "novel.sunovels" -> {
                val novelSlug = novelUri.pathSegments.dropWhile { it != "novel" }.getOrNull(1)?.trim()
                val chapterSlug = chapterUri.pathSegments.dropWhile { it != "novel" }.getOrNull(1)?.trim()
                if (novelSlug != null && chapterSlug != null) {
                    novelSlug.equals(chapterSlug, ignoreCase = true)
                } else {
                    true
                }
            }
            "novel.cenele" -> {
                val novelSlug = novelUri.pathSegments.dropWhile { it != "cont" }.getOrNull(1)?.trim()
                val chapterSlug = chapterUri.pathSegments.dropWhile { it != "cont" }.getOrNull(1)?.trim()
                if (novelSlug != null && chapterSlug != null) {
                    novelSlug.equals(chapterSlug, ignoreCase = true)
                } else {
                    true
                }
            }
            "novel.seanovel" -> {
                val novelSlug = novelUri.pathSegments.dropWhile { it != "novels" }.getOrNull(1)?.trim()
                val chapterSlug = chapterUri.pathSegments.dropWhile { it != "novels" }.getOrNull(1)?.trim()
                if (novelSlug != null && chapterSlug != null) {
                    novelSlug.equals(chapterSlug, ignoreCase = true)
                } else {
                    true
                }
            }
            "novel.kolnovel" -> {
                val novelSlug = novelUri.pathSegments.dropWhile { it.isBlank() || it == "series" }.firstOrNull()?.trim()
                    ?: return true
                val decodedNovelSlug = try {
                    URLDecoder.decode(novelSlug, "UTF-8").trim()
                } catch (_: Exception) { novelSlug }

                val chapterSeriesSlug = chapterUri.pathSegments.dropWhile { it != "series" }.getOrNull(1)?.trim()
                if (chapterSeriesSlug != null) {
                    val decodedChapterSeriesSlug = try {
                        URLDecoder.decode(chapterSeriesSlug, "UTF-8").trim()
                    } catch (_: Exception) { chapterSeriesSlug }
                    return chapterSeriesSlug.equals(novelSlug, ignoreCase = true) ||
                        decodedChapterSeriesSlug.equals(decodedNovelSlug, ignoreCase = true)
                }

                val chapterPath = chapterUri.encodedPath
                val decodedChapterPath = try {
                    URLDecoder.decode(chapterPath, "UTF-8")
                } catch (_: Exception) { chapterPath }

                if (chapterPath.contains(novelSlug, ignoreCase = true) ||
                    decodedChapterPath.contains(decodedNovelSlug, ignoreCase = true)
                ) {
                    return true
                }

                // Explicit alien series filter to reject foreign novel chapters (e.g. the-legeandeary-mechaenic-kol)
                val alienSlugs = setOf(
                    "the-legeandeary-mechaenic-kol",
                    "the-legendary-mechanic",
                )
                if (alienSlugs.any { chapterPath.contains(it, ignoreCase = true) || decodedChapterPath.contains(it, ignoreCase = true) }) {
                    if (!novelSlug.contains("mechaenic", ignoreCase = true) && !novelSlug.contains("mechanic", ignoreCase = true)) {
                        return false
                    }
                }
                true
            }
            else -> {
                true
            }
        }
    }

    fun compositeChapterKey(sourceId: String, novelUrl: String, chapter: NovelChapter): String {
        val novelUri = novelUrl.toHttpUrlOrNull()
        val novelPath = novelUri?.encodedPath?.trim('/') ?: novelUrl
        val chapterUri = chapter.url.toHttpUrlOrNull()
        val chapterKey = chapterUri?.encodedPath?.trim('/') ?: chapter.url
        return "$sourceId|$novelPath|$chapterKey"
    }

    fun preferred(
        editions: List<Novel>,
        evidence: Map<String, NovelEditionEvidence>,
        indexes: Map<String, NovelChapterIndex> = emptyMap(),
    ): Novel = NovelWorkReconciler.preferred(editions, evidence, indexes)
}

@Serializable
data class NovelEditionEvidence(
    val complete: Boolean = false,
    val availableCount: Int = 0,
    val accessWorks: Boolean? = null,
    val checkedAt: Long = 0,
    val accessCheckedAt: Long = checkedAt,
)

@Serializable
data class UnifiedNovelWork(val id: String, val editions: List<Novel>, val primaryEditionId: String) {
    val primary: Novel get() = editions.firstOrNull { it.id == primaryEditionId } ?: editions.first()
}

@Serializable
data class NovelCatalogState(
    val works: List<UnifiedNovelWork> = emptyList(),
    val aliases: Map<String, String> = emptyMap(),
    val evidence: Map<String, NovelEditionEvidence> = emptyMap(),
    val migrationVersion: Int = 0,
) {
    fun resolve(id: String): String {
        var next = id
        val visited = mutableSetOf<String>()
        while (next in aliases && visited.add(next)) next = aliases.getValue(next)
        return next
    }
    fun work(editionOrWork: String) = works.firstOrNull {
        it.id == resolve(editionOrWork) ||
            it.editions.any { n -> n.id == editionOrWork }
    }
}

/** Pure reconciliation used both during legacy migration and incremental live discovery. */
object NovelWorkReconciler {
    fun ingest(state: NovelCatalogState, novels: List<Novel>): NovelCatalogState {
        var works = state.works
        val aliases = state.aliases.toMutableMap()
        val keys = HashMap<String, Set<String>>()
        fun keysFor(novel: Novel) = keys.getOrPut(novel.id) { NovelIdentity.matchingKeys(novel) }
        for (candidate in novels) {
            val incoming = SunovelsSource.cleanMetadata(candidate)
            val previous = works.firstOrNull { w -> w.editions.any { it.id == incoming.id } }
            val old = previous?.editions?.firstOrNull { it.id == incoming.id }
            // A small catalogue card must never erase details learned from the actual work page.
            val novel = if (old == null) {
                val clean = cleanNovelCover(incoming.cover)
                if (clean != incoming.cover) incoming.copy(cover = clean) else incoming
            } else {
                incoming.copy(
                    author = incoming.author?.takeIf { it.isNotBlank() } ?: old.author,
                    description = incoming.description.ifBlank { old.description },
                    cover = cleanNovelCover(incoming.cover) ?: cleanNovelCover(old.cover),
                    status = incoming.status ?: old.status,
                    originalTitle =
                    incoming.originalTitle
                        ?: if (incoming.sourceId ==
                            SunovelsSource.SOURCE_ID
                        ) {
                            SunovelsSource.cleanOriginalTitle(old.originalTitle)
                        } else {
                            old.originalTitle
                        },
                    alternativeTitles = (old.alternativeTitles + incoming.alternativeTitles).distinct(),
                    workIdentifiers = (old.workIdentifiers + incoming.workIdentifiers).distinct(),
                    genres = incoming.genres.ifEmpty { old.genres },
                    reliableUpdatedAt = incoming.reliableUpdatedAt ?: old.reliableUpdatedAt,
                    chapterCount = incoming.chapterCount ?: old.chapterCount,
                    contentFormat = NovelContentTypeClassifier.merge(incoming.contentFormat, old.contentFormat),
                )
            }
            if (novel == old) continue
            val incomingKeys = NovelIdentity.matchingKeys(novel)
            keys[novel.id] = incomingKeys
            val remaining = previous?.editions.orEmpty().filterNot { it.id == novel.id }
            works = works.filterNot { it.id == previous?.id }
            if (remaining.isNotEmpty()) {
                works = works + previous!!.copy(
                    editions = remaining,
                    primaryEditionId = preferred(remaining, state.evidence).id,
                )
            }
            // A positive corroborating link is required and every member must be compatible.
            // A bridge edition cannot merge conflicting authors
            // or two distinct records from one provider. New evidence can safely separate an old group.
            val candidates = works.filter { w ->
                w.editions.any {
                    keysFor(it).any { key -> key in incomingKeys } &&
                        NovelIdentity.matches(it, novel)
                } &&
                    w.editions.none { NovelIdentity.conflicts(it, novel) }
            }
            val compatible = candidates.flatMap { it.editions }
            val mutuallyCompatible = compatible.all { a -> compatible.all { b -> !NovelIdentity.conflicts(a, b) } }
            val matching = if (mutuallyCompatible) candidates else emptyList()
            var id = matching.minByOrNull { it.id }?.id
                ?: previous?.id?.takeIf { remaining.isEmpty() }
                ?: NovelIdentity.initialWorkId(novel.id)
            if (works.any { it.id == id && it !in matching }) id = NovelIdentity.initialWorkId(novel.id + "|separate")
            val editions = (matching.flatMap { it.editions } + novel).sortedBy { it.id }
            matching.filter { it.id != id }.forEach { aliases[it.id] = id }
            // An empty previous group was moved; preserve old canonical deep links.
            if (previous != null && remaining.isEmpty() && previous.id != id) aliases[previous.id] = id
            aliases.remove(id)
            works =
                works.filterNot { it in matching } +
                UnifiedNovelWork(id, editions, preferred(editions, state.evidence).id)
        }
        return state.copy(works = works.sortedBy { it.id }, aliases = aliases)
    }

    fun preferred(
        editions: List<Novel>,
        evidence: Map<String, NovelEditionEvidence>,
        indexes: Map<String, NovelChapterIndex> = emptyMap(),
    ): Novel = editions.sortedWith { a, b ->
        val countA = maxOf(indexes[a.id]?.chapters?.size ?: 0, evidence[a.id]?.availableCount ?: 0, a.chapterCount ?: 0)
        val countB = maxOf(indexes[b.id]?.chapters?.size ?: 0, evidence[b.id]?.availableCount ?: 0, b.chapterCount ?: 0)
        val healthA = evidence[a.id]?.accessWorks != false
        val healthB = evidence[b.id]?.accessWorks != false
        if (healthA && healthB && countA > 0 && countB > 0 && countA != countB) {
            countB.compareTo(countA)
        } else {
            fun quality(n: Novel): tachiyomi.domain.manga.service.CatalogEditionEvidence {
                val value = evidence[n.id]
                return tachiyomi.domain.manga.service.CatalogEditionEvidence(
                    indexedChapters = maxOf(indexes[n.id]?.chapters?.size ?: 0, value?.availableCount ?: 0, n.chapterCount ?: 0).takeIf { it > 0 },
                    complete = value?.complete == true || indexes[n.id]?.complete == true,
                    readable = value?.accessWorks,
                )
            }
            val compared = tachiyomi.domain.manga.service.CatalogEditionSelection.compareQuality(quality(b), quality(a))
            if (compared != 0) compared else a.id.compareTo(b.id)
        }
    }.first()

    fun withEvidence(state: NovelCatalogState, editionId: String, value: NovelEditionEvidence): NovelCatalogState {
        val old = state.evidence[editionId]
        val counts = if (old != null && (
                value.checkedAt < old.checkedAt ||
                    (!value.complete && old.complete && value.availableCount <= old.availableCount)
                )
        ) {
            old
        } else {
            value
        }
        val access = if (value.accessWorks != null &&
            (old == null || value.accessCheckedAt >= old.accessCheckedAt)
        ) {
            value
        } else {
            old
        }
        val retained = counts.copy(accessWorks = access?.accessWorks, accessCheckedAt = access?.accessCheckedAt ?: 0)
        val evidence = state.evidence + (editionId to retained)
        return state.copy(
            evidence = evidence,
            works = state.works.map {
                it.copy(primaryEditionId = preferred(it.editions, evidence).id)
            },
        )
    }
    private fun metadataQuality(
        n: Novel,
    ) = listOf(
        n.author,
        n.cover,
        n.description.takeIf {
            it.isNotBlank()
        },
        n.originalTitle,
        n.status,
    ).count { !it.isNullOrBlank() }
}

data class UnifiedNovelLibraryItem(val work: UnifiedNovelWork, val entries: List<NovelLibraryItem>) {
    val latest: NovelLibraryItem? get() = entries.filter { it.position != null }.maxByOrNull { it.position!!.updatedAt }
}

object NovelGenres {
    private val aliases = mapOf(
        "action" to "أكشن", "اكشن" to "أكشن", "حركه" to "أكشن", "fantasy" to "خيال",
        "فانتازيا" to "خيال",
        "romance" to "رومانسي", "رومانسيه" to "رومانسي", "comedy" to "كوميدي", "كوميديا" to "كوميدي",
        "martial arts" to "فنون قتالية", "historical" to "تاريخي", "history" to "تاريخي", "تاريخ" to "تاريخي",
        "adventure" to "مغامرة", "مغامرات" to "مغامرة", "mystery" to "غموض", "science fiction" to "خيال علمي",
        "drama" to "دراما", "horror" to "رعب", "supernatural" to "قوى خارقة", "reincarnation" to "تناسخ",
        "xianxia" to "خيال الخلود", "xuanhuan" to "فانتازيا شرقية",
    )
    fun key(value: String): String {
        val normalized = NovelIdentity.normalize(value)
        return NovelIdentity.normalize(aliases[normalized] ?: value)
    }
    fun label(value: String) = aliases[NovelIdentity.normalize(value)] ?: value
}
