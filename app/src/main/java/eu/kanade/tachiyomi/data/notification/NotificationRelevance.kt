package eu.kanade.tachiyomi.data.notification

import tachiyomi.domain.chapter.model.Chapter
import java.security.MessageDigest

internal fun notificationEventKey(vararg parts: String): String = MessageDigest.getInstance("SHA-256")
    .digest(parts.joinToString("\u0000").toByteArray()).joinToString("") { "%02x".format(it) }

/** Reordering, first import, incomplete fetches and older numeric backfills are not new releases. */
internal fun relevantChapterUpdates(previous: List<Chapter>, inserted: List<Chapter>, library: Boolean, verified: Boolean): List<Chapter> {
    if (!library || !verified || previous.isEmpty()) return emptyList()
    val knownUrls = previous.map { it.url }.toSet()
    val highestNumber = previous.filter { it.isRecognizedNumber }.maxOfOrNull { it.chapterNumber }
    return inserted.distinctBy { it.url }.filter { chapter ->
        !chapter.read && chapter.url.isNotBlank() && chapter.url !in knownUrls &&
            (highestNumber == null || !chapter.isRecognizedNumber || chapter.chapterNumber > highestNumber)
    }
}
