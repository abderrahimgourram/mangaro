package eu.kanade.tachiyomi.ui.reader.model

/** SavedState pages belong to one chapter, never to the reader globally. Zero-based. */
internal class ReaderPositionOwnership(private val restoredChapter: Long, private val restoredPage: Int) {
    private var restored = false
    fun enter(chapter: Long, savedPage: Int, read: Boolean, lastIndex: Int, explicitPage: Int? = null): Int {
        val page = explicitPage ?: if (!restored && chapter == restoredChapter && restoredPage >= 0) restoredPage
            else if (!read) savedPage else 0
        restored = true
        return page.coerceIn(0, lastIndex.coerceAtLeast(0))
    }
    companion object {
        fun owns(currentChapter: Long?, callbackChapter: Long?) = currentChapter != null && currentChapter == callbackChapter
    }
}

/** Selection generations fence late callbacks even after A -> B -> A navigation. */
internal class ReaderPageSelection {
    private var generation=0L
    private val latest=linkedMapOf<Long,Long>()
    @Synchronized fun selected(chapter:Long):Long {
        val ticket=++generation;latest[chapter]=ticket
        if(latest.size>64) latest.remove(latest.keys.first())
        return ticket
    }
    @Synchronized fun isLatest(chapter:Long,ticket:Long)=latest[chapter]==ticket
    @Synchronized fun isVisible(ticket:Long)=generation==ticket
}
