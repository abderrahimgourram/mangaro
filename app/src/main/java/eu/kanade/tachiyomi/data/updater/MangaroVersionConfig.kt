package eu.kanade.tachiyomi.data.updater

import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** Public release metadata only. Invalid metadata never establishes a mandatory update. */
internal data class MangaroVersionConfig(
    val latestVersionName: String,
    val latestVersionCode: Int,
    val minSupportedVersionCode: Int,
    val forceUpdate: Boolean,
    val downloadUrl: String,
) {
    fun requiresUpdate(installedVersionCode: Int): Boolean =
        forceUpdate && installedVersionCode < minSupportedVersionCode

    companion object {
        fun parse(body: String): MangaroVersionConfig? = runCatching {
            val json = Json.parseToJsonElement(body) as? JsonObject ?: return null
            fun primitive(key: String) = json[key] as? JsonPrimitive
            val name = primitive("latestVersionName")?.takeIf { it.isString }?.content ?: return null
            if (!name.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) return null
            val latest = primitive("latestVersionCode")?.takeUnless { it.isString }?.intOrNull ?: return null
            val minimum = primitive("minSupportedVersionCode")?.takeUnless { it.isString }?.intOrNull ?: return null
            val force = primitive("forceUpdate")?.takeUnless { it.isString }?.booleanOrNull ?: return null
            val url = primitive("downloadUrl")?.takeIf { it.isString }?.content ?: return null
            if (latest <= 0 || minimum <= 0 || minimum > latest) return null
            val uri = URI(url)
            if (uri.scheme != "https" || uri.host != "github.com" || uri.port != -1 ||
                uri.userInfo != null || uri.query != null || uri.fragment != null ||
                !uri.path.startsWith("/abderrahimgourram/mangaro/releases/download/v$name/Mangaro-") ||
                !uri.path.endsWith(".apk")
            ) return null
            MangaroVersionConfig(name, latest, minimum, force, url)
        }.getOrNull()
    }
}
