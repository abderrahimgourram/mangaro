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
    null -> null
}

@Composable
internal fun NovelChapterRow(chapter: NovelChapter, state: NovelDownloadState?, selecting: Boolean, selected: Boolean,
    onSelect: () -> Unit, onRead: () -> Unit, onDownload: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Design.SurfaceDark)
        .clickable(onClick = if (selecting) onSelect else onRead).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (selecting) Checkbox(selected, onCheckedChange = { onSelect() }, enabled = chapter.available)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(chapter.title, color = Color(0xFFE5DAED), style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.ContentOrRtl), maxLines = 2, overflow = TextOverflow.Ellipsis)
            downloadLabel(state)?.let { Text(it, color = if (state == NovelDownloadState.DONE) Design.GoldPrimary else Design.LavenderPrimary, style = MaterialTheme.typography.labelSmall) }
        }
        if (!selecting) IconButton(onClick = onDownload, enabled = chapter.available && state !in setOf(NovelDownloadState.DONE, NovelDownloadState.RUNNING, NovelDownloadState.PENDING, NovelDownloadState.PAUSED)) {
            Icon(if (state == NovelDownloadState.DONE) Icons.Outlined.DownloadDone else Icons.Outlined.Download,
                if (state == NovelDownloadState.FAILED) "إعادة المحاولة" else "تحميل الفصل", tint = Design.GoldPrimary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NovelDownloadSelectionSheet(index: NovelChapterIndex, onDismiss: () -> Unit, onDownload: (List<NovelChapter>) -> Unit) {
    var from by rememberSaveable { mutableStateOf("1") }
    var to by rememberSaveable { mutableStateOf(index.chapters.size.toString()) }
    var volumes by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    val groups = remember(index) { novelChapterGroups(index).filter { it.realVolume } }
    val start = from.toIntOrNull(); val end = to.toIntOrNull()
    val validRange = start != null && end != null && start >= 1 && end >= start && end <= index.chapters.size
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Design.SurfaceDark) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 540.dp), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("تحميل الفصول", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) }
            item { Text("اختر نطاقًا بحسب ترتيب الفصول في القائمة.", color = Color(0xFFBEABCC), style = MaterialTheme.typography.bodySmall) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(from, { from = it.filter(Char::isDigit) }, Modifier.weight(1f), label = { Text("من") }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(to, { to = it.filter(Char::isDigit) }, Modifier.weight(1f), label = { Text("إلى") }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            } }
            item { Button(onClick = { onDownload(index.chapters.subList(start!! - 1, end!!)) }, enabled = validRange, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Design.GoldPrimary, contentColor = Design.BackgroundDark)) { Text("تحميل النطاق") } }
            if (groups.isNotEmpty()) {
                item { Text("المجلدات", color = Color.White, fontWeight = FontWeight.SemiBold) }
                items(groups, key = { it.id }) { group -> Row(Modifier.fillMaxWidth().clickable { volumes = if (group.id in volumes) ArrayList(volumes - group.id) else ArrayList(volumes + group.id) }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(group.id in volumes, onCheckedChange = { volumes = if (group.id in volumes) ArrayList(volumes - group.id) else ArrayList(volumes + group.id) })
                    Column { Text(group.title.orEmpty(), color = Color.White); Text(numeric(group.chapters.size) + " فصلًا", color = Design.LavenderPrimary, style = MaterialTheme.typography.labelSmall) }
                } }
                item { Button(onClick = { onDownload(groups.filter { it.id in volumes }.flatMap { it.chapters }) }, enabled = volumes.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("تحميل المجلدات المختارة") } }
            }
        }
    }
}

class NovelDownloadsScreen : Screen() {
    @Composable override fun Content() { OpenNovelAppSection(downloads = true) }
}

