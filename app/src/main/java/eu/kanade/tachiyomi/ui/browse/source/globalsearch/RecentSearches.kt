package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.PreferenceStore

/** Device-local UI history only. No cloud, analytics or title/source identity normalization. */
class RecentSearches(store: PreferenceStore) {
    private val preference = store.getString("mangaro.search.recent", "[]")
    fun read(): List<String> = runCatching {
        Json.decodeFromString<List<String>>(preference.get()).map(String::trim).filter(String::isNotEmpty).distinctBy { it.lowercase(java.util.Locale.ROOT) }.take(10)
    }.getOrDefault(emptyList())
    fun record(query: String): List<String> {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || trimmed.length > 256) return read()
        return save((listOf(trimmed) + read()).distinctBy { it.lowercase(java.util.Locale.ROOT) }.take(10))
    }
    fun remove(query: String): List<String> = save(read().filterNot { it == query })
    fun clear(): List<String> = save(emptyList())
    private fun save(queries: List<String>): List<String> {
        runCatching { preference.set(Json.encodeToString(queries)) }
        return queries
    }
}
