package eu.kanade.tachiyomi.novels

import kotlinx.serialization.Serializable

/** No manga IDs, account state, XP, or image-reader models enter this subsystem. */
@Serializable
data class Novel(val sourceId: String, val url: String, val title: String, val cover: String? = null,
    val description: String = "", val author: String? = null, val status: String? = null,
    val genres: List<String> = emptyList(), val chapterCount: Int? = null) : java.io.Serializable {
    val id: String get() = sourceId + ":" + url
}
@Serializable
data class NovelChapter(val url: String, val title: String, val order: Int, val volume: String? = null) : java.io.Serializable {
    val id: String get() = url
}
data class NovelGenre(val name: String,val value: String)
data class NovelPage(val novels: List<Novel>, val nextPage: Int? = null, val notice: String? = null, val genres: List<NovelGenre> = emptyList())
data class ChapterPage(val chapters: List<NovelChapter>, val nextPage: Int? = null, val notice: String? = null)
data class NovelText(val paragraphs: List<String>, val markup: List<String> = emptyList())
@Serializable
data class NovelReadingPosition(val chapter: NovelChapter, val paragraph: Int = 0, val offset: Int = 0, val updatedAt: Long = 0, val anchor: String = "")
@Serializable
data class NovelLibraryItem(val novel: Novel, val saved: Boolean = false, val position: NovelReadingPosition? = null)
@Serializable
data class NovelReaderSettings(val fontSize: Float = 21f, val lineSpacing: Float = 1.8f,
    val paragraphSpacing: Int = 18, val margin: Int = 24, val theme: String = "dark")

class NovelSourceFailure(val publicMessage: String, detail: String) : Exception(detail)

interface NovelSource {
    val id: String
    val name: String
    val baseUrl: String
    suspend fun catalog(page: Int = 1, latest: Boolean = true, genre: String? = null): NovelPage
    suspend fun search(query: String, page: Int = 1): NovelPage
    suspend fun details(novel: Novel): Novel
    suspend fun chapters(novel: Novel, page: Int = 1): ChapterPage
    suspend fun chapter(chapter: NovelChapter): NovelText
}
