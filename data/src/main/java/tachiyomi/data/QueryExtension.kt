package tachiyomi.data

import app.cash.sqldelight.Query
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import app.cash.sqldelight.coroutines.mapToOneOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import tachiyomi.core.common.util.lang.LocalAccountScope
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Recreate the query subscription per workspace; a stale query cannot emit into the next one. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
fun <T : Any> Query<T>.subscribeToList(context: CoroutineContext = EmptyCoroutineContext): Flow<List<T>> =
    LocalAccountScope.changes.flatMapLatest { state ->
        if (!state.ready) flowOf(emptyList()) else asFlow().mapToList(context + LocalAccountScope.Element(state.token))
            .onEach { LocalAccountScope.check(state.token) }
    }

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
fun <T : Any> Query<T>.subscribeToOne(context: CoroutineContext = EmptyCoroutineContext): Flow<T> =
    LocalAccountScope.changes.flatMapLatest { state ->
        if (!state.ready) emptyFlow() else asFlow().mapToOne(context + LocalAccountScope.Element(state.token))
            .onEach { LocalAccountScope.check(state.token) }
    }

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
fun <T : Any> Query<T>.subscribeToOneOrNull(context: CoroutineContext = EmptyCoroutineContext): Flow<T?> =
    LocalAccountScope.changes.flatMapLatest { state ->
        if (!state.ready) flowOf(null) else asFlow().mapToOneOrNull(context + LocalAccountScope.Element(state.token))
            .onEach { LocalAccountScope.check(state.token) }
    }
