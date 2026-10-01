package eu.kanade.presentation.util

private val providerName = Regex("(?i)team\\s*x|mangatime|mangalek|azora|hijala|mangadar|mangaswat|مانجا\\s*ليك")

/** Keep full source diagnostics in logs; normal content errors don't expose provider identity. */
fun String?.contentErrorMessage(): String? {
    if (this == null || !providerName.containsMatchIn(this)) return this
    return when {
        contains("locked", true) -> "هذا الفصل مغلق حالياً"
        contains("novel", true) -> "هذا المحتوى النصي غير مدعوم في قارئ الصور"
        else -> "تعذّر تحميل المحتوى. حاول مجدداً"
    }
}
