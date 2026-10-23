package eu.kanade.tachiyomi.data.stories

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.service.SourceWorkReference
import tachiyomi.domain.manga.service.WorkMetadata

class ReviewedStoryIdentityTest {
    private val graph get() = StoryFixtures.registry
    private val relation get() = graph.records.single { it.id == "return-mount-hua-original" }
    private fun edition() = StoryFixtures.comic(
        StoryEditionRef(StoryFormat.MANHWA, "7657007209499352344", "649313"),
    ).copy(title = "Return of the Mount Hua Sect", author = "Biga")
    private fun metadata() = edition().let {
        WorkMetadata(
            SourceWorkReference(it.source, it.id, it.url),
            it.title,
            aliases = setOf("Return of the Blossoming Blade", "화산귀환"),
            author = it.author,
            format = "manhwa",
        )
    }
    private fun candidate(work: WorkMetadata = metadata()) = StoryCandidate(
        StoryDestination.Comic(edition()),
        externalIds = work.externalIds,
        reviewedComicKey = work.reviewedWorkKey,
        comicMetadata = work,
    )

    @Test fun `real release source metadata identifies unenrolled edition without adding a route or rewriting IDs`() {
        graph.relationship(edition().let { StoryDestination.Comic(it).edition }) shouldBe null
        graph.relationship(candidate()) shouldBe relation
        metadata().externalIds shouldBe emptyMap()
        metadata().reviewedWorkKey shouldBe null
        edition().url shouldBe "649313"
    }

    @Test fun `single title plus creator is only a metadata request cue and never a relationship`() {
        val old = candidate(metadata().copy(aliases = emptySet(), format = null))
        graph.relationship(old) shouldBe null
        graph.needsOriginMetadata(old) shouldBe true
        graph.needsOriginMetadata(candidate()) shouldBe false
    }

    @Test fun `original name translated name named creator and explicit medium are all required`() {
        listOf(
            metadata().copy(aliases = setOf("Return of the Blossoming Blade")),
            metadata().copy(title = "화산귀환", aliases = emptySet()),
            metadata().copy(author = null),
            metadata().copy(author = "Unknown"),
            metadata().copy(author = "Another author"),
            metadata().copy(format = null),
            metadata().copy(format = "novel"),
            metadata().copy(edition = "sequel"),
        ).forEach { graph.relationship(candidate(it)) shouldBe null }
    }

    @Test fun `sequel main title cannot inherit original aliases and conflicting IDs veto membership`() {
        graph.relationship(candidate(metadata().copy(title = "Return of the Mount Hua Sect Ragnarok"))) shouldBe null
        graph.relationship(candidate(metadata().copy(externalIds = mapOf("anilist" to "12345")))) shouldBe null
        graph.relationship(candidate(metadata().copy(reviewedWorkKey = "a".repeat(64)))) shouldBe null
    }

    @Test fun `proof cannot be borrowed from another edition source or local row`() {
        listOf(
            metadata().reference.copy(sourceId = 123),
            metadata().reference.copy(url = "999999"),
            metadata().reference.copy(mangaId = 99),
        ).forEach { graph.relationship(candidate(metadata().copy(reference = it))) shouldBe null }
    }

    @Test fun `revocation ineligibility and format conflicts still fail closed`() {
        StoryFixtures.state("REVOKED").relationship(candidate()) shouldBe null
        StoryFixtures.state("CONFLICTED").relationship(candidate()) shouldBe null
        graph.relationship(candidate().copy(eligible = false)) shouldBe null
        graph.relationship(candidate().copy(formatCompatible = false)) shouldBe null
    }

    @Test fun `publisher profile matching is general and never hardcodes a story or numeric source route`() {
        val profile =
            ReviewedStoryIdentity(setOf("原作名"), setOf("The Genuine Work"), setOf("Named Writer"), setOf("manhua"))
        val work = metadata().copy(
            title = "The Genuine Work",
            aliases = setOf("原作名"),
            author = "Named Writer",
            format = "manhua",
        )
        profile.matches(work) shouldBe true
        profile.matches(work.copy(aliases = emptySet())) shouldBe false
        profile.matches(work.copy(title = "The Genuine Work II")) shouldBe false
    }
}
