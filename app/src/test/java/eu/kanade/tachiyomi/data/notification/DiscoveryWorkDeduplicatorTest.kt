package eu.kanade.tachiyomi.data.notification

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DiscoveryWorkDeduplicatorTest {

    private val context = mockk<Context>(relaxed = true)
    private val prefs = mockk<SharedPreferences>(relaxed = true)
    private val editor = mockk<SharedPreferences.Editor>(relaxed = true)
    private val storedSet = mutableSetOf<String>()
    private var baselineStored = false

    @BeforeEach
    fun setUp() {
        storedSet.clear()
        baselineStored = false

        every { context.applicationContext } returns context
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { prefs.getStringSet(any(), any()) } answers { storedSet.toSet() }
        every { prefs.getBoolean(any(), any()) } answers { baselineStored }
        every { prefs.edit() } returns editor

        val setSlot = slot<Set<String>>()
        every { editor.putStringSet(any(), capture(setSlot)) } answers {
            storedSet.clear()
            storedSet.addAll(setSlot.captured)
            editor
        }

        val boolSlot = slot<Boolean>()
        every { editor.putBoolean(any(), capture(boolSlot)) } answers {
            baselineStored = boolSlot.captured
            editor
        }

        every { editor.apply() } returns Unit

        DiscoveryWorkDeduplicator.clearForTesting()
        DiscoveryWorkDeduplicator.notificationPoster = { _, _ -> }
    }

    @Test
    fun `initial discovery establishes baseline without notifying`() {
        val initialCatalog = listOf(
            1L to "https://source.com/work/1",
            1L to "https://source.com/work/2",
            1L to "https://source.com/work/3",
        )

        val newCount = DiscoveryWorkDeduplicator.processDiscoveredWorks(context, initialCatalog)
        newCount shouldBe 0
    }

    @Test
    fun `subsequent discovery of genuinely new works reports correct count`() {
        val initialCatalog = listOf(
            1L to "https://source.com/work/1",
            1L to "https://source.com/work/2",
        )
        DiscoveryWorkDeduplicator.processDiscoveredWorks(context, initialCatalog)

        val updatedCatalog = listOf(
            1L to "https://source.com/work/1",
            1L to "https://source.com/work/2",
            1L to "https://source.com/work/3",
            1L to "https://source.com/work/4",
            1L to "https://source.com/work/5",
        )

        val newCount = DiscoveryWorkDeduplicator.processDiscoveredWorks(context, updatedCatalog)
        newCount shouldBe 3
    }

    @Test
    fun `repeated discovery of existing works produces zero new count`() {
        val initialCatalog = listOf(
            1L to "https://source.com/work/1",
            1L to "https://source.com/work/2",
        )
        DiscoveryWorkDeduplicator.processDiscoveredWorks(context, initialCatalog)

        val newCountPass1 = DiscoveryWorkDeduplicator.processDiscoveredWorks(context, listOf(1L to "https://source.com/work/3"))
        newCountPass1 shouldBe 1

        val newCountPass2 = DiscoveryWorkDeduplicator.processDiscoveredWorks(context, listOf(1L to "https://source.com/work/3"))
        newCountPass2 shouldBe 0
    }
}
