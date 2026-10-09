package eu.kanade.tachiyomi.novels

data class NovelChapterGroup(val id: String, val title: String?, val chapters: List<NovelChapter>, val realVolume: Boolean)

/** Genuine volume names only. Numeric ranges are navigation, never presented as author volumes. */
fun novelChapterGroups(index: NovelChapterIndex, descending: Boolean = false): List<NovelChapterGroup> {
    val volumes = index.volumes.associateBy { it.id }
    val sections = if (index.chapters.any { it.volume != null }) index.chapters.groupBy { it.volumeId ?: "ungrouped" }.map { (id, chapters) ->
        val name = volumes[id]?.title ?: chapters.firstOrNull()?.volume
        NovelChapterGroup(id, name, chapters, name != null)
    } else if (index.chapters.size > 100) index.chapters.chunked(100).mapIndexed { position, chapters ->
        NovelChapterGroup("range-$position", "الفصول ${position * 100 + 1}–${position * 100 + chapters.size}", chapters, false)
    } else listOf(NovelChapterGroup("chapters", null, index.chapters, false))
    return if (descending) sections.reversed().map { it.copy(chapters = it.chapters.reversed()) } else sections
}
