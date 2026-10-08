package tachiyomi.core.common.i18n

import android.content.Context
import dev.icerock.moko.resources.PluralsResource
import dev.icerock.moko.resources.StringResource
import dev.icerock.moko.resources.desc.Plural
import dev.icerock.moko.resources.desc.Resource
import dev.icerock.moko.resources.desc.StringDesc
import tachiyomi.core.common.util.lang.WesternDigits

fun Context.stringResource(resource: StringResource): String {
    return WesternDigits.normalize(StringDesc.Resource(resource).toString(this).fixed())
}

fun Context.stringResource(resource: StringResource, vararg args: Any): String {
    return WesternDigits.format(stringResource(resource), *localizedArgs(args)).fixed()
}

fun Context.pluralStringResource(resource: PluralsResource, count: Int): String {
    return WesternDigits.normalize(StringDesc.Plural(resource, count).toString(this).fixed())
}

fun Context.pluralStringResource(resource: PluralsResource, count: Int, vararg args: Any): String {
    return WesternDigits.format(pluralStringResource(resource, count), *localizedArgs(args)).fixed()
}

// Preserve Moko's nested-description support without rewriting user string arguments.
private fun Context.localizedArgs(args: Array<out Any>): Array<Any> =
    args.map { (it as? StringDesc)?.toString(this) ?: it }.toTypedArray()

// TODO: janky workaround for https://github.com/icerockdev/moko-resources/issues/337
private fun String.fixed() =
    this.replace("""\""", """"""")
