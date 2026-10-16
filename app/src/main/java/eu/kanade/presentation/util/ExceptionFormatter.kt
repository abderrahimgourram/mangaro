package eu.kanade.presentation.util

import android.content.Context
import eu.kanade.tachiyomi.network.HttpException
import tachiyomi.data.source.NoResultsException
import tachiyomi.domain.source.model.SourceNotInstalledException

context(context: Context)
val Throwable.formattedMessage: String
    get() = when (this) {
        is NoResultsException -> "لم نعثر على محتوى مطابق"
        is SourceNotInstalledException -> "هذا المحتوى غير متاح حاليًا، راجع إعدادات المصادر"
        is HttpException -> when (code) {
            401, 403 -> "تعذر الوصول إلى المحتوى حاليًا"
            404 -> "لم يعد هذا المحتوى متاحًا"
            429 -> "المصدر مشغول حاليًا، حاول لاحقًا"
            else -> "تعذر تحميل المحتوى، أعد المحاولة"
        }
        else -> mangaroErrorMessage()
    }
