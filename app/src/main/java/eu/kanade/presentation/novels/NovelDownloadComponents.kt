package eu.kanade.presentation.novels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.theme.MangaroDesignSystem as Design
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.novels.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

internal fun chapterGroups(index: NovelChapterIndex, descending: Boolean) = novelChapterGroups(index, descending)
internal fun downloadLabel(state: NovelDownloadState?) = when (state) {
    NovelDownloadState.DONE -> "متاح دون إنترنت"
    NovelDownloadState.RUNNING -> "جارٍ التحميل"
    NovelDownloadState.PENDING -> "في قائمة الانتظار"
    NovelDownloadState.PAUSED -> "متوقف مؤقتًا"
    NovelDownloadState.FAILED -> "تعذّر التحميل"
    NovelDownloadState.CANCELLED -> "أُلغي التحميل"
    NovelDownloadState.DELETING -> "الحذف قيد الإكمال"
    null -> null
}

@Composable
internal fun NovelChapterRow(chapter: NovelChapter, state: NovelDownloadState?, selecting: Boolean, selected: Boolean,
    onSelect: () -> Unit, onRead: () -> Unit, onDownload: () -> Unit, onCommunity: (() -> Unit)? = null, onDelete: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Design.SurfaceDark)
        .clickable(onClick = if (selecting) onSelect else onRead).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (selecting) Checkbox(selected, onCheckedChange = { onSelect() }, enabled = chapter.available)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(chapter.title, color = Color(0xFFE5DAED), style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.ContentOrRtl), maxLines = 2, overflow = TextOverflow.Ellipsis)
            downloadLabel(state)?.let { Text(it, color = if (state == NovelDownloadState.DONE) Design.GoldPrimary else Design.LavenderPrimary, style = MaterialTheme.typography.labelSmall) }
        }
        onCommunity?.let { action ->
            IconButton(onClick = action, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.ChatBubbleOutline, "تعليقات الفصل", Modifier.size(16.dp), tint = Design.LavenderPrimary.copy(alpha = .75f))
            }
        }
        if (!selecting && onDelete != null && state in setOf(NovelDownloadState.DONE, NovelDownloadState.DELETING)) {
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, "حذف الفصل المحمّل", tint = Design.LavenderPrimary) }
        } else if (!selecting) IconButton(onClick = onDownload, enabled = chapter.available && state !in setOf(NovelDownloadState.DONE, NovelDownloadState.RUNNING, NovelDownloadState.PENDING, NovelDownloadState.PAUSED, NovelDownloadState.DELETING)) {
            Icon(if (state == NovelDownloadState.DONE) Icons.Outlined.DownloadDone else Icons.Outlined.Download,
                if (state == NovelDownloadState.FAILED) "إعادة المحاولة" else "تحميل الفصل", tint = Design.GoldPrimary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NovelDownloadSelectionSheet(index: NovelChapterIndex, currentChapterId: String? = null, onDismiss: () -> Unit, onSelect: () -> Unit, onDownload: (List<NovelChapter>) -> Unit) {
    val current = index.chapters.indexOfFirst { it.id == currentChapterId }.takeIf { it >= 0 }
    val next = current?.plus(1) ?: 0
    val remaining = index.chapters.size - next
    var confirmAll by remember { mutableStateOf(false) }
    val placement = remember { "download-selection:" + java.util.UUID.randomUUID().toString() }
    var from by rememberSaveable { mutableStateOf("1") }
    var to by rememberSaveable { mutableStateOf(index.chapters.size.toString()) }
    var volumes by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    val groups = remember(index) { novelChapterGroups(index).filter { it.realVolume } }
    val start = from.toIntOrNull(); val end = to.toIntOrNull()
    val validRange = start != null && end != null && start >= 1 && end >= start && end <= index.chapters.size
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Design.SurfaceDark) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 540.dp), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { NovelAdPlacement(placement) }
            item { Text("تحميل الفصول", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    current?.let { at -> AssistChip(onClick = { onDownload(listOf(index.chapters[at])) }, label = { Text("تحميل الفصل") }) }
                    listOf(10, 25, 50, 100).forEach { count ->
                        AssistChip(onClick = { onDownload(index.chapters.subList(next, next + count)) }, enabled = remaining >= count,
                            label = { Text("تحميل " + numeric(count) + " فصلًا") })
                    }
                    if (index.complete && remaining in 1..9) AssistChip(onClick = { onDownload(index.chapters.drop(next)) }, label = { Text("تحميل المتبقي") })
                }
            }
            if (!index.complete) item { Text("جارٍ استكمال قائمة الفصول…", color = Design.LavenderPrimary, style = MaterialTheme.typography.labelSmall) }
            item { Text("اختر نطاقًا بحسب ترتيب الفصول في القائمة.", color = Color(0xFFBEABCC), style = MaterialTheme.typography.bodySmall) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(from, { from = it.filter(Char::isDigit) }, Modifier.weight(1f), label = { Text("من") }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(to, { to = it.filter(Char::isDigit) }, Modifier.weight(1f), label = { Text("إلى") }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            } }
            item { Button(onClick = { onDownload(index.chapters.subList(start!! - 1, end!!)) }, enabled = validRange, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Design.GoldPrimary, contentColor = Design.BackgroundDark)) { Text("تحميل النطاق") } }
            item { OutlinedButton(onClick = onSelect, modifier = Modifier.fillMaxWidth()) { Text("تحديد الفصول") } }
            item { OutlinedButton(onClick = { confirmAll = true }, enabled = index.complete && index.chapters.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("تحميل الكل") } }
            if (groups.isNotEmpty()) {
                item { Text("المجلدات", color = Color.White, fontWeight = FontWeight.SemiBold) }
                items(groups, key = { it.id }) { group -> Row(Modifier.fillMaxWidth().clickable { volumes = if (group.id in volumes) ArrayList(volumes - group.id) else ArrayList(volumes + group.id) }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(group.id in volumes, onCheckedChange = { volumes = if (group.id in volumes) ArrayList(volumes - group.id) else ArrayList(volumes + group.id) })
                    Column { Text(group.title.orEmpty(), color = Color.White); Text(numeric(group.chapters.size) + " فصلًا", color = Design.LavenderPrimary, style = MaterialTheme.typography.labelSmall) }
                } }
                item { Button(onClick = { onDownload(groups.filter { it.id in volumes }.flatMap { it.chapters }) }, enabled = volumes.isNotEmpty() && index.complete, modifier = Modifier.fillMaxWidth()) { Text("تحميل المجلدات المختارة") } }
            }
    }
    }
    if (confirmAll) AlertDialog(onDismissRequest = { confirmAll = false }, title = { Text("تحميل جميع الفصول؟") },
        text = { Text("سيُضاف " + numeric(index.chapters.count { it.available }) + " فصلًا إلى التنزيلات، مع الاحتفاظ بالفصول المحمّلة.") },
        confirmButton = { TextButton(onClick = { confirmAll = false; onDownload(index.chapters.filter { it.available }) }) { Text("تحميل الكل") } },
        dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("رجوع") } })
}

