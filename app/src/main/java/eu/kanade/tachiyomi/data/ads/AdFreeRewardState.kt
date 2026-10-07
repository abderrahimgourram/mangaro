package eu.kanade.tachiyomi.data.ads

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A future provider may report only server/provider-verified completion events. */
interface RewardCompletionProvider {
    fun setVerifiedRewardCompletedListener(listener: () -> Unit)
}

/** Shared ad-free timer and verified 5-completion progress; no provider is active today. */
class AdFreeRewardState private constructor(context: Context) {
    companion object {
        private const val REQUIRED_COMPLETIONS = 5
        private const val SESSION_MS = 30 * 60_000L
        private const val PREFS = "mangaro_ads_policy"
        private const val COMPLETIONS = "verified_reward_completions"
        private const val ACTIVE_UNTIL = "ad_free_until"
        @Volatile private var instance: AdFreeRewardState? = null
        fun get(context: Context): AdFreeRewardState = instance ?: synchronized(this) {
            instance ?: AdFreeRewardState(context.applicationContext).also { instance = it }
        }
    }

    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutableCompletions = MutableStateFlow(preferences.getInt(COMPLETIONS, 0).coerceIn(0, REQUIRED_COMPLETIONS))
    private val mutableActiveUntil = MutableStateFlow(
        preferences.getLong(ACTIVE_UNTIL, 0L).coerceAtMost(System.currentTimeMillis() + SESSION_MS),
    )
    val verifiedCompletions = mutableCompletions.asStateFlow()
    val activeUntil = mutableActiveUntil.asStateFlow()
    private val expiryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var expiryJob: Job? = null

    init { scheduleExpiry() }

    private fun scheduleExpiry() {
        expiryJob?.cancel()
        if (mutableActiveUntil.value <= 0L) return
        expiryJob = expiryScope.launch {
            while (true) {
                val remaining = mutableActiveUntil.value - System.currentTimeMillis()
                if (remaining <= 0L) {
                    isActive()
                    break
                }
                delay(remaining)
            }
        }
    }

    fun connect(provider: RewardCompletionProvider) {
        provider.setVerifiedRewardCompletedListener(::recordVerifiedCompletion)
    }

    @Synchronized
    fun isActive(now: Long = System.currentTimeMillis()): Boolean {
        val active = now < mutableActiveUntil.value
        if (!active && (mutableActiveUntil.value != 0L || mutableCompletions.value == REQUIRED_COMPLETIONS)) {
            mutableActiveUntil.value = 0L
            mutableCompletions.value = 0
            preferences.edit().putLong(ACTIVE_UNTIL, 0L).putInt(COMPLETIONS, 0).apply()
        }
        return active
    }

    @Synchronized
    private fun recordVerifiedCompletion() {
        // Already-earned progress must not extend the same 30-minute reward repeatedly.
        if (isActive()) return
        val next = (mutableCompletions.value + 1).coerceAtMost(REQUIRED_COMPLETIONS)
        if (next < REQUIRED_COMPLETIONS) {
            mutableCompletions.value = next
            preferences.edit().putInt(COMPLETIONS, next).apply()
        } else {
            val expiration = System.currentTimeMillis() + SESSION_MS
            mutableCompletions.value = REQUIRED_COMPLETIONS
            mutableActiveUntil.value = expiration
            preferences.edit().putInt(COMPLETIONS, REQUIRED_COMPLETIONS).putLong(ACTIVE_UNTIL, expiration).apply()
            scheduleExpiry()
        }
    }
}
