package eu.kanade.tachiyomi.novels

import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

/** Matching keys only; source titles, names, URLs and saved positions are never rewritten. */
object NovelIdentity {
    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "").replace("ـ", "")
        .replace(Regex("[أإآٱ]"), "ا").replace('ى', 'ي').replace('ة', 'ه')
        .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        .replace(Regex("\\s+"), " ")

    fun titleKey(value: String) = normalize(value).removePrefix("روايه ")
    // Explicit transliteration corroborated by the two provider records documented in docs/novels.
    // No fuzzy author matching and no inference from a novel title.
    private fun authorKey(value: String): String = when (val key = normalize(value)) {
        "gu zhen ren", "guzhenren", "غو زين رن" -> "gu zhen ren"
        else -> key
    }
    private fun originalNames(novel: Novel) = novel.originalTitle.orEmpty().split('|').map(::titleKey).filter {it.isNotBlank()}.toSet()
    private fun names(novel: Novel) = (listOf(novel.title) + novel.alternativeTitles + listOfNotNull(novel.originalTitle) + novel.originalTitle.orEmpty().split('|'))
        .map(::titleKey).filter { it.length >= 6 }.toSet()
    private fun qualifiers(novel: Novel): Set<String> = names(novel).flatMap { name ->
        Regex("(?:\\b[0-9]+\\b|\\b(?:wn|ln|sequel|adaptation|side story|fan fiction|fanfic)\\b|(?:الجزء|المجلد)\\s+\\S+|\\b(?:part|volume|vol)\\s+\\S+|نسخه الفان|قصه جانبيه|قصص جانبيه|تكمله|اقتباس|الارك الاخير)")
            .findAll(name).map { it.value }.toList()
    }.toSet()

    fun conflicts(a: Novel, b: Novel): Boolean {
        if (a.id == b.id) return false
        if (a.sourceId == b.sourceId) return true
        val authorA = a.author?.let(::authorKey)?.takeIf { it.isNotBlank() }
        val authorB = b.author?.let(::authorKey)?.takeIf { it.isNotBlank() }
        return (authorA != null && authorB != null && authorA != authorB) || qualifiers(a) != qualifiers(b)
    }
    fun matches(a: Novel, b: Novel): Boolean {
        if (a.id == b.id) return true
        if (conflicts(a,b)) return false
        val authorA = a.author?.let(::authorKey)?.takeIf { it.isNotBlank() }
        val authorB = b.author?.let(::authorKey)?.takeIf { it.isNotBlank() }
        if (a.workIdentifiers.intersect(b.workIdentifiers.toSet()).isNotEmpty()) return true
        val common = names(a).intersect(names(b))
        if (common.isEmpty()) return false
        if (authorA != null && authorA == authorB) return true
        if (originalNames(a).intersect(originalNames(b)).any {it.length >= 10 && ' ' in it}) return true
        // Missing authors require a distinctive exact title AND corroborating synopsis, never fuzzy title alone.
        val distinctive = common.any { it.length >= 14 && it.split(' ').size >= 3 }
        if (!distinctive || a.description.isBlank() || b.description.isBlank()) return false
        val tokensA = normalize(a.description).split(' ').filter { it.length > 2 }.toSet()
        val tokensB = normalize(b.description).split(' ').filter { it.length > 2 }.toSet()
        val union = tokensA.union(tokensB)
        return union.size >= 15 && tokensA.intersect(tokensB).size.toDouble() / union.size >= .65
    }

    fun initialWorkId(editionId: String): String = "novel.work." + UUID.nameUUIDFromBytes(editionId.toByteArray())
}

@Serializable
data class NovelEditionEvidence(val complete: Boolean = false, val availableCount: Int = 0,
    val accessWorks: Boolean? = null, val checkedAt: Long = 0)

@Serializable
data class UnifiedNovelWork(val id: String, val editions: List<Novel>, val primaryEditionId: String) {
    val primary: Novel get() = editions.firstOrNull { it.id == primaryEditionId } ?: editions.first()
}

@Serializable
data class NovelCatalogState(val works: List<UnifiedNovelWork> = emptyList(),
    val aliases: Map<String, String> = emptyMap(), val evidence: Map<String, NovelEditionEvidence> = emptyMap(),
    val migrationVersion: Int = 0) {
    fun resolve(id: String): String {
        var next = id
        val visited = mutableSetOf<String>()
        while (next in aliases && visited.add(next)) next = aliases.getValue(next)
        return next
    }
    fun work(editionOrWork: String) = works.firstOrNull { it.id == resolve(editionOrWork) || it.editions.any { n -> n.id == editionOrWork } }
}

