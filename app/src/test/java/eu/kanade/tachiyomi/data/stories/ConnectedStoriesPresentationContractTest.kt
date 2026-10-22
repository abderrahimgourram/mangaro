package eu.kanade.tachiyomi.data.stories

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/** Static production-wiring checks supplement real repository/Voyager tests, not device screenshots. */
class ConnectedStoriesPresentationContractTest {
    private fun source(path: String) = File(StoryFixtures.root, "app/src/main/java/$path").readText()

    @Test fun `phone tablet and novel details render the shared optional component`() {
        val comic = source("eu/kanade/presentation/manga/MangaScreen.kt")
        comic.split("eu.kanade.presentation.stories.ConnectedStoriesSection(").size - 1 shouldBe 2
        val novel = source("eu/kanade/presentation/novels/NovelScreens.kt")
        novel.split("eu.kanade.presentation.stories.ConnectedStoriesSection(").size - 1 shouldBe 1
        novel.contains("StoryDestination.Prose(novel)") shouldBe true
        novel.contains("if (work.editions.size > 1)") shouldBe true
        novel.contains("chooseEdition(edition)") shouldBe true
    }

    @Test fun `minimal card displays only required heading title cover and arrow`() {
        val card = source("eu/kanade/presentation/stories/ConnectedStoryCard.kt")
        listOf(
            "Text(model.heading",
            "Text(model.selected.title",
            "NovelCover(target.novel",
            "MangaCover.Book(target.manga",
            "LayoutDirection.Rtl",
            "textDirection = TextDirection.ContentOrRtl",
            "Icons.AutoMirrored.Outlined.ArrowForward",
        ).forEach {
            card.contains(it) shouldBe
                true
        }
        listOf("evidence", "relationshipId", "confidence", "source.name", "alternatives.size").forEach {
            card.contains("model.$it") shouldBe
                false
        }
        card.contains("TextButton") shouldBe false
        val integration = source("eu/kanade/presentation/stories/ConnectedStoriesSection.kt")
        integration.contains("collectAsStateWithLifecycle") shouldBe true
        integration.replace(Regex("\\s+"), "").contains("remember(repository,origin,workspace.token)") shouldBe true
        integration.contains("openReader(") shouldBe false
    }
}
