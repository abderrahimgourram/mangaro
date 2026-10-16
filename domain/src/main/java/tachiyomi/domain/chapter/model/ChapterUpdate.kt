package tachiyomi.domain.chapter.model

import kotlinx.serialization.json.JsonObject

data class ChapterUpdate(
    val id: Long,
    val mangaId: Long? = null,
    val read: Boolean? = null,
    val bookmark: Boolean? = null,
    val lastPageRead: Long? = null,
    val dateFetch: Long? = null,
    val sourceOrder: Long? = null,
    val url: String? = null,
    val name: String? = null,
    val dateUpload: Long? = null,
    val chapterNumber: Double? = null,
    val scanlator: String? = null,
    val version: Long? = null,
    val memo: JsonObject? = null,
    val totalPages: Long? = null,
    val accountToken: tachiyomi.core.common.util.lang.LocalAccountScope.Token = tachiyomi.core.common.util.lang.LocalAccountScope.capture(),
)

fun Chapter.toChapterUpdate(): ChapterUpdate {
    return ChapterUpdate(
        id = id,
        accountToken = accountToken,
        mangaId = mangaId,
        read = read,
        bookmark = bookmark,
        lastPageRead = lastPageRead,
        dateFetch = dateFetch,
        sourceOrder = sourceOrder,
        url = url,
        name = name,
        dateUpload = dateUpload,
        chapterNumber = chapterNumber,
        scanlator = scanlator,
        version = version,
        memo = memo,
        totalPages = null,
    )
}
