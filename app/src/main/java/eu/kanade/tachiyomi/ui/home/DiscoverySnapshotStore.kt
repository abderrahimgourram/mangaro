package eu.kanade.tachiyomi.ui.home

import mihon.domain.source.discovery.model.DiscoveryCategory
import java.util.concurrent.ConcurrentHashMap

object DiscoverySnapshotStore {

    private val snapshotMap = ConcurrentHashMap<DiscoveryCategory, List<HomeDiscoveryItem>>()

    fun setSnapshot(category: DiscoveryCategory, items: List<HomeDiscoveryItem>) {
        if (items.isNotEmpty()) {
            snapshotMap[category] = items.toList()
        }
    }

    fun getSnapshot(category: DiscoveryCategory): List<HomeDiscoveryItem> {
        // Already grouped when published. Do not redo identity linking on the UI thread.
        return snapshotMap[category].orEmpty().distinctBy(GroupDiscoveryItems::presentationKey)
    }

    fun clear() {
        snapshotMap.clear()
    }
}
