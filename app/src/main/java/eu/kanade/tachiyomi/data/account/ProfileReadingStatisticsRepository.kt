package eu.kanade.tachiyomi.data.account

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import tachiyomi.core.common.util.lang.LocalAccountScope
import tachiyomi.data.Database

/** Counts are current marked-read records (including manual marks), never an XP/event counter. */
internal data class ProfileReadingTotals(val comics: Long? = null, val novels: Long? = null) {
    init { require(comics == null || comics >= 0); require(novels == null || novels >= 0) }
    val total: Long? get() = if (comics != null && novels != null && comics <= Long.MAX_VALUE - novels) comics + novels else null
}

internal data class ProfileReadAggregate(val comics: Long, val invalid: Long, val library: Long)

internal enum class ProfileReadingCompleteness { LOADING, PARTIAL, COMPLETE, UNAVAILABLE }

internal data class ProfileReadingSnapshot(
    val token: LocalAccountScope.Token? = null,
    val totals: ProfileReadingTotals = ProfileReadingTotals(),
    val library: Long? = null,
    val loading: Boolean = true,
) {
    val completeness: ProfileReadingCompleteness get() = when {
        loading -> ProfileReadingCompleteness.LOADING
        totals.total != null -> ProfileReadingCompleteness.COMPLETE
        totals.comics != null || totals.novels != null -> ProfileReadingCompleteness.PARTIAL
        else -> ProfileReadingCompleteness.UNAVAILABLE
    }
    // Applied at rendering time too: collectLatest cancellation alone cannot retract a cached frame.
    fun visibleFor(owner: String, workspace: LocalAccountScope.State): ProfileReadingSnapshot =
        if (workspace.ready && workspace.token.owner == owner && token == workspace.token) this
        else ProfileReadingSnapshot()
}

/** Owner-only local projection. No auth mutations, remote history reads, or new storage. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ProfileReadingStatisticsRepository(
    private val aggregate: (String) -> Flow<ProfileReadAggregate?>,
    private val workspace: StateFlow<LocalAccountScope.State> = LocalAccountScope.changes,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(
        database: Database,
        workspace: StateFlow<LocalAccountScope.State> = LocalAccountScope.changes,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this({ owner ->
        database.profileReadingQueries.readStatistics(owner).asFlow().mapToOneOrNull(dispatcher).map { row ->
            row?.let { ProfileReadAggregate(it.comic_count, it.invalid_count, it.library_count) }
        }
    }, workspace, dispatcher)

    fun observe(owner: String): Flow<ProfileReadingSnapshot> = workspace.flatMapLatest { state ->
        if (!state.ready || state.token.owner != owner) flowOf(ProfileReadingSnapshot())
        else aggregate(owner).map { counts ->
            ProfileReadingSnapshot(
                token = state.token,
                // NovelReadingPosition is not completion evidence. Unknown is deliberately not zero.
                totals = ProfileReadingTotals(comics = counts?.takeIf { it.invalid == 0L }?.comics),
                library = counts?.library,
                loading = false,
            )
        }.onStart { emit(ProfileReadingSnapshot(token = state.token)) }
            .catch { failure ->
                if (failure is CancellationException) throw failure
                emit(ProfileReadingSnapshot(token = state.token, loading = false))
            }
    }.distinctUntilChanged().flowOn(dispatcher)
}
