package eu.kanade.tachiyomi.search

import tachiyomi.domain.manga.service.VerifiedCatalogIdentity

internal enum class WorkIdentityVerdict { VERIFIED_SAME_WORK, LIKELY_SAME_WORK, DISTINCT_OR_INSUFFICIENT }
internal data class WorkIdentityDecision(
    val verdict: WorkIdentityVerdict,
    val reason: String,
    val provenance: Set<String> = emptySet(),
)

/** Search-only evidence, never stored as a canonical ID. No fuzzy translation or artwork matching. */
internal object WorkIdentityNormalizer {
    private val placeholders = setOf("unknown", "anonymous", "none", "null", "n a", "مجهول", "غير معروف", "غير محدد")
    private val qualifiers =
        Regex(
            "\\b(?:part|volume|vol|season)\\s+\\S+|\\b(?:\\d+|ii|iii|iv|v|vi|vii|viii|ix|x|xi|xii|wn|ln|sequel|prequel|remake|reboot|adaptation|season|part|volume|vol|side story|spin off)\\b|(?:الجزء|الموسم|المجلد|تكمله|اقتباس|جانبيه)\\s*\\S*",
        )
    private val generic =
        setOf(
            "the", "a", "an", "of", "and", "in", "novel", "manga", "manhwa", "رواية", "روايه",
            "مانهوا", "مانجا", "ملك", "الملك", "king", "dark",
        )
    fun title(value: String, kind: SearchWorkKind): String = SearchWorkIdentity.normalize(value).let {
        if (kind == SearchWorkKind.NOVEL) it.removePrefix("رواية ").removePrefix("روايه ") else it
    }
    fun creator(value: String?): String? = value?.let(SearchWorkIdentity::normalize)?.takeIf {
        it.length >= 3 &&
            it !in placeholders
    }
    fun artistCredits(raw: String?): Set<String> = raw.orEmpty().split(';').take(12).mapNotNull(::creator).toSet()
    fun compatibleArtists(a: String?, b: String?): Boolean {
        val x = artistCredits(a)
        val y = artistCredits(b)
        return x.isNotEmpty() && y.isNotEmpty() && (x.containsAll(y) || y.containsAll(x))
    }
    fun names(c: SearchWorkCandidate): Set<String> = (listOf(c.title) + c.aliases + listOfNotNull(c.originalTitle))
        .take(24).flatMap {
            it.split('|').take(16)
        }.map { title(it.take(512), c.kind) }.filter { it.isNotBlank() }.toSet()
    fun originals(
        c: SearchWorkCandidate,
    ) = c.originalTitle.orEmpty().split('|').map { title(it, c.kind) }.filter { it.isNotBlank() }.toSet()
    fun qualifiers(
        c: SearchWorkCandidate,
    ): Set<String> = qualifiers.findAll(title(c.title, c.kind)).map { it.value }.toSet()

    // Deliberately conservative gate, not a calibrated probability: two informative words AND a creator.
    fun distinctive(name: String) = name.split(' ').count { it !in generic && it.length >= 3 } >= 2

    // Restore the historical exact-title + named-creator case, without its title-only path.
    // Short aliases cannot bridge different main titles or generic/placeholder names.
    fun specificSingleWord(name: String) = name.length >= 3 && ' ' !in name && name !in generic && name !in placeholders
}