@Composable
fun NovelDownloadsContent() {
        val context = LocalContext.current
        val repository = remember(context) { NovelRepository.get(context) }
        val queue = repository.downloads
        val tasks by queue.tasks.collectAsState()
        val queueError by queue.error.collectAsState()
        val summaries by remember(queue) { queue.tasks.map { queue.summaries(it) }.flowOn(Dispatchers.Default) }.collectAsState(initial = emptyList())
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        var expanded by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
        var pendingCancel by remember { mutableStateOf<String?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        fun control(id: String, action: NovelDownloadState, chapterId: String? = null) {
            scope.launch { try { queue.control(id, action, chapterId) } catch (c: CancellationException) { throw c } catch (e: Exception) { error = novelError(e) } }
        }
        Column(Modifier.fillMaxSize().background(Design.BackgroundDark)) {
            (error ?: queueError)?.let { Text(it, Modifier.padding(16.dp), color = Color(0xFFE5B5AB)) }
            if (summaries.isEmpty()) Text("فصولك المحمّلة ستظهر هنا.", Modifier.padding(24.dp), color = Color(0xFFBEABCC))
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                summaries.forEach { summary ->
                    item(key = summary.novel.id) {
                        Surface(color = Design.SurfaceDark, shape = RoundedCornerShape(18.dp)) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    NovelCover(summary.novel, Modifier.width(52.dp).height(74.dp))
                                    Column(Modifier.weight(1f).clickable { navigator.push(NovelDetailsScreen(summary.novel)) }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(summary.novel.title, color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.ContentOrRtl))
                                        Text("تم التحميل " + numeric(summary.done) + " · متبقي " + numeric(summary.pending + summary.running + summary.paused + summary.failed), color = Design.GoldPrimary, style = MaterialTheme.typography.labelSmall)
                                        if (summary.running > 0) Text("جارٍ التحميل", color = Design.LavenderPrimary, style = MaterialTheme.typography.labelSmall)
                                        if (summary.cancelled > 0) Text("أُلغيت " + numeric(summary.cancelled) + " فصول", color = Design.LavenderPrimary, style = MaterialTheme.typography.labelSmall)
                                        if (summary.failed > 0) Text(numeric(summary.failed) + " فصول تحتاج إلى إعادة المحاولة", color = Color(0xFFE5B5AB), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                LinearProgressIndicator(progress = { summary.done.toFloat() / summary.total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth(), color = Design.GoldPrimary)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (summary.pending + summary.running > 0) TextButton(onClick = { control(summary.novel.id, NovelDownloadState.PAUSED) }) { Text("إيقاف مؤقت") }
                                    if (summary.paused > 0) TextButton(onClick = { control(summary.novel.id, NovelDownloadState.PENDING) }) { Text("متابعة التحميل") }
                                    if (summary.failed + summary.cancelled > 0) TextButton(onClick = { control(summary.novel.id, NovelDownloadState.FAILED) }) { Text("إعادة المحاولة") }
                                    if (summary.pending + summary.running + summary.paused > 0) TextButton(onClick = { pendingCancel = summary.novel.id }) { Text("إلغاء") }
                                    TextButton(onClick = { expanded = if (summary.novel.id in expanded) ArrayList(expanded - summary.novel.id) else ArrayList(expanded + summary.novel.id) }) { Text(if (summary.novel.id in expanded) "إخفاء الفصول" else "الفصول") }
                                }
                            }
                        }
                    }
                    if (summary.novel.id in expanded) items(tasks.filter { it.novel.id == summary.novel.id }, key = { it.key }) { task ->
                        NovelChapterRow(task.chapter, task.state, false, false, {},
                            onRead = { navigator.push(NovelReaderScreen(task.novel, task.chapter)) },
                            onDownload = { control(task.novel.id, NovelDownloadState.FAILED, task.chapter.id) })
                    }
                }
            }
        }
        pendingCancel?.let { id -> AlertDialog(onDismissRequest = { pendingCancel = null }, title = { Text("إلغاء التنزيل؟") }, text = { Text("ستبقى الفصول المكتملة متاحة دون إنترنت.") },
            confirmButton = { TextButton(onClick = { control(id, NovelDownloadState.CANCELLED); pendingCancel = null }) { Text("إلغاء التنزيل") } }, dismissButton = { TextButton(onClick = { pendingCancel = null }) { Text("رجوع") } }) }
    }
