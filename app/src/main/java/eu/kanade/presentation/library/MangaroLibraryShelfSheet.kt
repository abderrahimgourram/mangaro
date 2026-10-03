package eu.kanade.presentation.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.presentation.theme.MangaroDesignSystem
import eu.kanade.tachiyomi.ui.library.MangaroLibraryShelves
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun MangaroLibraryShelfSheet(manga: Manga, onDismissRequest: () -> Unit) {
    val scope = rememberCoroutineScope()
    var shelves by remember(manga.id) { mutableStateOf<List<MangaroLibraryShelves.Binding>>(emptyList()) }
    var selected by remember(manga.id) { mutableStateOf<Set<Long>>(emptySet()) }
    var loaded by remember(manga.id) { mutableStateOf(false) }
    var saving by remember(manga.id) { mutableStateOf(false) }
    var error by remember(manga.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(manga.id) {
        try {
            val (bindings, ids) = withIOContext {
                MangaroLibraryShelves.ensure() to Injekt.get<CategoryRepository>().getCategoriesByMangaId(manga.id).map { it.id }.toSet()
            }
            shelves = bindings
            selected = ids.intersect(bindings.map { it.categoryId }.toSet())
            loaded = true
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) {
            manga.logcat(LogPriority.ERROR, failure)
            error = "تعذّر تحميل الرفوف. حاول مجددًا"
        }
    }

    fun save(remove: Boolean) {
        if (!loaded || saving) return
        saving = true
        error = null
        scope.launch {
            try {
                withIOContext {
                    val repository = Injekt.get<MangaRepository>()
                    val current = repository.getMangaById(manga.id)
                    val update = Injekt.get<UpdateManga>()
                    if (remove) {
                        check(update.awaitUpdateFavorite(current.id, false))
                    } else {
                        // Preserve every custom category, including changes made since opening.
                        val managedIds = shelves.map { it.categoryId }.toSet()
                        val customIds = Injekt.get<CategoryRepository>().getCategoriesByMangaId(current.id)
                            .map { it.id }.filter { it > 0 && it !in managedIds }
                        repository.setMangaCategories(current.id, (customIds + selected).distinct())
                        if (!current.favorite) {
                            check(update.awaitUpdateFavorite(current.id, true))
                            Injekt.get<AddTracks>().bindEnhancedTrackers(current, Injekt.get<SourceManager>().getOrStub(current.source))
                        }
                    }
                }
                onDismissRequest()
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                manga.logcat(LogPriority.ERROR, failure)
                error = "تعذّر حفظ المكتبة. حاول مجددًا"
            } finally { saving = false }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!saving) onDismissRequest() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MangaroDesignSystem.SurfaceDark,
        contentColor = Color.White,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(if (manga.favorite) "تنظيم المكتبة" else "إضافة إلى المكتبة", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(manga.title, color = Color(0xFFCBBED5), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!loaded && error == null) {
                CircularProgressIndicator(Modifier.padding(12.dp).size(22.dp), color = MangaroDesignSystem.GoldPrimary, strokeWidth = 2.dp)
            }
            shelves.forEach { binding ->
                val checked = binding.categoryId in selected
                Row(
                    Modifier.fillMaxWidth().height(48.dp).clickable(enabled = !saving) {
                        selected = if (checked) selected - binding.categoryId else selected + binding.categoryId
                    },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        when (binding.shelf) {
                            MangaroLibraryShelves.Shelf.FAVORITE -> Icons.Outlined.FavoriteBorder
                            MangaroLibraryShelves.Shelf.READING -> Icons.Outlined.MenuBook
                            MangaroLibraryShelves.Shelf.LATER -> Icons.Outlined.WatchLater
                            MangaroLibraryShelves.Shelf.COMPLETED -> Icons.Outlined.CheckCircle
                            MangaroLibraryShelves.Shelf.PAUSED -> Icons.Outlined.PauseCircle
                        },
                        null,
                        tint = if (checked) MangaroDesignSystem.GoldPrimary else Color(0xFFAB9CBD),
                        modifier = Modifier.size(21.dp),
                    )
                    Text(binding.shelf.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Checkbox(
                        checked = checked,
                        onCheckedChange = null,
                        enabled = !saving,
                        colors = CheckboxDefaults.colors(checkedColor = MangaroDesignSystem.GoldPrimary, checkmarkColor = MangaroDesignSystem.BackgroundDark, uncheckedColor = Color(0xFF80738F)),
                    )
                }
            }
            error?.let { Text(it, color = Color(0xFFD5B4C2), style = MaterialTheme.typography.bodySmall) }
            Button(
                onClick = { save(false) },
                enabled = loaded && !saving,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MangaroDesignSystem.GoldPrimary, contentColor = MangaroDesignSystem.BackgroundDark),
            ) {
                Text(if (saving) "جارٍ الحفظ..." else if (manga.favorite) "حفظ التغييرات" else "إضافة إلى المكتبة", fontWeight = FontWeight.SemiBold)
            }
            if (manga.favorite) {
                TextButton(onClick = { save(true) }, enabled = loaded && !saving, modifier = Modifier.fillMaxWidth()) {
                    Text("إزالة من المكتبة", color = Color(0xFFCBBED5))
                }
            }
        }
    }
}
