package eu.kanade.tachiyomi.data.ads

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tachiyomi.core.common.util.lang.LocalAccountScope as Account

class ReaderAdActionTest {
    @Test fun readyOwnerCanUseTheExistingBrowserLaunchFlowImmediately() = runTest {
        val token = Account.Token("fixture-a", 1)
        val states = MutableStateFlow(Account.State(token))
        assertEquals(token, awaitReaderAdAccount(SmartLinkController.owner(token), states))
        assertEquals("https://asiafilm.org/4/f6ae7ab4f2990fc7f8c59d3bff6a4431", AdsterraSmartLinkConfig.SMARTLINK_URL)
        assertEquals(AdsterraSmartLinkConfig.SMARTLINK_URL, SmartLinkConfiguration.URL)
    }
    @Test fun delayedWorkspaceIsBoundedAndCanBeRetriedWithoutGrantingAnAdStep() = runTest {
        val token = Account.Token("fixture-a", 1)
        val states = MutableStateFlow(Account.State(token, ready=false))
        val result = async { runCatching { awaitReaderAdAccount(SmartLinkController.owner(token), states) } }
        testScheduler.advanceUntilIdle()
        assertTrue(result.await().exceptionOrNull() is TimeoutCancellationException)
        states.value = Account.State(token)
        assertEquals(token, awaitReaderAdAccount(SmartLinkController.owner(token), states))
    }
    @Test fun aDifferentAccountCannotUseThePreviousOwnersAdvertisingButton() = runTest {
        val a = Account.Token("fixture-a", 1); val b = Account.Token("fixture-b", 2)
        val failure = runCatching { awaitReaderAdAccount(SmartLinkController.owner(a), MutableStateFlow(Account.State(b))) }.exceptionOrNull()
        assertTrue(failure is Account.Changed)
    }
}
