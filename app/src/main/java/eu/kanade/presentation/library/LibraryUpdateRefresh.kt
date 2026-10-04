package eu.kanade.presentation.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.work.WorkInfo
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.util.system.workManager
import eu.kanade.tachiyomi.util.system.isOnline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class LibraryUpdateRefresh(val refreshing: Boolean, val refresh: () -> Unit)

/** Observe the existing worker; a periodic job waiting for its next interval is not a refresh. */
internal fun isLibraryUpdateActive(state: WorkInfo.State, tags: Set<String>): Boolean =
    state in setOf(WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED) && "LibraryUpdate-manual" in tags

@Composable
internal fun rememberLibraryUpdateRefresh(onMessage: suspend (String) -> Unit): LibraryUpdateRefresh {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val showMessage by rememberUpdatedState(onMessage)
    val workFlow = remember(context) { context.workManager.getWorkInfosByTagFlow("LibraryUpdate") }
    var active by remember(context) { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(workFlow) {
        val observed = mutableSetOf<java.util.UUID>()
        workFlow.catch { emit(emptyList()) }.collect { work ->
            active = work.any { isLibraryUpdateActive(it.state, it.tags) }
            work.filter { isLibraryUpdateActive(it.state, it.tags) }.forEach { observed.add(it.id) }
            work.filter { it.state.isFinished && observed.remove(it.id) }.forEach {
                if (it.state == WorkInfo.State.FAILED) {
                    showMessage("تعذّر تحديث بعض الأعمال. بياناتك محفوظة — حاول مجددًا")
                }
            }
        }
    }
    var starting by remember { mutableStateOf(false) }
    return LibraryUpdateRefresh(active || starting) {
        if (!active && !starting) {
            starting = true
            scope.launch {
                val message = try {
                    withContext(Dispatchers.IO) {
                        if (!context.isOnline()) {
                            "لا يوجد اتصال. تبقى مكتبتك محفوظة — حاول عند الاتصال"
                        } else {
                            // Include enqueued manual work in admission, before the flow reaches the UI.
                            val busy = context.workManager.getWorkInfosByTag("LibraryUpdate").get()
                                .any { isLibraryUpdateActive(it.state, it.tags) }
                            val started = !busy && LibraryUpdateJob.startNow(context)
                            if (started) "جارٍ البحث عن فصول جديدة" else "التحديث جارٍ بالفعل"
                        }
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    "تعذّر بدء التحديث. حاول مجددًا"
                } finally {
                    starting = false
                }
                showMessage(message)
            }
        }
    }
}
