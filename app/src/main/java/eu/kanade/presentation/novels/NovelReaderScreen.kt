package eu.kanade.presentation.novels

import android.text.Spanned
import android.text.style.StyleSpan
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.text.HtmlCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.novels.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import tachiyomi.core.common.util.lang.WesternDigits

/** Dedicated, paragraph-lazy text reader. Never enters manga ReaderActivity or XP hooks. */
class NovelReaderScreen(private val novel: Novel, private val initialChapter: NovelChapter) : Screen() {
    @OptIn(ExperimentalMaterial3Api::class,FlowPreview::class)
    @Composable override fun Content() {
        val context=LocalContext.current
        val repository=remember(context) {NovelRepository.get(context)}
        val navigator=LocalNavigator.currentOrThrow
        val storageError by repository.storageError.collectAsState()
        val savedSettings by repository.settings.collectAsState()
        var appearance by remember {mutableStateOf(savedSettings)}
        LaunchedEffect(savedSettings) {appearance=savedSettings}
        var chapter by rememberSaveable {mutableStateOf(initialChapter)}
        var settingsOpen by rememberSaveable {mutableStateOf(false)}
        var chaptersOpen by rememberSaveable {mutableStateOf(false)}
        var chapterChoices by remember {mutableStateOf(emptyList<NovelChapter>())}
        var chaptersComplete by remember {mutableStateOf(false)}
        val tasks by repository.downloads.tasks.collectAsState()
        val offline = tasks.any {it.novel.id==novel.id && it.chapter.id==chapter.id && it.state==NovelDownloadState.DONE}
        var choicesLoading by remember {mutableStateOf(false)}
        var paragraphs by remember {mutableStateOf(emptyList<AnnotatedString>())}
        var loadedChapter by remember { mutableStateOf<NovelChapter?>(null) }
        var restoring by remember { mutableStateOf(false) }
        var plain by remember {mutableStateOf(emptyList<String>())}
        var error by remember {mutableStateOf<String?>(null)}
        var loading by remember {mutableStateOf(true)}
        var navigating by remember {mutableStateOf(false)}
        var generation by remember {mutableIntStateOf(0)}
        val scroll=rememberLazyListState()
        val scope=rememberCoroutineScope()
        LaunchedEffect(chapter.id,generation) {
            loading=true;restoring=true;loadedChapter=null;error=null;paragraphs=emptyList();plain=emptyList()
            try {
                repository.awaitLocal()
                val text=repository.chapterText(novel,chapter)
                plain=text.paragraphs
                paragraphs=withContext(Dispatchers.Default) {text.paragraphs.mapIndexed {i,p -> styledParagraph(text.markup.getOrNull(i),p)}}
                val position=repository.library.value.firstOrNull {it.novel.id==novel.id}?.position?.takeIf {it.chapter.id==chapter.id}
                val index = withContext(Dispatchers.Default) {
                    val anchor = position?.anchor?.takeIf { it.isNotEmpty() }?.let { hash ->
                        plain.indices.filter { paragraphAnchor(plain[it]) == hash }.minByOrNull { kotlin.math.abs(it - ((position?.paragraph ?: 1) - 1)) } ?: -1
                    } ?: -1
                    if (anchor >= 0) anchor + 1 else (position?.paragraph ?: 0).coerceIn(0, paragraphs.size)
                }
                // Wait for this chapter's lazy-list layout, not merely the old heading-only list.
                snapshotFlow { scroll.layoutInfo.totalItemsCount }.first { it >= paragraphs.size + 1 }
                scroll.scrollToItem(index, position?.offset ?: 0)
                loadedChapter = chapter
                restoring = false
                repository.savePosition(novel, chapter, index, position?.offset ?: 0,
                    plain.getOrNull(index - 1)?.let(::paragraphAnchor).orEmpty())
            } catch(c: CancellationException) {throw c}
            catch(e: Exception) {error=novelError(e)}
            finally {loading=false}
        }
        val currentChapter by rememberUpdatedState(loadedChapter)
        val currentRestoring by rememberUpdatedState(restoring)
        val currentPlain by rememberUpdatedState(plain)
        fun saveCurrent() {
            val shown = currentChapter ?: return
            if(!currentRestoring && currentPlain.isNotEmpty()) {
                val index=scroll.firstVisibleItemIndex
                repository.savePosition(novel,shown,index,scroll.firstVisibleItemScrollOffset,
                    currentPlain.getOrNull(index-1)?.let(::paragraphAnchor).orEmpty())
            }
        }
        LaunchedEffect(loadedChapter?.id,restoring) {
            if(loadedChapter != null && !restoring && paragraphs.isNotEmpty()) snapshotFlow {Triple(scroll.firstVisibleItemIndex,scroll.firstVisibleItemScrollOffset,scroll.isScrollInProgress)}
                .debounce(600).distinctUntilChanged().collect {if(!it.third) saveCurrent()}
        }
        val owner=LocalLifecycleOwner.current
        DisposableEffect(owner) {
            val observer=LifecycleEventObserver {_,event -> if(event==Lifecycle.Event.ON_STOP) saveCurrent()}
            owner.lifecycle.addObserver(observer)
            onDispose {owner.lifecycle.removeObserver(observer);saveCurrent()}
        }
        BackHandler {saveCurrent();navigator.pop()}
        fun adjacent(forward: Boolean) {
            if(navigating || loading) return
            saveCurrent();navigating=true
            scope.launch {
                try {
                    val target=repository.adjacent(novel,chapter,forward)
                    if(target!=null) chapter=target
                } catch(c: CancellationException) {throw c} catch(e: Exception) {error=novelError(e)}
                finally {navigating=false}
            }
        }
        fun moreChapters() {
            if(choicesLoading || chaptersComplete) return
            choicesLoading=true
            scope.launch {
                try {
                    repository.indexSnapshot(novel)?.let { chapterChoices=it.chapters; chaptersComplete=it.complete }
                    val result=repository.completeIndex(novel) { partial -> withContext(Dispatchers.Main.immediate) {chapterChoices=partial.chapters; chaptersComplete=partial.complete} }
                    chapterChoices=result.chapters; chaptersComplete=result.complete
                } catch(c: CancellationException) {throw c} catch(e: Exception) {error=novelError(e)}
                finally {choicesLoading=false}
            }
        }
        val background=when(appearance.theme) {"light" -> Color(0xFFF7F5F1);"sepia" -> Color(0xFFF0E3C8);else -> Color(0xFF100D14)}
        val ink=if(appearance.theme=="dark") Color(0xFFE6DEED) else Color(0xFF2F2925)
        val gold=if(appearance.theme=="dark") Color(0xFFDCB965) else Color(0xFF795B28)
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Column(Modifier.fillMaxSize().background(background).safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().heightIn(min=52.dp),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick={saveCurrent();navigator.pop()}) {Icon(Icons.AutoMirrored.Outlined.ArrowBack,"رجوع",tint=ink)}
                    Text(novel.title,Modifier.weight(1f),style=MaterialTheme.typography.labelLarge.copy(textDirection=TextDirection.ContentOrRtl),
                        color=ink,maxLines=2)
                    IconButton(onClick={scope.launch { try {repository.downloads.enqueue(novel,listOf(chapter))} catch(c: CancellationException) {throw c} catch(e: Exception) {error=novelError(e)} }},enabled=!offline) {Icon(Icons.Outlined.Download,"تحميل الفصل",tint=gold)}
                    val entries by repository.library.collectAsState()
                    val bookmarked = entries.firstOrNull { it.novel.id == novel.id }?.bookmarks?.contains(chapter.id) == true
                    IconButton(onClick={scope.launch {
                        try {repository.updateLibrary(novel, bookmark=chapter.id)} catch(c: CancellationException) {throw c} catch(e: Exception) {error=novelError(e)}
                    }}) {Icon(if(bookmarked) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,"إشارة مرجعية",tint=gold)}
                    IconButton(onClick={settingsOpen=true}) {Icon(Icons.Outlined.FormatSize,"إعدادات القراءة",tint=gold)}
                    IconButton(onClick={chaptersOpen=true;if(chapterChoices.isEmpty()) moreChapters()}) {Icon(Icons.Outlined.List,"قائمة الفصول",tint=gold)}
                }
                storageError?.let { Text(it,Modifier.padding(horizontal=20.dp),color=gold,style=MaterialTheme.typography.labelSmall) }
                if(offline) Text("متاح دون إنترنت",Modifier.padding(horizontal=20.dp),color=gold,style=MaterialTheme.typography.labelSmall)
                if(loading) LinearProgressIndicator(Modifier.fillMaxWidth(),color=gold)
                if(error!=null) NovelFailure(error!!,chapter.url) {generation++}
                LazyColumn(state=scroll,modifier=Modifier.weight(1f),contentPadding=PaddingValues(horizontal=appearance.margin.dp,vertical=20.dp),
                    verticalArrangement=Arrangement.spacedBy(appearance.paragraphSpacing.dp)) {
                    item(key="chapter-heading") {Text(chapter.title,color=gold,fontWeight=FontWeight.Bold,
                        style=MaterialTheme.typography.headlineSmall.copy(textDirection=TextDirection.ContentOrRtl),modifier=Modifier.padding(bottom=12.dp))}
                    items(paragraphs.size,key={it}) {index ->
                        Text(paragraphs[index],color=ink,style=MaterialTheme.typography.bodyLarge.copy(
                            fontFamily=if(appearance.font=="system") FontFamily.Default else FontFamily(Font(R.font.novel_noto_naskh_arabic)),
                            fontSize=appearance.fontSize.sp,lineHeight=(appearance.fontSize*appearance.lineSpacing).sp,
                            textDirection=TextDirection.ContentOrRtl))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=4.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                    TextButton(onClick={adjacent(false)},enabled=!loading && !navigating) {Text("الفصل السابق",color=gold)}
                    TextButton(onClick={adjacent(true)},enabled=!loading && !navigating) {Text("الفصل التالي",color=gold)}
                }
            }
            if(settingsOpen) ModalBottomSheet(onDismissRequest={settingsOpen=false},containerColor=Color(0xFF1B1423)) {
                Column(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("إعدادات القراءة",color=Color.White,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        listOf("naskh" to "نسخ", "system" to "خط الجهاز").forEach { (font,label) ->
                            FilterChip(appearance.font==font,onClick={appearance=appearance.copy(font=font);repository.saveSettings(appearance)},label={Text(label)})
                        }
                    }
                    ReaderSlider("حجم الخط",appearance.fontSize,16f..32f,onValue={appearance=appearance.copy(fontSize=it)},onSave={repository.saveSettings(appearance)})
                    ReaderSlider("تباعد السطور",appearance.lineSpacing,1.3f..2.3f,onValue={appearance=appearance.copy(lineSpacing=it)},onSave={repository.saveSettings(appearance)})
                    ReaderSlider("تباعد الفقرات",appearance.paragraphSpacing.toFloat(),6f..30f,onValue={appearance=appearance.copy(paragraphSpacing=it.toInt())},onSave={repository.saveSettings(appearance)})
                    ReaderSlider("الهوامش",appearance.margin.toFloat(),12f..40f,onValue={appearance=appearance.copy(margin=it.toInt())},onSave={repository.saveSettings(appearance)})
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        listOf("dark" to "داكن","light" to "فاتح","sepia" to "ورقي").forEach { (theme,label) ->
                            FilterChip(appearance.theme==theme,onClick={appearance=appearance.copy(theme=theme);repository.saveSettings(appearance)},label={Text(label)})
                        }
                    }
                    TextButton(onClick={repository.saveSettings(appearance);settingsOpen=false},modifier=Modifier.align(Alignment.End)) {Text("تم",color=Color(0xFFDCB965))}
                }
            }
            if(chaptersOpen) ModalBottomSheet(onDismissRequest={chaptersOpen=false},containerColor=Color(0xFF1B1423)) {
                Text("الفصول",Modifier.padding(20.dp),color=Color.White,style=MaterialTheme.typography.titleMedium)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max=480.dp)) {
                    items(chapterChoices.size,key={chapterChoices[it].id}) {index ->
                        val choice=chapterChoices[index]
                        TextButton(onClick={saveCurrent();chapter=choice;chaptersOpen=false},modifier=Modifier.fillMaxWidth()) {
                            Text(choice.title,color=if(choice.id==chapter.id) gold else Color(0xFFDDCDE8))
                        }
                    }
                    if(choicesLoading) item {LinearProgressIndicator(Modifier.fillMaxWidth(),color=gold)}
                    else if(!chaptersComplete) item {TextButton(onClick=::moreChapters,modifier=Modifier.fillMaxWidth()) {Text("حاول مجددًا")}}
                }
            }
        }
    }
}

