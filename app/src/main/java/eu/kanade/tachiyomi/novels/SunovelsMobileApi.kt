package eu.kanade.tachiyomi.novels

import kotlinx.serialization.json.*

/** Identity comes from the ownership-verified public reader record, never order, title or guessed IDs. */
internal data class SunovelsMobileIdentity(val chapterId: Long, val novelId: Long, val slug: String, val number: String) {
    val endpoint: String get() = "https://adminx.sunovels.com/api/mobile/chapter/$chapterId?v=0.0.1&device=android"
}
internal data class SunovelsMobileContent(val html: String, val title: String, val number: String)

/** Observed v0.0.19 anonymous reader contract. No session forgery, API keys, login or paid-download API. */
internal object SunovelsMobileApi {
    fun parse(raw: String, expected: SunovelsMobileIdentity): SunovelsMobileContent {
        val root = try { Json.parseToJsonElement(raw) as? JsonObject } catch (_: Exception) { null }
            ?: failure("SUN_MOBILE_RESPONSE", "Invalid mobile JSON")
        val error = root["error"] as? JsonObject
        if (error != null || root["error"] != null && root["error"] != JsonNull)
            failure("SUN_MOBILE_ACCESS", "Mobile server rejected reader request")
        val chapter = root["chapter"] as? JsonObject ?: failure("SUN_MOBILE_RESPONSE", "Missing mobile chapter")
        val novel = chapter["novel"] as? JsonObject ?: failure("SUN_OWNERSHIP", "Missing mobile novel owner")
        fun JsonObject.value(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
        if (chapter.value("id")?.toLongOrNull() != expected.chapterId || novel.value("id")?.toLongOrNull() != expected.novelId ||
            novel.value("url") != expected.slug) failure("SUN_OWNERSHIP", "Mobile chapter ownership mismatch")
        val number = chapter.value("chapterNumber") ?: failure("NUMBER_MISMATCH", "Missing mobile chapter number")
        if (number.toBigDecimalOrNull()?.compareTo(expected.number.toBigDecimal()) != 0)
            failure("NUMBER_MISMATCH", "Mobile chapter number mismatch")
        // An absent lock flag is not permission by itself; actual meaningful story content must also pass validation.
        if ((chapter["isLocked"] as? JsonPrimitive)?.booleanOrNull == true || (chapter["locked"] as? JsonPrimitive)?.booleanOrNull == true)
            throw NovelSourceFailure("هذا الفصل مقفل في واجهة شمس الروايات.", "Explicit mobile reader lock", 403, code = "SUN_LOCKED", sourceVerified = true)
        if (chapter.value("canRead") == "false" || chapter.value("available") == "false")
            failure("SUN_MOBILE_ACCESS", "Mobile chapter not available")
        val html = chapter.value("content")?.takeIf { it.isNotBlank() && it.length <= 4 * 1024 * 1024 }
            ?: failure("CONTAINER_EMPTY", "Missing mobile story content")
        return SunovelsMobileContent(html, chapter.value("title").orEmpty(), number)
    }
    private fun failure(code: String, detail: String): Nothing =
        throw NovelSourceFailure("تعذّر التحقق من إتاحة الفصل في واجهة شمس الروايات.", detail, code = code)
}
