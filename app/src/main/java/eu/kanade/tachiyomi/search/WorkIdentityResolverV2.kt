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

    // A source-declared unmarked alias can explain a narrow translation annotation.
    // It supplies no new identity authority and cannot strip arbitrary subtitles/sequels.
    fun comparisonTitle(c: SearchWorkCandidate): String {
        val name = c.title.trim()
        val marker = Regex("(?i)\\s*\\((?:مترجم|مترجمة|translated|translation)\\)\\s*$")
        val base = name.replace(marker, "").trim()
        return if (base != name && c.aliases.any { title(it, c.kind) == title(base, c.kind) }) {
            title(base, c.kind)
        } else {
            title(name, c.kind)
        }
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

    /** Weak overlap only. Commas in a person's name never supply creator identity.
     * A long comma enumeration can corroborate independently proven names/writer,
     * but must NOT enter the strict artist-subset or standalone creator comparison.
     */
    fun overlappingArtEnumeration(a: String?, b: String?): Boolean {
        fun hints(raw: String?): Set<String> = raw.orEmpty().take(1024).split(';').take(12).flatMap { part ->
            val enumeration = part.split(',').map(String::trim)
            if (enumeration.size >= 3) enumeration.take(12) else listOf(part)
        }.take(24).mapNotNull(::creator).filter {
            it.length >= 4 && it.any(Char::isLetter) && it !in setOf("studio", "artist", "team", "illustrator")
        }.toSet()
        return hints(a).intersect(hints(b)).isNotEmpty()
    }

    /** Only a complete label can corroborate an exact name/writer pair without
     * bilingual names. Never turn a surname/given-name comma fragment into proof.
     * Structured semicolon credits retain their existing strict subset semantics.
     */
    fun completeArtLabelFromEnumeration(a: String?, b: String?): Boolean {
        val roles = setOf("art", "artist", "studio", "team", "illustrator", "unknown", "author", "writer")
        fun complete(value: String): Boolean {
            val name = creator(value) ?: return false
            return (name.any(Char::isLetter) && name.any(Char::isDigit)) ||
                name.split(' ').count { it.length >= 2 && it !in roles } >= 2
        }
        fun matches(enumeration: String?, individual: String?): Boolean {
            val credits = enumeration.orEmpty().take(1024).split(';').take(12).flatMap { field ->
                val parts = field.split(',').mapNotNull(::creator)
                if (parts.size >= 3 && parts.none { it in setOf("jr", "sr", "junior", "senior", "ii", "iii", "iv") }) {
                    parts.take(12)
                } else {
                    emptyList()
                }
            }.toSet()
            return individual.orEmpty().take(1024).split(';').take(12).filter { ',' !in it && complete(it) }
                .mapNotNull(::creator).any { it in credits }
        }
        return matches(a, b) || matches(b, a)
    }

    /** Exact source-declared full names in different scripts, not acronyms or fuzzy translations. */
    fun independentlyTranslatedNames(shared: Set<String>): Boolean {
        val scripts = shared.filter(::distinctive).map { name ->
            name.filter(Char::isLetter).map { Character.UnicodeScript.of(it.code) }.toSet()
        }.filter(Set<Character.UnicodeScript>::isNotEmpty).distinct()
        return scripts.any { a -> scripts.any { b -> a.intersect(b).isEmpty() } }
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
            idsConflict(y.externalIds, ax.externalIds) ||
            tachiyomi.domain.manga.service.CatalogFormatCompatibility.conflicts(ax.format, by.format) ||
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
        val mainA = WorkIdentityNormalizer.comparisonTitle(x)
        val mainB = WorkIdentityNormalizer.comparisonTitle(y)
        if (mainA != mainB && (mainA.startsWith("$mainB ") || mainB.startsWith("$mainA ")) &&
            !(mainA in b.names && mainB in a.names)
        ) {
            return reject("unconfirmed-franchise-subtitle")
        }
        if (x.source == y.source) return reject("different-provider-editions-without-alias-proof")
        if (x.year != null && y.year != null && x.year != y.year) return reject("publication-year-conflict")
        val sharedArtist = WorkIdentityNormalizer.compatibleArtists(x.artist, y.artist)
        val shared = a.names.intersect(b.names)
        // Provider artist lists are not exhaustive publication contracts. Different
        // extra credits cannot veto a shared writer + independent bilingual names
        // + informative art credit. Disjoint credits and every earlier veto remain.
        val completeArtLabel = mainA == mainB && WorkIdentityNormalizer.distinctive(mainA) &&
            WorkIdentityNormalizer.completeArtLabelFromEnumeration(x.artist, y.artist)
        val bilingualArt = WorkIdentityNormalizer.independentlyTranslatedNames(shared) &&
            WorkIdentityNormalizer.overlappingArtEnumeration(x.artist, y.artist)
        val corroboratedArtHistory = x.kind == SearchWorkKind.COMIC && !sharedArtist && sharedAuthor &&
            (completeArtLabel || bilingualArt)
        if (a.artist != null && b.artist != null && !sharedArtist && !corroboratedArtHistory) {
            return reject("artist-conflict")
        }
        val creator = sharedAuthor || sharedArtist
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
            if (corroboratedArtHistory && completeArtLabel) {
                "shared-writer-full-title-and-complete-art-credit"
            } else if (corroboratedArtHistory) {
                "shared-writer-bilingual-names-and-nonexclusive-art-credits"
            } else {
                "exact-distinctive-name-and-creator"
            },
            setOf(x.metadataOrigin, y.metadataOrigin, "presentation-only-not-canonical"),
        )
    }

    /** Reversible card clustering only. Never authoritative for library/chapters/relationships. */
    internal fun presentationDecision(a: Evidence, b: Evidence): WorkIdentityDecision {
        val strict = decide(a, b)
        val decision = if (strict.reason == "kind-format-edition-or-external-id-conflict") {
            val x = SearchWorkIdentity.presentationComparable(a.row)
            val y = SearchWorkIdentity.presentationComparable(b.row)
            // Re-run ALL vetoes using compatible reader media. Never waive an ID, creator,
            // original-title, edition or group-wide conflict just because formats are compatible.
            val compatible = if (x === a.row && y === b.row) strict else decide(a.copy(row = x), b.copy(row = y))
            if (compatible.verdict == WorkIdentityVerdict.VERIFIED_SAME_WORK) {
                compatible.copy(
                    verdict = WorkIdentityVerdict.LIKELY_SAME_WORK,
                    reason = "presentation-compatible-comic-formats-and-identity-evidence",
                    provenance = setOf("reversible-ui-only-not-canonical"),
                )
            } else {
                compatible
            }
        } else {
            strict
        }
        if (decision.verdict != WorkIdentityVerdict.DISTINCT_OR_INSUFFICIENT ||
            decision.reason !in setOf("missing-independent-creator-corroboration", "missing-distinctive-shared-name")
        ) {
            return decision
        }
        val x = WorkIdentityNormalizer.comparisonTitle(a.row)
        val y = WorkIdentityNormalizer.comparisonTitle(b.row)
        // A declared translation is directional evidence: the other provider need not
        // repeat the translated title. Every earlier contradiction still vetoes the
        // pair, and complete-link clustering checks every member independently.
        val sharedAlias = (x in b.names || y in a.names) &&
            a.names.intersect(b.names).any(WorkIdentityNormalizer::distinctive)
        if ((
                x == y && (
                    WorkIdentityNormalizer.distinctive(x) ||
                        (x.length >= 6 && WorkIdentityNormalizer.specificSingleWord(x))
                    )
                ) || sharedAlias
        ) {
            return WorkIdentityDecision(
                WorkIdentityVerdict.LIKELY_SAME_WORK,
                "presentation-exact-name-unknown-credits",
                setOf("reversible-ui-only-not-canonical"),
            )
        }
        return decision
    }

    fun resolve(
        input: List<SearchWorkCandidate>,
        established: Map<String, List<SearchWorkCandidate>>? = null,
        previous: List<SearchWorkGroup> = emptyList(),
    ): Resolution = AtlasWorkIdentityResolver(this).resolve(input, established, previous)
}
