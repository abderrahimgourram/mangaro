package mihon.domain.source.discovery.model

data class SourceDiscoveryResult(
    val sourceId: Long,
    val sourceName: String,
    val category: DiscoveryCategory,
    val page: Int,
    val hasNextPage: Boolean,
    val items: List<SourceDiscoveryItem>,
    /** False for error placeholders and last-known fallback data. */
    val isFresh: Boolean = true,
)
