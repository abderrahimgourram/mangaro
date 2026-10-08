package eu.kanade.presentation.util

import tachiyomi.core.common.util.lang.WesternDigits

private val formatter = WesternDigits.decimalFormat("#.###")

fun formatChapterNumber(chapterNumber: Double): String {
    return formatter.format(chapterNumber)
}
