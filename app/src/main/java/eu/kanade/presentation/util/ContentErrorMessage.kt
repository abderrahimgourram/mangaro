package eu.kanade.presentation.util

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.io.InterruptedIOException
import javax.net.ssl.SSLException

/** User-visible messages never contain server responses, source URLs, class names or credentials. */
fun Throwable.mangaroErrorMessage(fallback: String = "تعذر تحميل المحتوى، أعد المحاولة"): String {
    val causes = generateSequence(this) { it.cause }.take(8).toList()
    return when {
        causes.any { it is SocketTimeoutException || it is InterruptedIOException } -> "استغرق التحميل وقتًا أطول من المتوقع"
        causes.any { it is UnknownHostException || it is java.net.ConnectException } -> "تعذر الاتصال بالإنترنت"
        causes.any { it is SSLException } -> "تعذر إنشاء اتصال آمن، حاول مجددًا لاحقًا"
        else -> fallback
    }
}

private val internalDetails = Regex("(?i)https?://|bearer|access[_ -]?token|refresh[_ -]?token|eyJ[a-z0-9_-]+|exception|stacktrace|SQL|postgres|supabase|team\\s*x|mangatime|mangalek|azora|hijala|mangadar|mangaswat|مانجا\\s*ليك")
fun String?.contentErrorMessage(): String? {
    if (this == null) return null
    if (contains("locked", true)) return "هذا الفصل مغلق حاليًا"
    if (contains("novel", true)) return "هذا المحتوى النصي غير مدعوم في قارئ الصور"
    if (contains("timeout", true) || contains("timed out", true)) return "استغرق التحميل وقتًا أطول من المتوقع"
    if (contains("UnknownHost", true) || contains("No network", true)) return "تعذر الاتصال بالإنترنت"
    // Preserve app-authored Arabic explanations, never raw transport or backend text.
    return if (length <= 300 && any { it in '\u0600'..'\u06ff' } && !internalDetails.containsMatchIn(this)) this
    else "تعذر تحميل المحتوى، أعد المحاولة"
}