/** Indexed blocking narrows comparisons; dense ambiguous buckets still require complete-link checks. */
internal class WorkIdentityResolverV2 {
    internal data class Evidence(
        val row: SearchWorkCandidate,
        val names: Set<String>,
        val originals: Set<String>,
        val author: String?,
        val artist: String?,
        val qualifiers: Set<String>,
        val established: String?,
    ) {
        val identity = SearchWorkIdentity.catalog(row)
        val authoritative = AtlasCandidateIndex.authoritative(row)
    }
    data class Resolution(
        val groups: Map<String, List<SearchWorkCandidate>>,
        val decisions: Map<String, WorkIdentityDecision>,
    )
    internal fun evidence(
        row: SearchWorkCandidate,
        established: String?,
    ) = Evidence(
        row,
        WorkIdentityNormalizer.names(row),
        WorkIdentityNormalizer.originals(row),
        WorkIdentityNormalizer.creator(row.author),
        WorkIdentityNormalizer.creator(row.artist),
        WorkIdentityNormalizer.qualifiers(row),
        established,
    )
    fun decide(
        a: SearchWorkCandidate,
        b: SearchWorkCandidate,
    ): WorkIdentityDecision = decide(evidence(a, null), evidence(b, null))
    internal fun decide(a: Evidence, b: Evidence): WorkIdentityDecision {
        fun reject(reason: String) = WorkIdentityDecision(WorkIdentityVerdict.DISTINCT_OR_INSUFFICIENT, reason)
        val x = a.row
        val y = b.row
        if (SearchWorkIdentity.conflicts(x, y)) return reject("kind-format-edition-or-external-id-conflict")
        val ax = a.identity
        val by = b.identity
        val trusted = setOf("anilist", "myanimelist", "mangadex", "mangaupdates", "kitsu", "novelupdates")
        fun idsConflict(a: Map<String, String>, b: Map<String, String>) =
            a.keys.intersect(b.keys).intersect(trusted).any { key ->
                AtlasCandidateIndex.validId(a[key]) && AtlasCandidateIndex.validId(b[key]) &&
                    a[key]!!.trim() != b[key]!!.trim()
            }
        fun fieldConflict(a: String?, b: String?) = !a.isNullOrBlank() && !b.isNullOrBlank() &&
            SearchWorkIdentity.normalize(a) != SearchWorkIdentity.normalize(b)
        // Catalog and edition metadata must agree before a presentation-only relationship.
        if (idsConflict(ax.externalIds, by.externalIds) || idsConflict(x.externalIds, ax.externalIds) ||
            idsConflict(y.externalIds, by.externalIds) || idsConflict(x.externalIds, by.externalIds) ||
            idsConflict(y.externalIds, ax.externalIds) || fieldConflict(ax.format, by.format) ||
            fieldConflict(ax.edition, by.edition)
        ) {
            return reject("kind-format-edition-or-external-id-conflict")
        }
        if (ax.reviewedWorkKey != null && by.reviewedWorkKey != null && ax.reviewedWorkKey != by.reviewedWorkKey) {
            return reject("reviewed-work-conflict")
        }
        // Existing authority is stronger than spelling/transliteration of provider creator strings.
        if (x.kind == y.kind && VerifiedCatalogIdentity.matches(ax, by)) {
            return WorkIdentityDecision(
                WorkIdentityVerdict.VERIFIED_SAME_WORK,
                "existing-verified-identity",
                setOf(x.metadataOrigin, y.metadataOrigin),
            )
        }
        if (x.kind == SearchWorkKind.NOVEL &&
            x.externalIds["novelupdates"]?.takeIf(AtlasCandidateIndex::validId)?.let {
                it == y.externalIds["novelupdates"]
            } ==
            true
        ) {
            return WorkIdentityDecision(
                WorkIdentityVerdict.VERIFIED_SAME_WORK,
                "existing-novelupdates-identifier",
                setOf(x.metadataOrigin, y.metadataOrigin),
            )
        }
        val sharedAuthor = a.author != null && b.author != null &&
            CreatorNameEvidence.matches(x.author, x.format, y.author, y.format)
        if (a.author != null && b.author != null && !sharedAuthor) return reject("creator-conflict")
        if (a.qualifiers != b.qualifiers) return reject("sequel-volume-or-adaptation-conflict")
        if (a.originals.isNotEmpty() && b.originals.isNotEmpty() && a.originals.intersect(b.originals).isEmpty() &&
            a.originals.intersect(b.names).isEmpty() && b.originals.intersect(a.names).isEmpty()
        ) {
            return reject("original-title-conflict")
        }
        if (a.established != null && a.established == b.established) {
            return WorkIdentityDecision(
                WorkIdentityVerdict.VERIFIED_SAME_WORK,
                "existing-novel-catalog-relationship",
                setOf("native-catalog"),
            )
        }
        val mainA = WorkIdentityNormalizer.title(x.title, x.kind)
        val mainB = WorkIdentityNormalizer.title(y.title, y.kind)
        if (mainA != mainB && (mainA.startsWith("$mainB ") || mainB.startsWith("$mainA ")) &&
            !(mainA in b.names && mainB in a.names)
        ) {
            return reject("unconfirmed-franchise-subtitle")
        }
        if (x.source == y.source) return reject("different-provider-editions-without-alias-proof")
        if (x.year != null && y.year != null && x.year != y.year) return reject("publication-year-conflict")
        val sharedArtist = WorkIdentityNormalizer.compatibleArtists(x.artist, y.artist)
        if (a.artist != null && b.artist != null && !sharedArtist) return reject("artist-conflict")
        val creator = sharedAuthor || sharedArtist
        val shared = a.names.intersect(b.names)
        val original = a.originals.intersect(b.originals).filter(WorkIdentityNormalizer::distinctive)
        // Two source-declared names in different scripts corroborate one another. Repeating
        // the displayed title in an "original" field supplies no independent evidence.
        val bilingual = original.any { name ->
            shared.any { other ->
                WorkIdentityNormalizer.distinctive(other) &&
                    Regex("[\\u0600-\\u06ff]").containsMatchIn(name) !=
                    Regex("[\\u0600-\\u06ff]").containsMatchIn(other)
            }
        }
        if (bilingual) {
            return WorkIdentityDecision(
                WorkIdentityVerdict.LIKELY_SAME_WORK,
                "source-declared-original-and-independent-translated-name",
                setOf(x.metadataOrigin, y.metadataOrigin, "presentation-only-not-canonical"),
            )
        }
        if (!creator) return reject("missing-independent-creator-corroboration")
        val exactShortTitle = mainA == mainB && WorkIdentityNormalizer.specificSingleWord(mainA)
        if (shared.none(WorkIdentityNormalizer::distinctive) &&
            !exactShortTitle
        ) {
            return reject("missing-distinctive-shared-name")
        }
        if (exactShortTitle) {
            return WorkIdentityDecision(
                WorkIdentityVerdict.LIKELY_SAME_WORK,
                "restored-exact-short-title-and-creator",
                setOf(x.metadataOrigin, y.metadataOrigin, "presentation-only-not-canonical"),
            )
        }
        return WorkIdentityDecision(
            WorkIdentityVerdict.LIKELY_SAME_WORK,
            "exact-distinctive-name-and-creator",
            setOf(x.metadataOrigin, y.metadataOrigin, "presentation-only-not-canonical"),
        )
    }
    fun resolve(
        input: List<SearchWorkCandidate>,
        established: Map<String, List<SearchWorkCandidate>>? = null,
        previous: List<SearchWorkGroup> = emptyList(),
    ): Resolution = AtlasWorkIdentityResolver(this).resolve(input, established, previous)
}