/** Pure reconciliation used both during legacy migration and incremental live discovery. */
object NovelWorkReconciler {
    fun ingest(state: NovelCatalogState, novels: List<Novel>): NovelCatalogState {
        var works = state.works
        val aliases = state.aliases.toMutableMap()
        for (incoming in novels) {
            val previous = works.firstOrNull { w -> w.editions.any { it.id == incoming.id } }
            val old = previous?.editions?.firstOrNull { it.id == incoming.id }
            // A small catalogue card must never erase details learned from the actual work page.
            val novel = if (old == null) incoming else incoming.copy(
                author = incoming.author?.takeIf { it.isNotBlank() } ?: old.author,
                description = incoming.description.ifBlank { old.description },
                cover = incoming.cover ?: old.cover, status = incoming.status ?: old.status,
                originalTitle = incoming.originalTitle ?: old.originalTitle,
                alternativeTitles = (old.alternativeTitles + incoming.alternativeTitles).distinct(),
                workIdentifiers = (old.workIdentifiers + incoming.workIdentifiers).distinct(),
                genres = incoming.genres.ifEmpty { old.genres },
                reliableUpdatedAt = incoming.reliableUpdatedAt ?: old.reliableUpdatedAt,
            )
            val remaining = previous?.editions.orEmpty().filterNot { it.id == novel.id }
            works = works.filterNot { it.id == previous?.id }
            if (remaining.isNotEmpty()) works = works + previous!!.copy(editions = remaining,
                primaryEditionId = preferred(remaining, state.evidence).id)
            // A positive corroborating link is required and every member must be compatible.
            // A bridge edition cannot merge conflicting authors
            // or two distinct records from one provider. New evidence can safely separate an old group.
            val candidates = works.filter { w -> w.editions.any { NovelIdentity.matches(it, novel) } && w.editions.none { NovelIdentity.conflicts(it, novel) } }
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
            works = works.filterNot { it in matching } + UnifiedNovelWork(id, editions, preferred(editions, state.evidence).id)
        }
        return state.copy(works = works.sortedBy { it.id }, aliases = aliases)
    }

    fun preferred(editions: List<Novel>, evidence: Map<String, NovelEditionEvidence>): Novel = editions.filter { evidence[it.id]?.accessWorks != false }.ifEmpty { editions }.sortedWith(
        compareByDescending<Novel> {
            evidence[it.id]?.takeIf { e -> e.complete && e.accessWorks != false }?.availableCount ?: -1
        }.thenByDescending { evidence[it.id]?.complete == true }
            .thenByDescending { evidence[it.id]?.accessWorks == true }
            .thenByDescending { metadataQuality(it) }
            .thenByDescending { it.reliableUpdatedAt ?: 0 }.thenBy { it.id },
    ).first()

    fun withEvidence(state: NovelCatalogState, editionId: String, value: NovelEditionEvidence): NovelCatalogState {
        val evidence = state.evidence + (editionId to value)
        return state.copy(evidence = evidence, works = state.works.map { it.copy(primaryEditionId = preferred(it.editions, evidence).id) })
    }
    private fun metadataQuality(n: Novel) = listOf(n.author, n.cover, n.description.takeIf { it.isNotBlank() }, n.originalTitle, n.status).count { !it.isNullOrBlank() }
}

data class UnifiedNovelLibraryItem(val work: UnifiedNovelWork, val entries: List<NovelLibraryItem>) {
    val latest: NovelLibraryItem? get() = entries.filter { it.position != null }.maxByOrNull { it.position!!.updatedAt }
}

object NovelGenres {
    private val aliases = mapOf("action" to "أكشن", "اكشن" to "أكشن", "حركه" to "أكشن", "fantasy" to "خيال", "فانتازيا" to "خيال",
        "romance" to "رومانسي", "رومانسيه" to "رومانسي", "comedy" to "كوميدي", "كوميديا" to "كوميدي",
        "martial arts" to "فنون قتالية", "historical" to "تاريخي", "history" to "تاريخي", "تاريخ" to "تاريخي",
        "adventure" to "مغامرة", "مغامرات" to "مغامرة", "mystery" to "غموض", "science fiction" to "خيال علمي",
        "drama" to "دراما", "horror" to "رعب", "supernatural" to "قوى خارقة", "reincarnation" to "تناسخ", "xianxia" to "خيال الخلود", "xuanhuan" to "فانتازيا شرقية")
    fun key(value: String): String {
        val normalized = NovelIdentity.normalize(value)
        return NovelIdentity.normalize(aliases[normalized] ?: value)
    }
    fun label(value: String) = aliases[NovelIdentity.normalize(value)] ?: value
}
