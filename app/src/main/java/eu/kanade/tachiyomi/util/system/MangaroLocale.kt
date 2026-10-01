package eu.kanade.tachiyomi.util.system

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

object MangaroLocale {
    val arabic: Locale = Locale.forLanguageTag("ar")

    fun wrap(context: Context): Context = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(arabic))
            setLayoutDirection(arabic)
        },
    )
}
