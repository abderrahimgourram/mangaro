package mihon.domain.source.discovery.interactor

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.domain.source.discovery.model.CapabilitySupport
import mihon.domain.source.discovery.model.DiscoveryCategory
import mihon.domain.source.discovery.model.SourceDiscoveryItem
import mihon.domain.source.discovery.model.SourceDiscoveryResult

class GetSourceDiscovery(
    private val getSourceCapabilities: GetSourceCapabilities = GetSourceCapabilities(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend operator fun invoke(
        source: CatalogueSource,
        category: DiscoveryCategory,
        page: Int = 1,
    ): SourceDiscoveryResult = withContext(ioDispatcher) {
        val capabilities = getSourceCapabilities(source)

        val mangasPage: MangasPage = try {
            when (category) {
                DiscoveryCategory.POPULAR -> {
                    source.getPopularManga(page)
                }
                DiscoveryCategory.LATEST -> {
                    if (capabilities.supportsLatest == CapabilitySupport.SUPPORTED) {
                        source.getLatestUpdates(page)
                    } else {
                        return@withContext SourceDiscoveryResult(
                            sourceId = source.id,
                            sourceName = source.name,
                            category = category,
                            page = page,
                            hasNextPage = false,
                            items = emptyList(),
                        )
                    }
                }
                DiscoveryCategory.COMPLETED -> {
                    if (capabilities.supportsCompletedFilter == CapabilitySupport.SUPPORTED) {
                        val filterList = prepareCompletedFilter(source)
                        source.getSearchManga(page, query = "", filters = filterList)
                    } else {
                        return@withContext SourceDiscoveryResult(
                            sourceId = source.id,
                            sourceName = source.name,
                            category = category,
                            page = page,
                            hasNextPage = false,
                            items = emptyList(),
                        )
                    }
                }
                DiscoveryCategory.NEW -> {
                    return@withContext SourceDiscoveryResult(
                        sourceId = source.id,
                        sourceName = source.name,
                        category = category,
                        page = page,
                        hasNextPage = false,
                        items = emptyList(),
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@withContext SourceDiscoveryResult(
                sourceId = source.id,
                sourceName = source.name,
                category = category,
                page = page,
                hasNextPage = false,
                items = emptyList(),
            )
        }

        val items = mangasPage.mangas.map { sManga ->
            sManga.toSourceDiscoveryItem(source.id, source.name)
        }

        SourceDiscoveryResult(
            sourceId = source.id,
            sourceName = source.name,
            category = category,
            page = page,
            hasNextPage = mangasPage.hasNextPage,
            items = items,
        )
    }

    private fun prepareCompletedFilter(source: CatalogueSource): FilterList {
        val filters = try {
            source.getFilterList()
        } catch (_: Exception) {
            return FilterList()
        }

        fun applyFilter(filter: Filter<*>): Boolean {
            if (filter is Filter.Select<*>) {
                val index = filter.values.indexOfFirst { value ->
                    val text = value.toString().lowercase()
                    text.contains("completed") ||
                        text.contains("مكتمل") ||
                        text.contains("مكتملة") ||
                        text.contains("finish")
                }
                if (index >= 0) {
                    filter.state = index
                    return true
                }
            } else if (filter is Filter.Group<*>) {
                for (item in filter.state) {
                    if (item is Filter<*> && applyFilter(item)) {
                        return true
                    }
                }
            }
            return false
        }

        for (filter in filters) {
            if (applyFilter(filter)) break
        }

        return filters
    }

    private fun SManga.toSourceDiscoveryItem(sourceId: Long, sourceName: String): SourceDiscoveryItem {
        return SourceDiscoveryItem(
            sourceId = sourceId,
            sourceName = sourceName,
            url = url,
            title = title,
            thumbnailUrl = thumbnail_url,
            artist = artist,
            author = author,
            description = description,
            status = status,
            genre = getGenres() ?: emptyList(),
            localMangaId = null,
        )
    }
}
