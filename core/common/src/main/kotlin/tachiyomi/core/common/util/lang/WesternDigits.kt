package tachiyomi.core.common.util.lang

import java.text.DateFormat
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.util.Date
import java.util.Locale

/** Display formatting only. Never normalize user/source text or persist isolated output. */
object WesternDigits {
    val locale: Locale = Locale.US

    /** Keep the language/calendar/region, selecting only Western decimal digits. */
    fun numberingLocale(languageLocale: Locale): Locale = Locale.Builder()
        .setLocale(languageLocale)
        .setUnicodeLocaleKeyword("nu", "latn")
        .build()

    /** For app-owned labels and formatter results, not names, comments or input. */
    fun normalize(generatedText: String): String {
        if (generatedText.none { it !in '0'..'9' && it.isDigit() }) return generatedText
        return buildString(generatedText.length) {
            generatedText.forEach { character ->
                val digit = if (character.isDigit()) Character.digit(character, 10) else -1
                append(if (digit >= 0) '0' + digit else character)
            }
        }
    }

    /** Normalize the app-owned pattern BEFORE substitution; string arguments stay literal. */
    fun format(pattern: String, vararg args: Any?): String = String.format(locale, normalize(pattern), *args)

    fun decimalFormat(pattern: String): DecimalFormat = DecimalFormat(pattern, DecimalFormatSymbols(locale))

    fun percentFormat(): NumberFormat = NumberFormat.getPercentInstance(locale)

    fun dateTimeFormat(formatter: DateTimeFormatter): DateTimeFormatter = formatter.withDecimalStyle(DecimalStyle.STANDARD)

    fun date(value: Date, style: Int = DateFormat.DEFAULT, languageLocale: Locale = Locale.getDefault()): String =
        normalize(DateFormat.getDateInstance(style, numberingLocale(languageLocale)).format(value))

    fun time(value: Date, style: Int = DateFormat.SHORT, languageLocale: Locale = Locale.getDefault()): String =
        normalize(DateFormat.getTimeInstance(style, numberingLocale(languageLocale)).format(value))

    fun timestamp(value: Date, pattern: String, languageLocale: Locale = Locale.getDefault()): String =
        normalize(SimpleDateFormat(pattern, numberingLocale(languageLocale)).format(value))

    /** Numeric runs such as current/total, decimals and times must stay together in RTL. */
    fun isolate(numericText: String): String = "\u2066${normalize(numericText)}\u2069"
}
