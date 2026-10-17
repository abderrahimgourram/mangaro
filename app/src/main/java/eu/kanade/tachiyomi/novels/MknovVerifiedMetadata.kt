package eu.kanade.tachiyomi.novels

/** Public provider-ID metadata verified against getWorkById on 2026-10-17.
 * Makes known catalogue cards eligible for the EXISTING corroborated work reconciler immediately.
 * No cross-source chapter links or forced community mappings. Live details remain authoritative.
 */
internal object MknovVerifiedMetadata {
    private data class Identity(val title: String, val author: String, val original: String?, val aliases: List<String>)
    private val verified = mapOf(
        "96" to Identity("عبد الظل", "Guiltythree", "Shadow Slave", listOf("عبد الظلام", "Shadow Slave", "عبد الظل")),
        "37" to Identity("القس المجنون", "Gu Zhen Ren", null, listOf("القس المجنون")),
    )
    fun enrich(novel: Novel, providerId: String, metadataResponse: Boolean): Novel {
        if (novel.sourceId != MknovSource.SOURCE_ID || metadataResponse) return novel
        val known = verified[providerId]?.takeIf { it.title == novel.title } ?: return novel
        return novel.copy(author = known.author, originalTitle = known.original, alternativeTitles = known.aliases)
    }
}
