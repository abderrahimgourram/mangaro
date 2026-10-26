package eu.kanade.tachiyomi.ui.home

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.LocalAccountScope
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import java.util.UUID

class CatalogPreferenceProjectionCostTest {
    @Test
    fun `snapshot filter preserves account pins and local routes`() = runBlocking<Unit> {
        val oldOwner = LocalAccountScope.capture().owner
        val owner = UUID.randomUUID().toString()
        val prefs = mockk<PreferenceStore>()
        val values = mutableMapOf<String, InMemoryPreference<String>>()
        every { prefs.getString(any(), any()) } answers {
            values.getOrPut(firstArg()) { InMemoryPreference(firstArg(), null, secondArg()) }
        }
        every { prefs.getAll() } answers { values.filterValues { it.isSet() }.mapValues { it.value.get() } }
        try {
            LocalAccountScope.commit(owner)
            for (route in listOf("/series/a-public-work#native-id", "")) {
                fun card(id: Long): HomeDiscoveryItem {
                    val manga = Manga.create().copy(id = id, source = id, url = route, title = "A Public Work")
                    return HomeDiscoveryItem(id, manga.title, manga.asMangaCover(), id, "Fixture", route)
                }
                val pinned = card(99888203)
                val group = card(
                    99888204,
                ).copy(canonicalWorkId = "presentation:fixture:$route", alternatives = listOf(pinned))
                val choices = CatalogEditionPreferences(prefs)
                choices.remember(group, pinned.mangaId)
                val before = prefs.getAll()
                choices.selected(group)!!.mangaId shouldBe pinned.mangaId
                LocalAccountScope.commit(UUID.randomUUID().toString())
                choices.selected(group) shouldBe null
                LocalAccountScope.commit(owner)
                choices.selected(group)!!.url shouldBe pinned.url
                prefs.getAll() shouldBe before
            }
        } finally {
            LocalAccountScope.commit(oldOwner)
        }
    }

    @Test
    fun `unselected shelves never enrich metadata to search nonexistent legacy choices`() {
        for (snapshot in listOf(emptyMap(), mapOf("__PRIVATE_catalog_preferred_edition_other" to "another-edition"))) {
            val prefs = mockk<PreferenceStore>()
            every { prefs.getAll() } returns snapshot
            every { prefs.getString(any(), any()) } answers {
                InMemoryPreference(firstArg(), null, secondArg<String>())
            }
            val manga = Manga.create().copy(
                id = 99888201,
                source = 99888202,
                url = "/series/a-public-work",
                title = "A Public Work",
            )
            val group =
                HomeDiscoveryItem(manga.id, manga.title, manga.asMangaCover(), manga.source, "Fixture", manga.url)
            mockkObject(CatalogWorkEvidence)
            try {
                CatalogEditionPreferences(prefs).selected(group) shouldBe null
                verify(exactly = 0) { CatalogWorkEvidence.resolve(any()) }
            } finally {
                unmockkObject(CatalogWorkEvidence)
            }
        }
    }
}
