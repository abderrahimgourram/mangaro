package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.domain.chapter.model.toDbChapter
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import kotlinx.coroutines.flow.MutableStateFlow
import tachiyomi.core.common.util.system.logcat

data class ReaderChapter(val chapter: Chapter) {
    var verifiedChapterList: Boolean = false

    val stateFlow = MutableStateFlow<State>(State.Wait)
    var state: State
        get() = stateFlow.value
        set(value) {
            stateFlow.value = value
        }

    val pages: List<ReaderPage>?
        get() = (state as? State.Loaded)?.pages

    var pageLoader: PageLoader? = null

    var requestedPage: Int = 0

    private var references = 0

    constructor(chapter: tachiyomi.domain.chapter.model.Chapter) : this(chapter.toDbChapter())

    fun ref() {
        references++
    }

    fun unref() {
        references--
        if (references == 0) {
            if (pageLoader != null) {
                logcat { "Recycling chapter ${chapter.name}" }
            }
            pageLoader?.recycle()
            pageLoader = null
            state = State.Wait
        }
    }

    sealed interface State {
        data object Wait : State
        data object Loading : State
        data class Error(val error: Throwable) : State
        data class Loaded(val pages: List<ReaderPage>) : State
    }
}

/** Admission for explicit chapter navigation only; loading/rendering stay in the existing pipeline. */
internal class ReaderChapterSelection {
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    suspend fun switch(chapters: List<ReaderChapter>, id: Long, load: suspend (ReaderChapter) -> Unit): ReaderChapter? {
        val selected = chapters.firstOrNull { it.chapter.id == id } ?: return null
        if (!busy.compareAndSet(false, true)) return null
        try {
            load(selected)
            return selected
        } finally { busy.set(false) }
    }
}