class NovelDownloadsScreen : Screen() {
    @Composable override fun Content() { OpenNovelAppSection(downloads = true) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelDownloadsContent() {
        val context = LocalContext.current
        val repository = remember(context) { NovelRepository.get(context) }
        NovelForegroundRefresh(repository)
        val queue = repository.downloads
        val tasks by queue.tasks.collectAsState()
        val queueError by queue.error.collectAsState()
        val restored by queue.restored.collectAsState()
        val summaries by remember(queue) { queue.tasks.map { queue.summaries(it) }.flowOn(Dispatchers.Default) }.collectAsState(initial = emptyList())
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        var expanded by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
        var pendingCancel by remember { mutableStateOf<String?>(null) }
        var manageId by remember { mutableStateOf<String?>(null) }
        var selectedChapters by remember { mutableStateOf(emptySet<String>()) }
        var pendingDelete by remember { mutableStateOf<Pair<String, Set<String>>?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        fun control(id: String, action: NovelDownloadState, chapterId: String? = null) {
            scope.launch { try { queue.control(id, action, chapterId) } catch (c: CancellationException) { throw c } catch (e: Exception) { error = novelError(e) } }
        }
        Column(Modifier.fillMaxSize().background(Design.BackgroundDark)) {
            (error ?: queueError)?.let { Text(it, Modifier.padding(16.dp), color = Color(0xFFE5B5AB)) }
            val operation = remember { "downloads:" + java.util.UUID.randomUUID().toString() }
            if (!restored) CircularProgressIndicator(Modifier.padding(16.dp).size(24.dp), strokeWidth = 2.dp)
            else if (tasks.isEmpty() && queueError == null) Text("لا توجد تنزيلات للروايات بعد.", Modifier.padding(24.dp), color = Design.LavenderPrimary)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (tasks.any { it.state in setOf(NovelDownloadState.PENDING, NovelDownloadState.RUNNING) }) item(key = "advertisement") { NovelAdPlacement(operation) }
                summaries.forEach { summary ->
                    item(key = summary.novel.id) {
                        var menu by remember { mutableStateOf(false) }
                        val active = summary.pending + summary.running > 0
                        val retryable = summary.failed + summary.cancelled > 0
                        val status = when {
                            summary.deleting > 0 -> "الحذف قيد الإكمال"
                            summary.running > 0 -> "جارٍ التحميل"
                            summary.pending > 0 -> "في الانتظار"
                            summary.paused > 0 -> "متوقف مؤقتًا"
                            summary.failed > 0 -> numeric(summary.failed) + " تعذّر تحميلها"
                            summary.cancelled > 0 -> numeric(summary.cancelled) + " ملغاة"
                            else -> "متاح دون إنترنت"
                        }
                        eu.kanade.presentation.downloads.MangaroDownloadGroupRow(
                            expanded = summary.novel.id in expanded,
                            onExpand = { expanded = if (summary.novel.id in expanded) ArrayList(expanded - summary.novel.id) else ArrayList(expanded + summary.novel.id) },
                            cover = { NovelCover(summary.novel, Modifier.width(60.dp).height(84.dp).clickable { navigator.push(NovelDetailsScreen(summary.novel)) }) },
                            details = {
                                Text(summary.novel.title, color = Color.White, style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.ContentOrRtl), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(numeric(summary.done) + " / " + numeric(summary.total) + " · " + status, color = Design.LavenderPrimary, style = MaterialTheme.typography.bodySmall)
                                if (active || summary.paused > 0) LinearProgressIndicator(progress = { summary.done.toFloat() / summary.total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth().height(3.dp), color = Design.GoldPrimary, trackColor = Design.SurfaceHigh)
                            },
                            actions = {
                                if (active || summary.paused > 0 || retryable) IconButton(onClick = {
                                    control(summary.novel.id, when { active -> NovelDownloadState.PAUSED; summary.paused > 0 -> NovelDownloadState.PENDING; else -> NovelDownloadState.FAILED })
                                }, modifier = Modifier.size(38.dp)) {
                                    Icon(when { active -> Icons.Outlined.Pause; summary.paused > 0 -> Icons.Outlined.PlayArrow; else -> Icons.Outlined.Refresh },
                                        when { active -> "إيقاف مؤقت"; summary.paused > 0 -> "متابعة التحميل"; else -> "إعادة المحاولة" }, Modifier.size(19.dp), tint = Design.LavenderPrimary)
                                }
                                Box {
                                    IconButton(onClick = { menu = true }, modifier = Modifier.size(38.dp)) { Icon(Icons.Outlined.MoreVert, "خيارات التنزيل", Modifier.size(19.dp), tint = Design.LavenderPrimary) }
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Design.SurfaceDark) {
                                        if (retryable) DropdownMenuItem(text = { Text("إعادة المحاولة") }, onClick = { menu = false; control(summary.novel.id, NovelDownloadState.FAILED) })
                                        if (active || summary.paused > 0) DropdownMenuItem(text = { Text("إلغاء التنزيل") }, onClick = { menu = false; pendingCancel = summary.novel.id })
                                        DropdownMenuItem(text = { Text("إدارة الفصول") }, onClick = { menu = false; selectedChapters = emptySet(); manageId = summary.novel.id })
                                        if (summary.done + summary.deleting > 0) DropdownMenuItem(text = { Text("حذف الفصول المحمّلة") }, onClick = {
                                            menu = false
                                            pendingDelete = summary.novel.id to tasks.filter { it.novel.id == summary.novel.id && it.state in setOf(NovelDownloadState.DONE, NovelDownloadState.DELETING) }.map { it.chapter.id }.toSet()
                                        })
                                        DropdownMenuItem(text = { Text("فتح الرواية") }, onClick = { menu = false; navigator.push(NovelDetailsScreen(summary.novel)) })
                                    }
                                }
                            },
                        )
                    }
                    if (summary.novel.id in expanded) items(tasks.filter { it.novel.id == summary.novel.id }, key = { it.key }) { task ->
                        NovelChapterRow(task.chapter, task.state, false, false, {},
                            onRead = { navigator.push(NovelReaderScreen(task.novel, task.chapter)) },
                            onDownload = { control(task.novel.id, NovelDownloadState.FAILED, task.chapter.id) },
                            onCommunity = { navigator.push(eu.kanade.presentation.community.CommunityCommentsScreen(novelCommunityContext(task.novel, task.chapter))) },
                            onDelete = { pendingDelete = task.novel.id to setOf(task.chapter.id) })
                    }
                }
            }
        }
        manageId?.let { id ->
            val chapters = remember(tasks, id) { tasks.filter { it.novel.id == id } }
            ModalBottomSheet(onDismissRequest = { manageId = null }, containerColor = Design.SurfaceDark) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("إدارة الفصول", Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { selectedChapters = chapters.map { it.chapter.id }.toSet() }) { Text("تحديد الكل") }
                }
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val downloaded = chapters.filter { it.chapter.id in selectedChapters && it.state in setOf(NovelDownloadState.DONE, NovelDownloadState.DELETING) }.map { it.chapter.id }.toSet()
                    TextButton(onClick = { pendingDelete = id to downloaded }, enabled = downloaded.isNotEmpty()) { Text("حذف المحدد") }
                    val pending = chapters.filter { it.chapter.id in selectedChapters && it.state !in setOf(NovelDownloadState.DONE, NovelDownloadState.DELETING, NovelDownloadState.CANCELLED) }.map { it.chapter.id }.toSet()
                    TextButton(onClick = { scope.launch {
                        try { queue.cancelSelected(id, pending); selectedChapters = emptySet() }
                        catch (c: CancellationException) { throw c } catch (e: Exception) { error = novelError(e) }
                    } }, enabled = pending.isNotEmpty()) { Text("إلغاء المحدد") }
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(chapters, key = { it.key }) { task ->
                        NovelChapterRow(task.chapter, task.state, true, task.chapter.id in selectedChapters,
                            onSelect = { selectedChapters = if (task.chapter.id in selectedChapters) selectedChapters - task.chapter.id else selectedChapters + task.chapter.id },
                            onRead = {}, onDownload = {})
                        task.error?.let { Text(it, color = Color(0xFFE5B5AB), style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
        pendingDelete?.let { (id, chapters) ->
            AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("حذف الفصول المحمّلة؟") },
                text = { Text("سيُحذف " + numeric(chapters.size) + " فصلًا من هذا الجهاز فقط. ستبقى المكتبة والمفضلة وتقدّم القراءة محفوظة.") },
                confirmButton = { TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        try { queue.deleteChapters(id, chapters); selectedChapters = selectedChapters - chapters }
                        catch (c: CancellationException) { throw c } catch (e: Exception) { error = novelError(e) }
                    }
                }) { Text("حذف") } },
                dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("رجوع") } })
        }
        pendingCancel?.let { id -> AlertDialog(onDismissRequest = { pendingCancel = null }, title = { Text("إلغاء التنزيل؟") }, text = { Text("ستبقى الفصول المكتملة متاحة دون إنترنت.") },
            confirmButton = { TextButton(onClick = { control(id, NovelDownloadState.CANCELLED); pendingCancel = null }) { Text("إلغاء التنزيل") } }, dismissButton = { TextButton(onClick = { pendingCancel = null }) { Text("رجوع") } }) }
    }
