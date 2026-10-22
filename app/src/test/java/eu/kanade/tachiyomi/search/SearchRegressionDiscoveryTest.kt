package eu.kanade.tachiyomi.search

import eu.kanade.tachiyomi.data.stories.ConnectedStoriesAlternateIdentityTest
import eu.kanade.tachiyomi.source.internal.teamx.TeamXRecordedIndexTest
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SearchRegressionDiscoveryTest {
    @Test fun `new regression methods have JUnit discoverable void return types`() {
        listOf(
            SearchChapterVerificationRegressionTest::class.java,
            TeamXRecordedIndexTest::class.java,
            ConnectedStoriesAlternateIdentityTest::class.java,
            SearchSourceLiveContractTest::class.java,
        ).forEach { type ->
            type.declaredMethods.filter { it.isAnnotationPresent(Test::class.java) }.forEach { method ->
                method.returnType shouldBe Void.TYPE
            }
        }
    }
}
