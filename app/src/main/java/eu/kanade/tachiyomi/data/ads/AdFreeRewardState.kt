package eu.kanade.tachiyomi.data.ads

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    private val mutableCompletions = MutableStateFlow(preferences.getInt(COMPLETIONS, 0).coerceIn(0, REQUIRED_COMPLETIONS - 1))
    private val mutableActiveUntil = MutableStateFlow(
        preferences.getLong(ACTIVE_UNTIL, 0L).coerceAtMost(System.currentTimeMillis() + SESSION_MS),
    )
    val verifiedCompletions = mutableCompletions.asStateFlow()
    val activeUntil = mutableActiveUntil.asStateFlow()

    fun connect(provider: RewardCompletionProvider) {
        provider.setVerifiedRewardCompletedListener(::recordVerifiedCompletion)
    }

    fun isActive(now: Long = System.currentTimeMillis()): Boolean = now < mutableActiveUntil.value

    @Synchronized
    private fun recordVerifiedCompletion() {
        val next = mutableCompletions.value + 1
        if (next < REQUIRED_COMPLETIONS) {
            mutableCompletions.value = next
            preferences.edit().putInt(COMPLETIONS, next).apply()
        } else {
            val expiration = System.currentTimeMillis() + SESSION_MS
            mutableCompletions.value = 0
            mutableActiveUntil.value = expiration
            preferences.edit().putInt(COMPLETIONS, 0).putLong(ACTIVE_UNTIL, expiration).apply()
        }
    }
}
