package eu.kanade.tachiyomi.data.stories

import cafe.adriel.voyager.core.annotation.InternalVoyagerApi
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.NavigatorDisposeBehavior
import eu.kanade.presentation.novels.NovelDetailsScreen
import eu.kanade.presentation.stories.openConnectedStory
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import tachiyomi.core.common.util.lang.LocalAccountScope as Local

/** Real Voyager stack and production navigation handler, without composing device screens. */
@OptIn(InternalVoyagerApi::class)
class ConnectedStoryNavigationTest {
    private val token=Local.Token("guest",1)
    private fun navigator() = Navigator(listOf(MangaScreen(99)),"connected-test",mockk(relaxed=true),NavigatorDisposeBehavior())
    private fun model(toNovel: Boolean): ConnectedStoryUiModel = ConnectedStoryResolver.resolve(StoryFixtures.registry,
        if(toNovel) StoryFixtures.comics().first() else StoryFixtures.novels().single(),
        if(toNovel) StoryFixtures.novels() else StoryFixtures.comics(),token)!!

    @Test fun `manhwa to novel uses exact existing details destination with back and double tap protection`() {
        val nav=navigator();val origin=nav.lastItem;val model=model(true)
        openConnectedStory(nav,origin.key,model,StoryFixtures.registry,Local.State(token)) { true } shouldBe true
        nav.items.size shouldBe 2
        val target=nav.lastItem as NovelDetailsScreen
        val field=NovelDetailsScreen::class.java.getDeclaredField("initial").apply { isAccessible=true }
        field.get(target) shouldBe (model.selected as StoryDestination.Prose).novel
        openConnectedStory(nav,origin.key,model,StoryFixtures.registry,Local.State(token)) { true } shouldBe false
        nav.items.size shouldBe 2
        nav.pop() shouldBe true
        nav.lastItem shouldBe origin
        openConnectedStory(nav,origin.key,model,StoryFixtures.registry,Local.State(token)) { true } shouldBe true
    }
    @Test fun `reverse route and every eligible selected edition preserve their SQL IDs`() {
        val base=model(false)
        for(edition in base.alternatives) {
            val nav=navigator();val origin=nav.lastItem
            val model=base.copy(selected=edition)
            openConnectedStory(nav,origin.key,model,StoryFixtures.registry,Local.State(token)) { true } shouldBe true
            (nav.lastItem as MangaScreen).mangaId shouldBe (edition as StoryDestination.Comic).manga.id
            nav.pop() shouldBe true
            nav.lastItem shouldBe origin
        }
    }
    @Test fun `revoked stale account or unavailable target cannot navigate`() {
        val nav=navigator();val origin=nav.lastItem;val model=model(true)
        openConnectedStory(nav,origin.key,model,StoryFixtures.state("REVOKED"),Local.State(token)) { true } shouldBe false
        openConnectedStory(nav,origin.key,model,StoryFixtures.registry,Local.State(Local.Token("b",2))) { true } shouldBe false
        openConnectedStory(nav,origin.key,model,StoryFixtures.registry,Local.State(token)) { false } shouldBe false
        nav.items.size shouldBe 1
    }
}
