package eu.kanade.tachiyomi.novels

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.nodes.Element

@Serializable
enum class NovelContentType { TEXT_NOVEL, COMIC, UNKNOWN }

@Serializable
enum class NovelFormatProvenance { SOURCE_TAXONOMY, SOURCE_FORMAT, VERIFIED_READER }

/** Optional metadata only; legacy catalog/library/download payloads decode without this field. */
@Serializable
data class NovelContentFormat(
    val type: NovelContentType,
    val provenance: NovelFormatProvenance,
    val value: String,
    val observedAt: Long = 0,
    val validUntil: Long? = null,
) : java.io.Serializable

/** Discovery eligibility, never a reader/download permission or an identity matching authority.
 * UNKNOWN stays explicitly unknown and provisionally visible: absence of format metadata must
 * not erase genuine legacy novels. No requests, title matching or mutable classification cache.
 */
internal object NovelContentTypeClassifier {
    data class Result(val type: NovelContentType, val reason: String)
    private val unknown = Result(NovelContentType.UNKNOWN, "NO_CURRENT_FORMAT_EVIDENCE")

    /** Measurements from an identity-checked public reader container, not the cover/page body.
     * sequentialPanels counts source-format-marked panels, never ordinary illustrations.
     */
    data class ReadingSample(
        val httpStatus: Int,
        val canonicalMatches: Boolean,
        val proseParagraphs: Int,
        val proseCharacters: Int,
        val sequentialPanels: Int,
    )
    fun readingFormat(sample: ReadingSample): NovelContentType = when {
        sample.httpStatus != 200 || !sample.canonicalMatches || sample.proseParagraphs < 0 ||
            sample.proseCharacters < 0 || sample.sequentialPanels < 0 -> NovelContentType.UNKNOWN
        sample.proseParagraphs >= 3 && sample.proseCharacters >= 200 -> NovelContentType.TEXT_NOVEL
        sample.sequentialPanels >= 3 && sample.proseCharacters == 0 -> NovelContentType.COMIC
        else -> NovelContentType.UNKNOWN
    }

    // Public reading-format observation, not a title/cover rule. 2026-10-18: exact canonical
    // chapter1 returned37 distinct .nhv-webtoon-page panels, no prose. See the filtering report.
    // Exact edition observation, bounded in time: URL reuse or format changes require new
    // evidence, not an everlasting URL blacklist. Normal metadata errors never renew it.
    internal const val REVIEWED_AT = 1792281600000L
    internal const val REVIEWED_UNTIL = REVIEWED_AT + 7L * 24 * 60 * 60 * 1000
    private const val REVIEWED_EDITION = "novel.cenele:https://cenele.com/cont/shadow-sl-manhwa/"

    fun classify(novel: Novel, now: Long = System.currentTimeMillis()): Result {
        val supplied = novel.contentFormat?.takeIf {
            it.value.length <= 160 && (it.validUntil == null || now in it.observedAt..it.validUntil)
        }
        val authorMatches = novel.author.isNullOrBlank() || NovelIdentity.normalize(novel.author) == "guiltythree"
        val reviewed = if (novel.id == REVIEWED_EDITION && authorMatches && now in REVIEWED_AT..REVIEWED_UNTIL) {
            NovelContentFormat(
                readingFormat(ReadingSample(200, true, 0, 0, 37)),
                NovelFormatProvenance.VERIFIED_READER,
                "cenele.webtoon-panels.v1:37:no-prose",
                REVIEWED_AT,
                REVIEWED_UNTIL,
            )
        } else {
            null
        }
        val evidence = listOfNotNull(supplied, reviewed)
        if (evidence.isEmpty()) return unknown
        val rank = evidence.maxOf { it.provenance.ordinal }
        val best = evidence.filter { it.provenance.ordinal == rank }
        val newest = best.maxOf { it.observedAt }
        val current = best.filter { it.observedAt == newest }
        if (current.map { it.type }.distinct().size != 1) {
            return Result(NovelContentType.UNKNOWN, "CONFLICTING_FORMAT_EVIDENCE")
        }
        val value = current.first()
        return Result(value.type, "${value.provenance}:${value.value}")
    }

    fun eligible(novel: Novel): Boolean = classify(novel).type != NovelContentType.COMIC &&
        tachiyomi.domain.manga.model.DiscoveryEligibility.valid(novel.title, novel.url)

    /** Only dedicated type/format fields, never generic 'type' (often country), genre or title. */
    fun jsonFormat(row: JsonObject): NovelContentFormat? = declared(
        listOf("content_type", "content_format", "contentType", "contentFormat", "book_format")
            .mapNotNull { (row[it] as? JsonPrimitive)?.takeIf { v -> v.isString }?.content },
    )

    /** Call on the source's individual card or main metadata container, not the whole page. */
    fun htmlFormat(metadata: Element?): NovelContentFormat? = metadata?.let { root ->
        declared(
            listOf("data-content-type", "data-content-format").mapNotNull {
                root.attr(it).takeIf(String::isNotBlank)
            } + root.select("[itemprop=bookFormat]").map { it.attr("content").ifBlank { it.text() } },
        )
    }

    /** Exact controlled labels; an adaptation mention or a sentence is never a format tag. */
    fun declared(
        values: List<String>,
        provenance: NovelFormatProvenance = NovelFormatProvenance.SOURCE_FORMAT,
    ): NovelContentFormat? {
        if (values.size > 8 || values.any { it.length > 160 }) {
            return NovelContentFormat(NovelContentType.UNKNOWN, provenance, "oversized-format-metadata")
        }
        val bounded = values.map(String::trim).filter(String::isNotBlank)
        if (bounded.isEmpty()) return null
        val types = bounded.map { value ->
            when (NovelIdentity.normalize(value)) {
                "manga", "manhwa", "manhua", "comic", "comics", "webtoon", "comic book",
                "مانجا", "مانغا", "مانغوا", "مانهوا", "مانها", "مانوا", "قصه مصوره", "قصص مصوره",
                -> NovelContentType.COMIC
                "novel", "text novel", "web novel", "webnovel", "light novel", "روايه", "روايه نصيه", "روايه خفيفه" ->
                    NovelContentType.TEXT_NOVEL
                else -> NovelContentType.UNKNOWN
            }
        }.distinct()
        return NovelContentFormat(
            types.singleOrNull() ?: NovelContentType.UNKNOWN,
            provenance,
            if (types.size > 1) "conflicting-source-format" else bounded.first(),
        )
    }
}
