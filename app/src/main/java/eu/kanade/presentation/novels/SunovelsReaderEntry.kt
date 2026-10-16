package eu.kanade.presentation.novels

import androidx.compose.runtime.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.novels.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** One user request; validate Sunovels through the existing memory/offline/direct contract before navigation. */
@Composable
internal fun rememberSunovelsReaderEntry(
    onLoading: (Boolean) -> Unit = {},
    onFailure: (suspend (Novel, Exception) -> Unit)? = null,
): (Novel, NovelChapter) -> Unit {
    val repository = NovelRepository.get(LocalContext.current)
    val navigator = LocalNavigator.currentOrThrow
    val scope = rememberCoroutineScope()
    val failure by rememberUpdatedState(onFailure)
    val loading by rememberUpdatedState(onLoading)
    var pending by remember { mutableStateOf(false) }
    var failedSelection by remember { mutableStateOf<Pair<Novel, NovelChapter>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var manualRetry by remember { mutableStateOf(false) }
    val open: (Novel, NovelChapter) -> Unit = { novel, chapter ->
        if (novel.sourceId != SunovelsSource.SOURCE_ID) navigator.push(NovelReaderScreen(novel, chapter))
        else if (!pending) {
            message = null
            pending = true; loading(true)
            val destination = navigator.lastItem.key
            val retryRequested = manualRetry
            manualRetry = false
            scope.launch {
                try {
                    repository.prepareReaderChapter(novel, chapter, manualRetry = retryRequested)
                    if (navigator.lastItem.key == destination) navigator.push(NovelReaderScreen(novel, chapter))
                } catch (c: CancellationException) { throw c }
                catch (e: Exception) {
                    if (navigator.lastItem.key == destination) {
                        val handler = failure
                        failedSelection = novel to chapter
                        message = novelError(e)
                        if (handler != null) handler(novel, e)
                    }
                } finally { pending = false; loading(false) }
            }
        }
    }
    message?.let { text ->
        AlertDialog(onDismissRequest = { message = null }, title = { Text("تعذّر فتح الفصل") },
            text = { androidx.compose.foundation.layout.Column {
                Text(text)
                failedSelection?.let { SunovelsChapterDiagnostic(it.first, it.second) }
            } }, confirmButton = { TextButton(onClick = {
                val selection = failedSelection
                message = null
                if (selection != null) { manualRetry = true; open(selection.first, selection.second) }
            }) { Text("إعادة المحاولة") } },
            dismissButton = { TextButton(onClick = { message = null }) { Text("إغلاق") } })
    }
    return open
}
