package eu.kanade.presentation.novels

import android.text.Spanned
import android.text.style.StyleSpan
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import eu.kanade.presentation.community.ReaderCommunitySheet
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
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
import androidx.compose.material.icons.outlined.ChatBubbleOutline
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
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.novels.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import tachiyomi.core.common.util.lang.WesternDigits
import java.text.Normalizer

/** Dedicated, paragraph-lazy text reader. Never enters manga ReaderActivity or XP hooks. */
class NovelReaderScreen(private val novel: Novel, private val initialChapter: NovelChapter) : Screen() {
    @OptIn(ExperimentalMaterial3Api::class,FlowPreview::class)
    @Composable override fun Content() {
        val context=LocalContext.current
        val repository=remember(context) {NovelRepository.get(context)}
        val downloadAdGate = rememberNovelDownloadAdGate()
        val navigator=LocalNavigator.currentOrThrow
        NovelForegroundRefresh(repository)
        val storageError by repository.storageError.collectAsStateWithLifecycle()
        val savedSettings by repository.settings.collectAsStateWithLifecycle()
        var appearance by remember {mutableStateOf(savedSettings)}
        LaunchedEffect(savedSettings) {appearance=savedSettings}
        var chapter by rememberSaveable {mutableStateOf(initialChapter)}
        var settingsOpen by rememberSaveable {mutableStateOf(false)}
        var commentsOpen by rememberSaveable { mutableStateOf(false) }
        var controlsVisible by rememberSaveable { mutableStateOf(true) }
        var inlineAdHeight by remember(chapter.id) { mutableIntStateOf(0) }
        val activity = remember(context) { generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<android.app.Activity>().firstOrNull() }
        val readerPreferences = remember { Injekt.get<ReaderPreferences>() }
        val customBrightness by readerPreferences.customBrightness.changes().collectAsState(initial = readerPreferences.customBrightness.get())
        val brightness by readerPreferences.customBrightnessValue.changes().collectAsState(initial = readerPreferences.customBrightnessValue.get())
        val originalBrightness = remember(activity) { activity?.window?.attributes?.screenBrightness ?: -1f }
        DisposableEffect(activity, customBrightness, brightness) {
            activity?.window?.let { window -> window.attributes = window.attributes.apply {
                screenBrightness = if (!customBrightness || brightness == 0) -1f else if (brightness < 0) .01f else brightness / 100f
            } }
            onDispose { activity?.window?.let { window -> window.attributes = window.attributes.apply { screenBrightness = originalBrightness } } }
        }
        val view = LocalView.current
        val chromeOwner = LocalLifecycleOwner.current
        DisposableEffect(activity, view, chromeOwner, controlsVisible) {
            val controller = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
            val previousBehavior = controller?.systemBarsBehavior
            fun updateChrome() {
                controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (controlsVisible) controller?.show(WindowInsetsCompat.Type.systemBars()) else controller?.hide(WindowInsetsCompat.Type.systemBars())
            }
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) updateChrome()
                else if (event == Lifecycle.Event.ON_STOP) controller?.show(WindowInsetsCompat.Type.systemBars())
            }
            chromeOwner.lifecycle.addObserver(observer)
            updateChrome()
            onDispose { chromeOwner.lifecycle.removeObserver(observer); controller?.show(WindowInsetsCompat.Type.systemBars())
                previousBehavior?.let { controller?.systemBarsBehavior = it } }
        }
        val baseTextStyle = MaterialTheme.typography.bodyLarge
        val paragraphStyle = remember(baseTextStyle, appearance.font, appearance.fontSize, appearance.lineSpacing) {
            baseTextStyle.copy(
                fontFamily = if (appearance.font == "system") FontFamily.Default else FontFamily(Font(R.font.novel_noto_naskh_arabic)),
                fontSize = appearance.fontSize.sp, lineHeight = (appearance.fontSize * appearance.lineSpacing).sp,
                textDirection = TextDirection.ContentOrRtl,
            )
        }
        val insets = WindowInsets.systemBars.asPaddingValues()
        // Keep the text viewport stable while system bars and toolbars toggle.
        val stableTop = remember { insets.calculateTopPadding() }
        val stableBottom = remember { insets.calculateBottomPadding() }
        val tapLimit = LocalViewConfiguration.current.longPressTimeoutMillis
        val tapSlop = LocalViewConfiguration.current.touchSlop
        var chaptersOpen by rememberSaveable {mutableStateOf(false)}
        var chapterChoices by remember {mutableStateOf(emptyList<NovelChapter>())}
        var chaptersComplete by remember {mutableStateOf(false)}
        val offline by remember(repository, chapter.id) {
            repository.downloads.tasks.map { tasks -> tasks.any {
                it.novel.id == novel.id && it.chapter.id == chapter.id && it.state == NovelDownloadState.DONE
            } }.distinctUntilChanged().flowOn(Dispatchers.Default)
        }.collectAsStateWithLifecycle(initialValue = false)
        var choicesLoading by remember {mutableStateOf(false)}
        var paragraphs by remember {mutableStateOf(emptyList<AnnotatedString>())}
        var loadedChapter by remember { mutableStateOf<NovelChapter?>(null) }
        var restoring by remember { mutableStateOf(false) }
        var plain by remember {mutableStateOf(emptyList<String>())}
        var anchors by remember { mutableStateOf(emptyList<String>()) }
        var blocks by remember { mutableStateOf(emptyList<NovelContentBlock>()) }
        var blockPositions by remember { mutableStateOf(emptyList<Int>()) }
        var error by remember {mutableStateOf<String?>(null)}
        var loading by remember {mutableStateOf(true)}
        var navigationJob by remember { mutableStateOf<Job?>(null) }
        var navigating by remember {mutableStateOf(false)}
        var generation by remember {mutableIntStateOf(0)}
        val isCenele = remember(novel.id, novel.sourceId) {
            novel.sourceId == "novel.cenele" || novel.id == "novel.cenele" || novel.id.startsWith("novel.cenele:") || novel.id.startsWith("novel.cenele/")
        }
        var ceneleDiagSourceMatch by remember { mutableStateOf(false) }
        var ceneleDiagOrigin by remember { mutableStateOf("unknown") }
        var ceneleDiagRawTheft by remember { mutableIntStateOf(0) }
        var ceneleDiagRawCenele by remember { mutableIntStateOf(0) }
        var ceneleDiagSanitizerTheft by remember { mutableIntStateOf(0) }
        var ceneleDiagSanitizerCenele by remember { mutableIntStateOf(0) }
        var ceneleDiagRenderedTheft by remember { mutableIntStateOf(0) }
        var ceneleDiagRenderedCenele by remember { mutableIntStateOf(0) }
        val scroll=rememberLazyListState()
        val chapterAd = remember(novel.id, chapter.id) { NovelAdRequest("reader:" + novelDigest(novel.id + "|" + chapter.id)) }
        val scope=rememberCoroutineScope()
        LaunchedEffect(chapter.id,generation) {
            loading=true;restoring=true;loadedChapter=null;error=null;paragraphs=emptyList();plain=emptyList();anchors=emptyList();blocks=emptyList();blockPositions=emptyList()
            try {
                repository.awaitLocal()
                val text=repository.chapterText(novel,chapter)
                plain=text.paragraphs
                val document = preparedDocument(novel.id + "|" + chapter.id, text)
                paragraphs = document.paragraphs
                anchors = document.anchors
                blocks = document.blocks
                blockPositions = document.positions

                fun countWord(list: List<String>, word: String): Int = list.sumOf { p ->
                    val normP = Normalizer.normalize(p, Normalizer.Form.NFKC).replace("\u0640", "")
                    Regex(Regex.escape(word), RegexOption.IGNORE_CASE).findAll(normP).count()
                }
                ceneleDiagSourceMatch = isCenele
                ceneleDiagOrigin = repository.lastDataOrigin
                ceneleDiagRawTheft = countWord(text.paragraphs, "يسرق")
                ceneleDiagRawCenele = countWord(text.paragraphs, "فضاء")
                ceneleDiagSanitizerTheft = countWord(plain, "يسرق")
                ceneleDiagSanitizerCenele = countWord(plain, "فضاء")
                ceneleDiagRenderedTheft = countWord(paragraphs.map { it.text }, "يسرق")
                ceneleDiagRenderedCenele = countWord(paragraphs.map { it.text }, "فضاء")
                val position=repository.readingPosition(novel,chapter)
                val index = withContext(Dispatchers.Default) {
                    val anchor = position?.anchor?.takeIf { it.isNotEmpty() }?.let { hash ->
                        anchors.indices.filter { anchors[it] == hash }.minByOrNull { kotlin.math.abs(document.positions[it] - (position?.paragraph ?: 0)) } ?: -1
                    } ?: -1
                    if (anchor >= 0) anchor + 1 else if ((position?.paragraph ?: 0) == 0) 0 else
                        (blocks.indexOfFirst { it.paragraph == (position!!.paragraph - 1) } + 1).coerceIn(0, blocks.size)
                }
                // Wait for this chapter's lazy-list layout, not merely the old heading-only list.
                withFrameNanos { }
                snapshotFlow { scroll.layoutInfo.totalItemsCount }.first { it >= blocks.size + 1 }
                scroll.scrollToItem(index, position?.offset ?: 0)
                loadedChapter = chapter
                restoring = false
                repository.savePosition(novel, chapter, blockPositions.getOrElse(index - 1) { 0 }, position?.offset ?: 0,
                    anchors.getOrNull(index - 1).orEmpty(), refreshHistory = true)
            } catch(c: CancellationException) {throw c}
            catch(e: Exception) {error=novelError(e)}
            finally {loading=false}
        }
        LaunchedEffect(loadedChapter?.id) {
            val shown = loadedChapter ?: return@LaunchedEffect
            var prefetched = false
            chromeOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (prefetched) return@repeatOnLifecycle
                // Match image-reader preloading: one neighbor once reading approaches the end.
                snapshotFlow { scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                    .first { it >= (blocks.size * 2 / 3).coerceAtLeast(1) }
                val network = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                if (network == null || network.activeNetwork == null || network.isActiveNetworkMetered) return@repeatOnLifecycle
                try {
                    // Only cached adjacent metadata; speculative reading never crawls a remote index.
                    val index = repository.indexSnapshot(novel) ?: return@repeatOnLifecycle
                    val current = index.chapters.indexOfFirst { it.id == shown.id }
                    if (current < 0) return@repeatOnLifecycle
                    val next = index.chapters.getOrNull(current + 1)?.takeIf { it.available } ?: return@repeatOnLifecycle
                    val nextText = repository.chapterText(novel, next)
                    preparedDocument(novel.id + "|" + next.id, nextText)
                    prefetched = true
                } catch (c: CancellationException) { throw c }
                catch (e: Exception) { android.util.Log.d("MangaroNovels", "Optional adjacent chapter unavailable", e) }
            }
        }
        val currentChapter by rememberUpdatedState(loadedChapter)
        val currentRestoring by rememberUpdatedState(restoring)
        val currentPlain by rememberUpdatedState(plain)
        val currentAnchors by rememberUpdatedState(anchors)
        val currentBlockPositions by rememberUpdatedState(blockPositions)
        fun saveCurrent() {
            val shown = currentChapter ?: return
            if(!currentRestoring && currentPlain.isNotEmpty()) {
                val index=scroll.firstVisibleItemIndex.coerceAtMost(currentAnchors.size)
                repository.savePosition(novel,shown,currentBlockPositions.getOrElse(index - 1) { 0 },scroll.firstVisibleItemScrollOffset,
                    currentAnchors.getOrNull(index-1).orEmpty())
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
        BackHandler { if (!controlsVisible) controlsVisible = true else { saveCurrent();navigator.pop() } }
        fun adjacent(forward: Boolean) {
            if(navigating || loading) return
            saveCurrent();navigating=true
            val requested = chapter
            navigationJob = scope.launch {
                try {
                    val target=repository.adjacent(novel,requested,forward)
                    if(target!=null && chapter.id == requested.id) chapter=target
                } catch(c: CancellationException) {throw c} catch(e: Exception) {if(chapter.id == requested.id) error=novelError(e)}
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
            Box(Modifier.fillMaxSize().background(background).padding(top = stableTop, bottom = stableBottom)) {
                Column(Modifier.fillMaxSize()) {
                    storageError?.let { Text(it,Modifier.padding(horizontal=20.dp),color=gold,style=MaterialTheme.typography.labelSmall) }
                    if(error!=null) NovelFailure(error!!,chapter.url) {generation++}
                    val adVisible by remember { derivedStateOf { scroll.layoutInfo.visibleItemsInfo.any { it.key == "chapter-heading" } } }
                    LazyColumn(state=scroll,modifier=Modifier.fillMaxSize().pointerInput(tapLimit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                var moved = false
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Final)
                                    val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if ((pointer.position - down.position).getDistance() > tapSlop || event.changes.size > 1) moved = true
                                    if (!pointer.pressed) {
                                        val onHeaderOrAd = scroll.layoutInfo.visibleItemsInfo.any {
                                            it.key == "chapter-heading" && inlineAdHeight > 0 && down.position.y >= it.offset && down.position.y < it.offset + inlineAdHeight
                                        }
                                        // Observe without consuming: long-press selection and scrolling keep their gestures.
                                        if (!pointer.isConsumed && !moved && !onHeaderOrAd && pointer.uptimeMillis - down.uptimeMillis < tapLimit && !scroll.isScrollInProgress) {
                                            controlsVisible = !controlsVisible
                                            if (!controlsVisible) { settingsOpen = false; chaptersOpen = false }
                                        }
                                        break
                                    }
                                }
                            }
                        },contentPadding=PaddingValues(top=64.dp,bottom=112.dp),
                            verticalArrangement=Arrangement.spacedBy(appearance.paragraphSpacing.dp)) {
                            item(key="chapter-heading") {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Box(Modifier.fillMaxWidth().onSizeChanged { inlineAdHeight = it.height }) {
                                        if (!loading && loadedChapter?.id == chapter.id && error == null) {
                                            // The URL is shared across chapters; the WebView and callbacks are not.
                                            // Cached transitions may coalesce loading frames, so isolate ownership explicitly.
                                            key(chapterAd.key, chapterAd.owner) {
                                                NovelAdPlacement(chapterAd, visible = adVisible, allowStart = !scroll.isScrollInProgress)
                                            }
                                        }
                                    }
                                    Text(chapter.title,color=gold,fontWeight=FontWeight.Bold,
                                        style=MaterialTheme.typography.titleLarge.copy(textDirection=TextDirection.ContentOrRtl),
                                        modifier=Modifier.padding(horizontal=appearance.margin.dp).alpha(if(controlsVisible) 1f else 0f).then(if(controlsVisible) Modifier else Modifier.clearAndSetSemantics { }))
                                }
                            }
                            items(blocks.size, key = { "block-$it" }, contentType = { blocks[it].kind }) { index ->
                                val block = blocks[index]
                                if (block.kind == NovelBlockKind.IMAGE) {
                                    Box(Modifier.padding(horizontal=appearance.margin.dp)) { NovelIllustration(repository, block, offline, gold) }
                                } else {
                                    val value = paragraphs[block.paragraph!!]
                                    Text(value, modifier = Modifier.padding(horizontal=appearance.margin.dp), color = ink, style = when (block.kind) {
                                        NovelBlockKind.HEADING -> paragraphStyle.copy(fontWeight = FontWeight.Bold)
                                        NovelBlockKind.CAPTION -> paragraphStyle.copy(fontSize = (appearance.fontSize * .8f).sp)
                                        else -> paragraphStyle
                                    })
                                }
                            }
                        }
                }
                if (BuildConfig.DEBUG && isCenele) {
                    Surface(
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp, top = 60.dp),
                        color = Color.Black.copy(alpha = 0.88f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("CENELE DIAGNOSTICS", color = Color.Yellow, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text("Source Match: $ceneleDiagSourceMatch", color = Color.White, fontSize = 10.sp)
                            Text("Origin: $ceneleDiagOrigin", color = Color.White, fontSize = 10.sp)
                            Text("Raw 'يسرق' / 'فضاء': $ceneleDiagRawTheft / $ceneleDiagRawCenele", color = Color.White, fontSize = 10.sp)
                            Text("Sanitized 'يسرق' / 'فضاء': $ceneleDiagSanitizerTheft / $ceneleDiagSanitizerCenele", color = Color.White, fontSize = 10.sp)
                            Text("Rendered 'يسرق' / 'فضاء': $ceneleDiagRenderedTheft / $ceneleDiagRenderedCenele", color = Color.White, fontSize = 10.sp)
                        }
                    }
                }
                if (loading) CircularProgressIndicator(Modifier.align(Alignment.TopCenter).padding(top=56.dp).size(20.dp),color=gold,strokeWidth=2.dp)
                if (customBrightness && brightness < 0) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = (-brightness / 100f).coerceIn(0f, .75f))))
                if (controlsVisible) {
                    Surface(Modifier.align(Alignment.TopCenter).fillMaxWidth(), color = background.copy(alpha = .97f)) {
                        Row(Modifier.heightIn(min=52.dp),verticalAlignment=Alignment.CenterVertically) {
                            IconButton(onClick={saveCurrent();navigator.pop()}) {Icon(Icons.AutoMirrored.Outlined.ArrowBack,"رجوع",tint=ink)}
                            Text(novel.title,Modifier.weight(1f),style=MaterialTheme.typography.labelLarge.copy(textDirection=TextDirection.ContentOrRtl),
                                color=ink,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            IconButton(onClick={settingsOpen=true}) {Icon(Icons.Outlined.FormatSize,"إعدادات القراءة",tint=gold)}
                            IconButton(onClick={chaptersOpen=true;if(chapterChoices.isEmpty()) moreChapters()}) {Icon(Icons.Outlined.List,"قائمة الفصول",tint=gold)}
                        }
                    }
                    Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), color = background.copy(alpha = .97f)) {
                        Column(Modifier.padding(horizontal=12.dp,vertical=4.dp)) {
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                                TextButton(onClick={adjacent(false)},enabled=!loading && !navigating) {Text("السابق",color=gold)}
                                ReaderProgress(scroll, blocks.size, gold)
                                TextButton(onClick={adjacent(true)},enabled=!loading && !navigating) {Text("التالي",color=gold)}
                            }
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
                                IconButton(onClick={downloadAdGate(1) { scope.launch { try {repository.downloads.enqueue(novel,listOf(chapter))} catch(c: CancellationException) {throw c} catch(e: Exception) {error=novelError(e)} } }},enabled=!offline && !loading) {Icon(Icons.Outlined.Download,"تحميل الفصل",tint=gold)}
                                val bookmarked by remember(repository, novel.id, chapter.id) {
                                    repository.library.map { entries -> entries.firstOrNull { it.novel.id == novel.id }?.bookmarks?.contains(chapter.id) == true }
                                        .distinctUntilChanged().flowOn(Dispatchers.Default)
                                }.collectAsStateWithLifecycle(initialValue = false)
                                IconButton(onClick={scope.launch {
                                    try {repository.updateLibrary(novel, bookmark=chapter.id)} catch(c: CancellationException) {throw c} catch(e: Exception) {error=novelError(e)}
                                }}) {Icon(if(bookmarked) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,"إشارة مرجعية",tint=gold)}
                                IconButton(onClick={saveCurrent(); commentsOpen=true}, enabled=!loading && !navigating) {
                                    Icon(Icons.Outlined.ChatBubbleOutline,"تعليقات الفصل",tint=gold)
                                }
                                if (offline) Text("دون إنترنت",color=gold,style=MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            if (commentsOpen) ReaderCommunitySheet(novelCommunityContext(novel, chapter), onDismiss = { commentsOpen = false })
        if(settingsOpen) ModalBottomSheet(onDismissRequest={settingsOpen=false},containerColor=Color(0xFF1B1423)) {
                Column(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("إعدادات القراءة",color=Color.White,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium)
                    ReaderSlider("السطوع", if(customBrightness) brightness.toFloat() else 0f, -75f..100f,
                        onValue={ readerPreferences.customBrightness.set(it != 0f); readerPreferences.customBrightnessValue.set(it.toInt()) }, onSave={})
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
                        TextButton(onClick={saveCurrent();navigationJob?.cancel();navigating=false;chapter=choice;chaptersOpen=false},modifier=Modifier.fillMaxWidth()) {
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
private fun paragraphAnchor(value: String): String {
    val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    val hex = "0123456789abcdef"
    return buildString(64) { bytes.forEach { b -> val v = b.toInt() and 255; append(hex[v ushr 4]); append(hex[v and 15]) } }
}

private data class PreparedNovelDocument(val source: NovelText, val paragraphs: List<AnnotatedString>, val anchors: List<String>,
    val blocks: List<NovelContentBlock>, val positions: List<Int>)
private val preparedDocuments = LinkedHashMap<String, PreparedNovelDocument>(4, .75f, true)

/** Mirrors the existing two-chapter text budget; never retains an entire novel. */
private suspend fun preparedDocument(key: String, text: NovelText): PreparedNovelDocument = withContext(Dispatchers.Default) {
    synchronized(preparedDocuments) { preparedDocuments[key]?.takeIf { it.source === text } }?.let { return@withContext it }
    val paragraphs = text.paragraphs.mapIndexed { index, paragraph ->
        ensureActive()
        styledParagraph(text.markup.getOrNull(index), paragraph)
    }
    val paragraphAnchors = text.paragraphs.map { ensureActive(); paragraphAnchor(it) }
    val blocks = text.orderedBlocks()
    var lastParagraph = 0
    val positions = blocks.map { block -> block.paragraph?.let { lastParagraph = it + 1 }; lastParagraph }
    val anchors = blocks.map { block ->
        if (block.kind == NovelBlockKind.IMAGE) "image:" + paragraphAnchor(block.imageUrl ?: "blocked:" + block.alt) else paragraphAnchors[block.paragraph!!]
    }
    val result = PreparedNovelDocument(text, paragraphs, anchors, blocks, positions)
    synchronized(preparedDocuments) {
        preparedDocuments[key] = result
        while (preparedDocuments.size > 2 || preparedDocuments.values.sumOf { doc ->
            doc.source.paragraphs.sumOf { it.length } + doc.source.markup.sumOf { it.length } + doc.blocks.sumOf { (it.imageUrl?.length ?: 0) + it.alt.length }
        } > 1_000_000) preparedDocuments.remove(preparedDocuments.keys.first())
    }
    result
}

/** Use the verified plain text as the source of truth. */
private fun styledParagraph(markup: String?, plain: String): AnnotatedString {
    if (markup == null) return AnnotatedString(plain)

    val text = HtmlCompat.fromHtml(
        markup,
        HtmlCompat.FROM_HTML_MODE_COMPACT,
    )

    val rendered = text.toString().trimEnd()

    // Never restore unsanitized text from the original HTML.
    if (rendered != plain) {
        return AnnotatedString(plain)
    }

    return buildAnnotatedString {
        append(rendered)

        text.getSpans(0, text.length, StyleSpan::class.java).forEach { span ->
            val start = text.getSpanStart(span).coerceIn(0, length)
            val end = text.getSpanEnd(span).coerceIn(start, length)

            val bold = span.style and android.graphics.Typeface.BOLD != 0
            val italic = span.style and android.graphics.Typeface.ITALIC != 0

            addStyle(
                SpanStyle(
                    fontWeight = if (bold) FontWeight.Bold else null,
                    fontStyle = if (italic) FontStyle.Italic else null,
                ),
                start,
                end,
            )
        }
    }
}

@Composable
private fun ReaderProgress(scroll: androidx.compose.foundation.lazy.LazyListState, count: Int, color: Color) {
    val progress by remember(scroll, count) { derivedStateOf { scroll.firstVisibleItemIndex.coerceAtMost(count) } }
    Text(WesternDigits.isolate("$progress / $count"), color = color, style = MaterialTheme.typography.labelSmall)
}
