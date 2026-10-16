package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.network.HttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException

/** Two attempts only for transport/server failures; access denials/rate limits require user retry. */
internal object ReaderLoadRecovery {
    fun category(error: Throwable): String = when(error) {
        is CancellationException -> "CANCELLED"
        is HttpException -> "HTTP_${error.code}"
        is java.net.SocketTimeoutException -> "TIMEOUT"
        is IOException -> "IO"
        is EmptyReaderPages -> "EMPTY_PAGE_LIST"
        else -> "SOURCE_OR_PARSER"
    }
    suspend fun <T> request(backoff: suspend () -> Unit = {delay(400)}, fetch: suspend () -> T): T {
        try {return fetch()}
        catch (cancel: CancellationException) {throw cancel}
        catch (error: Exception) {
            if (error !is IOException && !(error is HttpException && error.code in setOf(500,502,503,504))) throw error
            backoff()
            return fetch()
        }
    }
}
internal class EmptyReaderPages : IllegalStateException("Empty chapter page list")
