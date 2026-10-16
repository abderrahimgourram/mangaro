package eu.kanade.tachiyomi.ui.home

import kotlinx.coroutines.withContext
import tachiyomi.core.common.preference.AccountPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.LocalAccountScope
import tachiyomi.domain.manga.service.VerifiedCatalogIdentity
import java.security.MessageDigest
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Explicit manual choices only. Automatic representative scoring never writes personal preferences. */
internal class CatalogEditionPreferences(base: PreferenceStore) {
    private val personal = AccountPreferenceStore(base)
    private fun key(group: HomeDiscoveryItem): String = "__PRIVATE_catalog_preferred_edition_" + MessageDigest.getInstance("SHA-256")
        .digest(GroupDiscoveryItems.presentationKey(group).toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun members(group: HomeDiscoveryItem) = listOf(group) + group.alternatives
    suspend fun remember(group: HomeDiscoveryItem, selectedId: Long, owner: LocalAccountScope.Token = LocalAccountScope.capture()) = withContext(LocalAccountScope.Element(owner)) {
        LocalAccountScope.check(owner)
        val selected = members(group).firstOrNull { it.mangaId == selectedId } ?: return@withContext
        val identity = VerifiedCatalogIdentity.from(PreferredMangaVariants.work(selected)).editionKey
        personal.getString(key(group)).set(identity)
    }
    fun selected(group: HomeDiscoveryItem): HomeDiscoveryItem? {
        val saved = personal.getString(key(group)).get().takeIf { it.isNotEmpty() } ?: return null
        return members(group).firstOrNull {
            PreferredMangaVariants.usable(it.sourceId,it.mangaId) &&
                VerifiedCatalogIdentity.from(PreferredMangaVariants.work(it)).editionKey == saved
        }
    }
}

internal object CatalogEditionOpening {
    fun preferred(group: HomeDiscoveryItem): HomeDiscoveryItem? =
        runCatching { CatalogEditionPreferences(Injekt.get()).selected(group) }.getOrNull()
    fun selected(group: HomeDiscoveryItem): HomeDiscoveryItem = preferred(group) ?: group
    suspend fun remember(group: HomeDiscoveryItem, selectedId: Long, owner: LocalAccountScope.Token) =
        CatalogEditionPreferences(Injekt.get()).remember(group, selectedId, owner)
}
