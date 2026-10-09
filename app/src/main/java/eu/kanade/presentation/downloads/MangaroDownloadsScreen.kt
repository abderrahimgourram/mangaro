package eu.kanade.presentation.downloads

import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.theme.MangaroDesignSystem
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.download.MangaroDownloadsViewModel
import eu.kanade.tachiyomi.ui.download.MangaroDownloadsViewModel.CompletedGroup
import eu.kanade.tachiyomi.ui.download.MangaroDownloadsViewModel.CompletedChapter
import eu.kanade.tachiyomi.ui.download.MangaroDownloadsViewModel.Queued
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.core.common.util.lang.WesternDigits

private val Gold = MangaroDesignSystem.GoldPrimary
private val Secondary = Color(0xFFB6A7C6)
private val Failure = Color(0xFFD39AA5)
private enum class DownloadFilter(val title: String) { ALL("الكل"), ACTIVE("جاري"), COMPLETE("مكتمل"), FAILED("فشل") }
private enum class DownloadSort(val title: String) { RECENT("آخر تنزيل"), TITLE("الاسم"), SIZE("الحجم"), COUNT("عدد الفصول") }

@Composable
fun MangaroDownloadsScreen(
    state: MangaroDownloadsViewModel.State,
    onToggleRunning: () -> Unit,
    onRetry: (Queued) -> Unit,
    onRetryFailed: () -> Unit,
    onCancel: (Queued) -> Unit,
    onClearAll: () -> Unit,
    onDelete: (List<CompletedGroup>, CompletedChapter?) -> Unit,
    onOpenManga: (Manga) -> Unit,
    onOpenChapter: (Manga, CompletedChapter) -> Unit,
) {
    var clearMenu by remember { mutableStateOf(false) }
    var clearRequest by remember { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf(DownloadFilter.ALL) }
    var sort by rememberSaveable { mutableStateOf(DownloadSort.RECENT) }
    var sortMenu by remember { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(emptyList<Long>()) }
    var deleteRequest by remember { mutableStateOf<Pair<List<CompletedGroup>, CompletedChapter?>?>(null) }
    val hasCurrentOperations = state.queue.any { it.status != Download.State.DOWNLOADED }
    LaunchedEffect(hasCurrentOperations) {
        if (!hasCurrentOperations) { clearRequest = false; clearMenu = false }
    }
    val active = remember(state.queue) { state.queue.filter { it.status == Download.State.DOWNLOADING } }
    val pending = remember(state.queue) { state.queue.filter { it.status == Download.State.QUEUE || it.status == Download.State.NOT_DOWNLOADED } }
    val failed = remember(state.queue) { state.queue.filter { it.status == Download.State.ERROR } }
    val groups = remember(state.groups, sort) {
        when (sort) {
            DownloadSort.RECENT -> state.groups.sortedWith(compareByDescending<CompletedGroup> { it.latest }.thenBy { it.manga.id })
            DownloadSort.TITLE -> state.groups.sortedWith(compareBy<CompletedGroup> { it.manga.title.lowercase() }.thenBy { it.manga.id })
            DownloadSort.SIZE -> state.groups.sortedWith(compareByDescending<CompletedGroup> { it.bytes }.thenBy { it.manga.id })
            DownloadSort.COUNT -> state.groups.sortedWith(compareByDescending<CompletedGroup> { it.chapters.size }.thenBy { it.manga.id })
        }
    }
    val showQueue = filter == DownloadFilter.ALL || filter == DownloadFilter.ACTIVE
    val showCompleted = filter == DownloadFilter.ALL || filter == DownloadFilter.COMPLETE
    val showFailed = filter == DownloadFilter.ALL || filter == DownloadFilter.FAILED
    val empty = (!showQueue || active.isEmpty() && pending.isEmpty()) && (!showFailed || failed.isEmpty()) && (!showCompleted || groups.isEmpty())

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MangaroDesignSystem.BackgroundDark).statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("التنزيلات", Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (hasCurrentOperations) {
                    TextButton(onClick = onToggleRunning, enabled = !state.clearing) {
                        Icon(if (state.running) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, null, tint = Gold, modifier = Modifier.size(18.dp))
                        Text(if (state.running) "إيقاف مؤقت" else "استئناف", color = Gold)
                    }
                    Box {
                        IconButton(onClick = { clearMenu = true }, enabled = !state.clearing) {
                            Icon(Icons.Outlined.MoreVert, "إزالة جميع التنزيلات", tint = Secondary)
                        }
                        DropdownMenu(expanded = clearMenu, onDismissRequest = { clearMenu = false }, containerColor = MangaroDesignSystem.SurfaceDark) {
                            DropdownMenuItem(
                                text = { Text("إزالة جميع التنزيلات", color = Failure) },
                                leadingIcon = { Icon(Icons.Outlined.DeleteSweep, null, tint = Failure) },
                                enabled = !state.clearing,
                                onClick = { clearMenu = false; clearRequest = true },
                            )
                        }
                    }
                }
            }
        }
        item(key = "summary") { DownloadSummary(state, active.size, pending.size, failed.size) }
        item(key = "filters") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DownloadFilter.entries.forEach { option ->
                    Surface(
                        onClick = { filter = option },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp),
                        color = if (filter == option) MangaroDesignSystem.SurfaceHigh else Color.Transparent,
                    ) {
                        Box(Modifier.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                            Text(option.title, color = if (filter == option) Gold else Secondary, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
        state.error?.let { message -> item(key = "error") { Text(message, color = Secondary, style = MaterialTheme.typography.bodySmall) } }
        if (showQueue) {
            queueSection("active", "جاري التنزيل", active, onRetry, onCancel, onOpenManga)
            queueSection("pending", "في الانتظار", pending, onRetry, onCancel, onOpenManga)
        }
        if (showFailed && failed.isNotEmpty()) {
            item(key = "failedHeader") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("فشل", failed.size, Modifier.weight(1f))
                    TextButton(onClick = onRetryFailed) { Text("إعادة المحاولة", color = Failure, style = MaterialTheme.typography.labelMedium) }
                }
            }
            items(failed, key = { "failed:${it.download.chapter.id}" }) { QueueRow(it, onRetry, onCancel, onOpenManga) }
        }
        if (showCompleted && groups.isNotEmpty()) {
            item(key = "completedHeader") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("مكتمل", groups.sumOf { it.chapters.size }, Modifier.weight(1f))
                    Box {
                        TextButton(onClick = { sortMenu = true }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                            Icon(Icons.AutoMirrored.Outlined.Sort, null, Modifier.size(16.dp), tint = Secondary)
                            Text(sort.title, color = Secondary, style = MaterialTheme.typography.labelSmall)
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }, containerColor = MangaroDesignSystem.SurfaceDark) {
                            DownloadSort.entries.forEach { option ->
                                DropdownMenuItem(text = { Text(option.title, color = if (sort == option) Gold else Secondary) }, onClick = { sort = option; sortMenu = false })
                            }
                        }
                    }
                    IconButton(onClick = { deleteRequest = groups to null }) {
                        Icon(Icons.Outlined.DeleteOutline, "حذف التنزيلات المكتملة", tint = Secondary, modifier = Modifier.size(19.dp))
                    }
                }
            }
            groups.forEach { group ->
                item(key = "manga:${group.manga.id}") {
                    CompletedMangaRow(group, group.manga.id in expanded,
                        onExpand = { expanded = if (group.manga.id in expanded) expanded - group.manga.id else expanded + group.manga.id },
                        onOpen = { onOpenManga(group.manga) }, onDelete = { deleteRequest = listOf(group) to null })
                }
                if (group.manga.id in expanded) {
                    items(group.chapters, key = { "chapter:${it.chapter.id}" }) { chapter ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpenChapter(group.manga, chapter) }.padding(start = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Check, null, tint = Gold, modifier = Modifier.size(15.dp))
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(chapter.chapter.name, color = Color.White, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(sizeLabel(chapter.bytes), color = Secondary, style = MaterialTheme.typography.labelSmall)
                            }
                            IconButton(onClick = { deleteRequest = listOf(group) to chapter }) {
                                Icon(Icons.Outlined.DeleteOutline, "حذف الفصل المنزّل", tint = Secondary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
        if (empty) {
            item(key = "empty") {
                Column(Modifier.fillMaxWidth().padding(vertical = 26.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.scanning) CircularProgressIndicator(Modifier.size(22.dp), color = Gold, strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.Download, null, tint = Secondary, modifier = Modifier.size(28.dp))
                    Text(if (state.scanning) "جارٍ تحميل التنزيلات..." else if (filter == DownloadFilter.ALL) "لا توجد تنزيلات" else "لا توجد تنزيلات في هذه الحالة", color = Color.White, style = MaterialTheme.typography.bodyLarge)
                    if (!state.scanning && filter == DownloadFilter.ALL) Text("الفصول التي تنزّلها ستظهر هنا", color = Secondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (clearRequest && hasCurrentOperations && !state.clearing) {
        AlertDialog(
            onDismissRequest = { clearRequest = false },
            containerColor = MangaroDesignSystem.SurfaceDark,
            shape = RoundedCornerShape(18.dp),
            title = { Text("إزالة جميع التنزيلات؟", color = Color.White) },
            text = { Text("سيتم إيقاف وإزالة جميع عمليات التنزيل الحالية.", color = Secondary) },
            confirmButton = {
                TextButton(onClick = { clearRequest = false; onClearAll() }) { Text("إزالة الكل", color = Failure) }
            },
            dismissButton = { TextButton(onClick = { clearRequest = false }) { Text("إلغاء", color = Secondary) } },
        )
    }
    deleteRequest?.let { request ->
        AlertDialog(
            onDismissRequest = { deleteRequest = null }, containerColor = MangaroDesignSystem.SurfaceDark,
            shape = RoundedCornerShape(18.dp),
            title = { Text(if (request.second != null) "حذف هذا الفصل المنزّل؟" else if (request.first.size == 1) "حذف جميع تنزيلات هذا العمل؟" else "حذف جميع التنزيلات المكتملة؟", color = Color.White) },
            text = { Text("لن تُحذف أعمال المكتبة أو سجل القراءة. تُحترم إعدادات حماية الفصول المنزّلة.", color = Secondary) },
            confirmButton = { TextButton(onClick = { onDelete(request.first, request.second); deleteRequest = null }) { Text("حذف", color = Failure) } },
            dismissButton = { TextButton(onClick = { deleteRequest = null }) { Text("إلغاء", color = Secondary) } },
        )
    }
}

private fun LazyListScope.queueSection(key: String, title: String, rows: List<Queued>, onRetry: (Queued) -> Unit, onCancel: (Queued) -> Unit, onOpen: (Manga) -> Unit) {
    if (rows.isEmpty()) return
    item(key = "${key}Header") { SectionTitle(title, rows.size) }
    items(rows, key = { "$key:${it.download.chapter.id}" }) { QueueRow(it, onRetry, onCancel, onOpen) }
}

@Composable
private fun SectionTitle(title: String, count: Int, modifier: Modifier = Modifier) {
    Text("$title · $count", modifier.padding(top = 4.dp), color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun DownloadSummary(state: MangaroDownloadsViewModel.State, active: Int, pending: Int, failed: Int) {
    Surface(color = MangaroDesignSystem.SurfaceDark, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Color(0x1DA78BFA))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("مكتمل" to state.completedCount, "جاري" to active, "انتظار" to pending, "فشل" to failed).forEach { (label, count) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$count", color = if (label == "فشل" && count > 0) Failure else Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(label, color = Secondary, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            HorizontalDivider(color = Color(0x1DA78BFA))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(state.usedBytes?.let { "${sizeLabel(it)} مستخدم" } ?: "جارٍ حساب المساحة...", color = Secondary, style = MaterialTheme.typography.bodySmall)
                state.freeBytes?.let { Text("${sizeLabel(it)} متاح", color = Secondary, style = MaterialTheme.typography.bodySmall) }
            }
            val used = state.usedBytes
            val free = state.freeBytes
            if (used != null && free != null && used + free > 0) {
                LinearProgressIndicator(progress = { (used.toDouble() / (used.toDouble() + free)).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(4.dp), color = Gold, trackColor = MangaroDesignSystem.SurfaceHigh)
            }
        }
    }
}

@Composable
private fun QueueRow(item: Queued, onRetry: (Queued) -> Unit, onCancel: (Queued) -> Unit, onOpen: (Manga) -> Unit) {
    val download = item.download
    Surface(color = MangaroDesignSystem.SurfaceDark, shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MangaCover.Book(download.manga.asMangaCover(), Modifier.width(48.dp), contentDescription = download.manga.title, shape = RoundedCornerShape(8.dp), onClick = { onOpen(download.manga) })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(download.manga.title, color = Color.White, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(download.chapter.name, color = Secondary, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (item.status == Download.State.DOWNLOADING) {
                    LinearProgressIndicator(progress = { item.progress / 100f }, modifier = Modifier.fillMaxWidth().height(3.dp), color = Gold, trackColor = MangaroDesignSystem.SurfaceHigh)
                    Text("${item.progress}%", color = Secondary, style = MaterialTheme.typography.labelSmall)
                } else if (item.status == Download.State.ERROR) Text("تعذّر التنزيل", color = Failure, style = MaterialTheme.typography.labelSmall)
            }
            if (item.status == Download.State.ERROR) IconButton(onClick = { onRetry(item) }, modifier = Modifier.size(38.dp)) { Icon(Icons.Outlined.Refresh, "إعادة المحاولة", tint = Failure, modifier = Modifier.size(19.dp)) }
            IconButton(onClick = { onCancel(item) }, modifier = Modifier.size(38.dp)) { Icon(Icons.Outlined.Close, "إلغاء التنزيل", tint = Secondary, modifier = Modifier.size(18.dp)) }
        }
    }
}

/** Shared presentation only; callers retain their own real payloads and queue actions. */
@Composable
internal fun MangaroDownloadGroupRow(
    expanded: Boolean, onExpand: () -> Unit,
    cover: @Composable () -> Unit,
    details: @Composable ColumnScope.() -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    Surface(color = MangaroDesignSystem.SurfaceDark, shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onExpand).padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            cover()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp), content = details)
            actions()
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "طي الفصول" else "عرض الفصول", tint = Secondary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun CompletedMangaRow(group: CompletedGroup, expanded: Boolean, onExpand: () -> Unit, onOpen: () -> Unit, onDelete: () -> Unit) {
    MangaroDownloadGroupRow(expanded, onExpand,
        cover = { MangaCover.Book(group.manga.asMangaCover(), Modifier.width(60.dp), contentDescription = group.manga.title, shape = RoundedCornerShape(9.dp), onClick = onOpen) },
        details = {
            Text(group.manga.title, color = Color.White, style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Content), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${group.chapters.size} فصل · ${sizeLabel(group.bytes)}", color = Secondary, style = MaterialTheme.typography.bodySmall)
        },
        actions = { IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.DeleteOutline, "حذف تنزيلات العمل", tint = Secondary, modifier = Modifier.size(18.dp)) } },
    )
}

@Composable
private fun sizeLabel(bytes: Long): String = WesternDigits.normalize(Formatter.formatShortFileSize(LocalContext.current, bytes))
