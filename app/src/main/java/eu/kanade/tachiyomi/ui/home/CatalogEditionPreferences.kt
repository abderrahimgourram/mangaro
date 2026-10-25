package eu.kanade.tachiyomi.ui.home

import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import tachiyomi.core.common.preference.AccountPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.LocalAccountScope
import tachiyomi.domain.manga.service.VerifiedCatalogIdentity
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.security.MessageDigest

/** Explicit manual choices only. Automatic representative scoring never writes personal preferences. */
internal class CatalogEditionPreferences(base: PreferenceStore) {
    private val personal = AccountPreferenceStore(base)
    private fun key(group: HomeDiscoveryItem) = key(GroupDiscoveryItems.presentationKey(group))
    private fun key(presentation: String): String =
        "__PRIVATE_catalog_preferred_edition_" + MessageDigest.getInstance("SHA-256")
            .digest(presentation.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun members(group: HomeDiscoveryItem) = listOf(group) + group.alternatives
    suspend fun remember(
        group: HomeDiscoveryItem,
        selectedId: Long,
        owner: LocalAccountScope.Token = LocalAccountScope.capture(),
    ) = withContext(LocalAccountScope.Element(owner)) {
        LocalAccountScope.check(owner)
        val selected = members(group).firstOrNull { it.mangaId == selectedId } ?: return@withContext
        val identity = VerifiedCatalogIdentity.from(PreferredMangaVariants.work(selected)).editionKey
        personal.getString(key(group)).set(identity)
        PreferredMangaVariants.changes.update { it + 1 }
    }
    fun selected(group: HomeDiscoveryItem): HomeDiscoveryItem? {
        val editions = members(group)
        fun find(saved: String): HomeDiscoveryItem? {
            // Empty preferences cannot match a source-qualified edition key. Avoid the
            // repeated work/title projection for every absent current or legacy preference.
            if (saved.isBlank()) return null
            return editions.firstOrNull {
                PreferredMangaVariants.usable(it.sourceId, it.mangaId) &&
                    VerifiedCatalogIdentity.from(PreferredMangaVariants.work(it)).editionKey == saved
            }
        }
        find(personal.getString(key(group)).get())?.let { return it }
        // A newly reviewed group may replace old standalone/provider presentation keys.
        // Read the original account's explicit preferences without migrating or rewriting them.
        val legacy = editions.take(64).flatMap { member ->
            val work = PreferredMangaVariants.work(member)
            // A reversible merge can replace a previously reviewed group's display key too.
            // Read both former roots without migrating preferences or changing edition identity.
            listOf(work, CatalogWorkEvidence.withoutBundledReview(work)).distinct().map { identity ->
                VerifiedCatalogIdentity.key(listOf(VerifiedCatalogIdentity.from(identity)))
            }
        }.distinct().mapNotNull { find(personal.getString(key(it)).get()) }.distinctBy { it.sourceId to it.mangaId }
        return legacy.singleOrNull() // Conflicting old choices require an explicit new selection.
    }
}

internal object CatalogEditionOpening {
    fun preferred(group: HomeDiscoveryItem): HomeDiscoveryItem? =
        runCatching { CatalogEditionPreferences(Injekt.get()).selected(group) }.getOrNull()
    fun selected(group: HomeDiscoveryItem): HomeDiscoveryItem = preferred(group) ?: group

    /** Resolve the explicit preference during presentation, never during click dispatch. */
    fun forDisplay(group: HomeDiscoveryItem): HomeDiscoveryItem {
        val selected = preferred(group)?.takeIf { PreferredMangaVariants.sourceEnabled(it.sourceId) } ?: return group
        val members = listOf(group) + group.alternatives
        return selected.copy(
            canonicalWorkId = group.canonicalWorkId,
            availableVersions = group.availableVersions,
            actualChapterCount = PreferredMangaVariants.actualChapterCount(selected),
            chapterPublishedAt = group.chapterPublishedAt,
            alternatives = members.filterNot { it.sourceId == selected.sourceId && it.mangaId == selected.mangaId },
        )
    }
    suspend fun remember(group: HomeDiscoveryItem, selectedId: Long, owner: LocalAccountScope.Token) =
        CatalogEditionPreferences(Injekt.get()).remember(group, selectedId, owner)
}