@Composable
private fun ReaderSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onValue: (Float) -> Unit, onSave: () -> Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Text(label,Modifier.weight(1f),color=Color(0xFFD7C8E1),style=MaterialTheme.typography.labelMedium)
        Text(WesternDigits.isolate(WesternDigits.format("%.1f",value)),color=Color(0xFFDCB965),style=MaterialTheme.typography.labelMedium)
    }
    Slider(value,onValue,valueRange=range,onValueChangeFinished=onSave)
}
private fun paragraphAnchor(value: String) = java.security.MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") {"%02x".format(it)}

/** Standard HTML styling conversion happens off the main thread, never in a WebView. */
private fun styledParagraph(markup: String?, plain: String): AnnotatedString {
    if(markup==null) return AnnotatedString(plain)
    val text=HtmlCompat.fromHtml(markup,HtmlCompat.FROM_HTML_MODE_COMPACT)
    return buildAnnotatedString {
        append(text.toString().trimEnd())
        text.getSpans(0,text.length,StyleSpan::class.java).forEach {span ->
            val start=text.getSpanStart(span).coerceIn(0,length);val end=text.getSpanEnd(span).coerceIn(start,length)
            val bold=span.style and android.graphics.Typeface.BOLD != 0
            val italic=span.style and android.graphics.Typeface.ITALIC != 0
            addStyle(SpanStyle(fontWeight=if(bold) FontWeight.Bold else null,fontStyle=if(italic) FontStyle.Italic else null),start,end)
        }
    }
}
