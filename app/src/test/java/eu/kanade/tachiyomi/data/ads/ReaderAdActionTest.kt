package eu.kanade.tachiyomi.data.ads

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.util.lang.LocalAccountScope as Account

class ReaderAdActionTest {
    @Test fun readyOwnerCanUseTheExistingBrowserLaunchFlowImmediately() = runTest {
        val token = Account.Token("fixture-a", 1)
        val states = MutableStateFlow(Account.State(token))
        assertEquals(token, awaitReaderAdAccount(SmartLinkController.owner(token), states))
        assertEquals("https://uplcm.com/4/11968619", AdsterraSmartLinkConfig.SMARTLINK_URL)
        assertEquals(AdsterraSmartLinkConfig.SMARTLINK_URL, SmartLinkConfiguration.URL)
    }

    @Test fun delayedWorkspaceIsBoundedAndCanBeRetriedWithoutGrantingAnAdStep() = runTest {
        val token = Account.Token("fixture-a", 1)
        val states = MutableStateFlow(Account.State(token, ready = false))
        val result = async { runCatching { awaitReaderAdAccount(SmartLinkController.owner(token), states) } }
        testScheduler.advanceUntilIdle()
        assertTrue(result.await().exceptionOrNull() is TimeoutCancellationException)
        states.value = Account.State(token)
        assertEquals(token, awaitReaderAdAccount(SmartLinkController.owner(token), states))
    }

    @Test fun aDifferentAccountCannotUseThePreviousOwnersAdvertisingButton() = runTest {
        val a = Account.Token("fixture-a", 1)
        val b = Account.Token("fixture-b", 2)
        val failure = runCatching {
            awaitReaderAdAccount(SmartLinkController.owner(a), MutableStateFlow(Account.State(b)))
        }.exceptionOrNull()
        assertTrue(failure is Account.Changed)
    }
}
