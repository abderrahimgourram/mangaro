package mihon.domain.source.registry

import tachiyomi.domain.source.model.Source

interface InternalSourceRegistry {
    fun getSources(): List<Source>
}

class DefaultInternalSourceRegistry(
    private val sourcesList: List<Source> = emptyList(),
) : InternalSourceRegistry {
    override fun getSources(): List<Source> = sourcesList
}
