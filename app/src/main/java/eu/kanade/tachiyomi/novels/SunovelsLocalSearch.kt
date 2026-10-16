package eu.kanade.tachiyomi.novels

/** Derived in-memory index over the existing persistent registry; no second catalog database. */
internal class SunovelsLocalSearch {
    private data class Entry(val novel: Novel, val text: String, val genres: Set<String>)
    private var snapshot: NovelCatalogState? = null
    private var entries = emptyList<Entry>()
    @Synchronized fun page(state: NovelCatalogState, term: String, page: Int, genre: String?): NovelPage? {
        if (page !in 1..2000 || term.length > 128) return null
        val tokens = NovelIdentity.normalize(term).split(' ').filter(String::isNotBlank)
        if (tokens.size > 8) return null
        if (snapshot !== state) {
            entries = state.works.flatMap { it.editions }.filter { it.sourceId == SunovelsSource.SOURCE_ID }
                .distinctBy { it.id }.sortedBy { it.id }.map { novel -> Entry(novel,
                    NovelIdentity.normalize((listOf(novel.title, novel.originalTitle.orEmpty(), novel.author.orEmpty()) + novel.alternativeTitles).joinToString(" ")),
                    novel.genres.mapTo(HashSet(), NovelGenres::key)) }
            snapshot = state
        }
        val category = genre?.let(NovelGenres::key)
        val rows = entries.asSequence().filter { entry -> tokens.all { it in entry.text } && (category == null || category in entry.genres) }
            .drop((page - 1) * 24).take(25).map { it.novel }.toList()
        if (rows.isEmpty()) return null
        return NovelPage(rows.take(24), if (rows.size > 24) page + 1 else null,
            notice = "بيانات اكتشاف محفوظة؛ اكتمال الفهرس وإتاحة الفصول يحتاجان التحقق من المصدر.")
    }
}
