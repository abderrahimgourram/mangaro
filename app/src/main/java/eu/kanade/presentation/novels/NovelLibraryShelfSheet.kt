package eu.kanade.presentation.novels

import androidx.compose.runtime.*
import eu.kanade.presentation.library.MangaroLibraryShelfSheet
import eu.kanade.presentation.library.MangaroShelfChoice
import eu.kanade.tachiyomi.novels.Novel
import eu.kanade.tachiyomi.novels.NovelRepository
import eu.kanade.tachiyomi.ui.library.MangaroLibraryShelves.Shelf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Thin adapter to the existing shelf sheet and existing novel library record. */
@Composable
internal fun NovelLibraryShelfSheet(novel: Novel, repository: NovelRepository, onDismiss: () -> Unit) {
    val entries by repository.library.collectAsState()
    val catalog by repository.catalog.collectAsState()
    val restored by repository.restored.collectAsState()
    val storageError by repository.storageError.collectAsState()
    val ids = catalog.work(novel.id)?.editions?.map { it.id }?.toSet() ?: setOf(novel.id)
    val current = entries.filter { it.novel.id in ids }
    val saved = current.any { it.saved }
    var favorite by remember(novel.id) { mutableStateOf(false) }
    var status by remember(novel.id) { mutableStateOf("reading") }
    var initialized by remember(novel.id) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(novel.id, restored) {
        if (restored && !initialized && !repository.libraryRestoreFailed) {
            favorite = current.any { it.favorite }
            status = current.firstOrNull { it.novel.id == novel.id }?.readingStatus ?: current.firstOrNull()?.readingStatus ?: "reading"
            initialized = true
        }
    }
    fun save(remove: Boolean) {
        if (saving || !initialized) return
        saving = true; error = null
        scope.launch {
            try {
                repository.updateLibrary(novel, favorite = if (remove) null else favorite, status = if (remove) null else status, saved = !remove)
                onDismiss()
            } catch (c: CancellationException) { throw c }
            catch (e: Exception) { error = novelError(e) }
            finally { saving = false }
        }
    }
    MangaroLibraryShelfSheet(
        title = novel.title, inLibrary = saved,
        options = listOf(
            MangaroShelfChoice(Shelf.FAVORITE, favorite, "المفضلة"),
            MangaroShelfChoice(Shelf.READING, status == "reading", "أقرأ الآن"),
            MangaroShelfChoice(Shelf.LATER, status == "planned", "للقراءة لاحقًا"),
            MangaroShelfChoice(Shelf.COMPLETED, status == "completed", "مكتملة"),
        ),
        loaded = initialized && !repository.libraryRestoreFailed, saving = saving,
        error = error ?: storageError,
        onToggle = { shelf -> when (shelf) {
            Shelf.FAVORITE -> favorite = !favorite
            Shelf.READING -> status = "reading"
            Shelf.LATER -> status = "planned"
            Shelf.COMPLETED -> status = "completed"
            Shelf.PAUSED -> Unit
        } },
        onSave = { save(false) }, onRemove = { save(true) },
        onRetry = { scope.launch { repository.retryLibraryRestore() } }, onDismissRequest = onDismiss,
    )
}
