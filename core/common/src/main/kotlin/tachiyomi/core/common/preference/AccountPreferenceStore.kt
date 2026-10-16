package tachiyomi.core.common.preference

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import tachiyomi.core.common.util.lang.LocalAccountScope

/** Only personal preference groups use this wrapper. Auth, consent and source rules do not. */
class AccountPreferenceStore(private val base: PreferenceStore, private val onlyKnown: Boolean = false) : PreferenceStore {
    private fun scoped(key: String, owner: String): String {
        val part = LocalAccountScope.segment(owner)
        if (part.isEmpty()) return key
        val private = if (key.startsWith("__PRIVATE_")) "__PRIVATE_" else if (key.startsWith("__APP_STATE_")) "__APP_STATE_" else ""
        return private + "local.$part." + key.removePrefix(private)
    }
    private fun <T> preference(key: String, factory: (String) -> Preference<T>): Preference<T> {
        if (key == "disallow_non_ascii_filenames" || onlyKnown && !LocalPersonalPreferenceKeys.contains(key)) return factory(key)
        return object : Preference<T> {
        private fun own() = factory(scoped(key, LocalAccountScope.fileOwner()))
        private fun visible(): Pair<Preference<T>, String> {
            val owner = LocalAccountScope.fileOwner()
            val current = own()
            if (current.isSet()) return current to owner
            for (legacy in LocalAccountScope.readableFileOwners().drop(1)) {
                val candidate = factory(scoped(key, legacy))
                if (candidate.isSet()) return candidate to legacy
            }
            return current to owner
        }
        override fun key() = own().key()
        @Suppress("UNCHECKED_CAST")
        override fun get(): T = try {
            LocalAccountScope.check()
            val (selected, source) = visible()
            categoryValue(key, selected.get(), source, LocalAccountScope.fileOwner()) as T
        } catch (_: LocalAccountScope.Changed) { defaultValue() }
        override fun set(value: T) { LocalAccountScope.check(); own().set(value) }
        override fun isSet() = try { LocalAccountScope.check(); visible().first.isSet() } catch (_: LocalAccountScope.Changed) { false }
        override fun delete() { LocalAccountScope.check(); own().set(defaultValue()) }
        override fun defaultValue() = own().defaultValue()
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        override fun changes(): Flow<T> = LocalAccountScope.changes.flatMapLatest { state ->
            if (!state.ready) flowOf(defaultValue()) else {
                val selected = withContext(LocalAccountScope.Element(state.token)) { visible() }
                selected.first.changes().map { categoryValue(key, it, selected.second, state.token.fileOwner) as T }
            }
        }
        override fun stateIn(scope: CoroutineScope): StateFlow<T> = changes().stateIn(scope, SharingStarted.Eagerly, defaultValue())
    }
    }
    /** Only explicit claimed fallback values are translated; an account's own choices win. */
    private fun categoryValue(key: String, value: Any?, source: String, actor: String): Any? {
        if (key !in LocalPersonalPreferenceKeys.categoryReferences || source == actor) return value
        fun mapped(id: Long) = LocalAccountScope.claimedCategory(actor, source, id)
        return when (value) {
            is Int -> mapped(value.toLong())?.toInt() ?: if (key == "default_category") -1 else 0
            is Long -> mapped(value) ?: if (key == "default_category") -1L else 0L
            is Set<*> -> value.mapNotNull { raw ->
                val text = raw as? String ?: return@mapNotNull null
                text.toLongOrNull()?.let { mapped(it)?.toString() } ?: text.takeIf { it.toLongOrNull() == null }
            }.toSet()
            else -> value
        }
    }
    override fun getString(key: String, defaultValue: String) = preference(key) { base.getString(it, defaultValue) }
    override fun getLong(key: String, defaultValue: Long) = preference(key) { base.getLong(it, defaultValue) }
    override fun getInt(key: String, defaultValue: Int) = preference(key) { base.getInt(it, defaultValue) }
    override fun getFloat(key: String, defaultValue: Float) = preference(key) { base.getFloat(it, defaultValue) }
    override fun getBoolean(key: String, defaultValue: Boolean) = preference(key) { base.getBoolean(it, defaultValue) }
    override fun getStringSet(key: String, defaultValue: Set<String>) = preference(key) { base.getStringSet(it, defaultValue) }
    override fun <T> getObjectFromString(key: String, defaultValue: T, serializer: (T) -> String, deserializer: (String) -> T) = preference(key) { base.getObjectFromString(it, defaultValue, serializer, deserializer) }
    override fun <T> getObjectFromInt(key: String, defaultValue: T, serializer: (T) -> Int, deserializer: (Int) -> T) = preference(key) { base.getObjectFromInt(it, defaultValue, serializer, deserializer) }
    override fun <T> getObjectSetFromStringSet(key: String, defaultValue: Set<T>, serializer: (T) -> String, deserializer: (String) -> T?) = preference(key) { base.getObjectSetFromStringSet(it, defaultValue, serializer, deserializer) }
    override fun getAll(): Map<String, *> {
        LocalAccountScope.check()
        val all = base.getAll()
        val out = mutableMapOf<String, Any?>()
        if (onlyKnown) all.filterKeys { !it.contains("local.") && !LocalPersonalPreferenceKeys.contains(it) }.forEach { (k,v) -> out[k]=v }
        for (owner in LocalAccountScope.readableFileOwners().asReversed()) {
            val segment = LocalAccountScope.segment(owner)
            for ((key,value) in all) {
                val decoded = when {
                    segment.isEmpty() && !key.contains("local.") && LocalPersonalPreferenceKeys.contains(key) -> key
                    segment.isNotEmpty() && key.startsWith("local.$segment.") -> key.removePrefix("local.$segment.")
                    segment.isNotEmpty() && key.startsWith("__PRIVATE_local.$segment.") -> "__PRIVATE_"+key.removePrefix("__PRIVATE_local.$segment.")
                    segment.isNotEmpty() && key.startsWith("__APP_STATE_local.$segment.") -> "__APP_STATE_"+key.removePrefix("__APP_STATE_local.$segment.")
                    else -> null
                }
                if (decoded != null) out[decoded]=categoryValue(decoded,value,owner,LocalAccountScope.fileOwner())
            }
        }
        return out
    }
}
