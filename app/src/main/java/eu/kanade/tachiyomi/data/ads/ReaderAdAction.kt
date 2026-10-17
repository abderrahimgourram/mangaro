package eu.kanade.tachiyomi.data.ads

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import tachiyomi.core.common.util.lang.LocalAccountScope as Account

/** Only readiness is awaited; opening still uses the existing owned, validated browser flow. */
internal suspend fun awaitReaderAdAccount(
    owner: String,
    states: StateFlow<Account.State> = Account.changes,
): Account.Token = withTimeout(5_000) {
    states.first { it.ready }.token.also {
        if (SmartLinkController.owner(it) != owner) throw Account.Changed()
    }
}
