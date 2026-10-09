package eu.kanade.tachiyomi.novels

data class NovelChapterGroup(val id: String, val title: String?, val chapters: List<NovelChapter>, val realVolume: Boolean, val declaredCount: Int? = null)

/** Genuine volume names only. Numeric ranges are navigation, never presented as author volumes. */
fun novelChapterGroups(index: NovelChapterIndex, descending: Boolean = false): List<NovelChapterGroup> {
    val volumes = index.volumes.associateBy { it.id }
    val unique = index.chapters.distinctBy { it.id }
    val sections = if (unique.any { it.volumeId != null || it.volume != null }) unique.groupBy { it.volumeId ?: it.volume?.let { name -> "name:" + name } ?: "ungrouped" }.map { (id, chapters) ->
        val name = volumes[id]?.title ?: chapters.firstOrNull()?.volume
        NovelChapterGroup(id, name, chapters, name != null, volumes[id]?.declaredCount)
    } else listOf(NovelChapterGroup("chapters", null, unique, false))
    return if (descending) sections.reversed().map { it.copy(chapters = it.chapters.reversed()) } else sections
}

/** Append source-ordered metadata; unchanged volumes retain their list instances. */
class NovelChapterGroupBuilder {
    private var previous: NovelChapterIndex? = null
    private val seen = HashSet<String>()
    private val sections = LinkedHashMap<String, MutableList<NovelChapter>>()
    private var ascending = emptyList<NovelChapterGroup>()
    private var reversed = emptyList<NovelChapterGroup>()

    @Synchronized
    fun build(index: NovelChapterIndex, descending: Boolean): List<NovelChapterGroup> {
        val old = previous
        val append = old != null && old.editionId == index.editionId && old.chapters.size <= index.chapters.size &&
            old.chapters.indices.all { old.chapters[it] == index.chapters[it] }
        if (!append) { seen.clear(); sections.clear(); ascending = emptyList(); reversed = emptyList() }
        val changed = HashSet<String>()
        for (i in (if (append) old!!.chapters.size else 0) until index.chapters.size) {
            val chapter = index.chapters[i]
            if (!seen.add(chapter.id)) continue
            val key = chapter.volumeId ?: chapter.volume?.let { "name:" + it } ?: "ungrouped"
            sections.getOrPut(key) { mutableListOf() }.add(chapter)
            changed += key
        }
        val volumes = index.volumes.associateBy { it.id }
        val prior = ascending.associateBy { it.id }
        val hasVolumes = sections.keys.any { it != "ungrouped" }
        val next = sections.map { (key, chapters) ->
            val id = if (hasVolumes) key else "chapters"
            val title = volumes[key]?.title ?: chapters.firstOrNull()?.volume
            val cached = prior[id]
            if (key !in changed && cached?.title == title && cached?.declaredCount == volumes[key]?.declaredCount) cached
            else NovelChapterGroup(id, title, chapters.toList(), title != null, volumes[key]?.declaredCount)
        }.filterNotNull()
        if (next != ascending) {
            ascending = next
            reversed = emptyList()
        }
        previous = index
        if (!descending) return ascending
        if (reversed.isEmpty() && ascending.isNotEmpty()) reversed = ascending.asReversed().map { it.copy(chapters = it.chapters.asReversed()) }
        return reversed
    }
}
