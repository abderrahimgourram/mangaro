package eu.kanade.tachiyomi.novels

data class NovelChapterGroup(val id: String, val title: String?, val chapters: List<NovelChapter>, val realVolume: Boolean)

/** Genuine volume names only. Numeric ranges are navigation, never presented as author volumes. */
fun novelChapterGroups(index: NovelChapterIndex, descending: Boolean = false): List<NovelChapterGroup> {
    val volumes = index.volumes.associateBy { it.id }
    val unique = index.chapters.distinctBy { it.id }
    val sections = if (unique.any { it.volumeId != null || it.volume != null }) unique.groupBy { it.volumeId ?: it.volume?.let { name -> "name:" + name } ?: "ungrouped" }.map { (id, chapters) ->
        val name = volumes[id]?.title ?: chapters.firstOrNull()?.volume
        NovelChapterGroup(id, name, chapters, name != null)
    } else listOf(NovelChapterGroup("chapters", null, unique, false))
    return if (descending) sections.reversed().map { it.copy(chapters = it.chapters.reversed()) } else sections
}
