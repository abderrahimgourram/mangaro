package eu.kanade.tachiyomi.util.lang

import android.content.Context
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toInstant
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toLocalDateTime
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.core.common.util.lang.WesternDigits
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import kotlin.math.absoluteValue
import kotlin.time.Clock
import kotlin.time.Instant

fun LocalDateTime.toDateTimestampString(dateTimeFormatter: DateTimeFormatter): String {
    val javaLocalDateTime = this.toJavaLocalDateTime()
    val date = WesternDigits.dateTimeFormat(dateTimeFormatter).format(javaLocalDateTime)
    val time = WesternDigits.dateTimeFormat(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)).format(javaLocalDateTime)
    return "$date $time"
}

fun Date.toTimestampString(): String {
    return WesternDigits.time(this)
}

fun Long.convertEpochMillisZone(
    from: TimeZone,
    to: TimeZone,
): Long {
    return Instant.fromEpochMilliseconds(this)
        .toLocalDateTime(from)
        .toInstant(to)
        .toEpochMilliseconds()
}

fun Long.toLocalDate(): LocalDate {
    return Instant.fromEpochMilliseconds(this).toLocalDateTime(TimeZone.currentSystemDefault()).date
}

fun Long.toJavaLocalDate(): java.time.LocalDate {
    return this.toLocalDate().toJavaLocalDate()
}

fun LocalDate.toRelativeString(
    context: Context,
    relative: Boolean = true,
    dateFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT),
): String {
    val displayFormat = WesternDigits.dateTimeFormat(dateFormat)
    if (!relative) {
        return displayFormat.format(this.toJavaLocalDate())
    }
    val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    val difference = this.daysUntil(today)
    return when {
        difference < -7 -> displayFormat.format(this.toJavaLocalDate())
        difference < 0 -> context.pluralStringResource(
            MR.plurals.upcoming_relative_time,
            difference.absoluteValue,
            difference.absoluteValue,
        )

        difference < 1 -> context.stringResource(MR.strings.relative_time_today)
        difference < 7 -> context.pluralStringResource(
            MR.plurals.relative_time,
            difference,
            difference,
        )

        else -> displayFormat.format(this.toJavaLocalDate())
    }
}
