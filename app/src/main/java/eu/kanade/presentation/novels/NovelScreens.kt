package eu.kanade.presentation.novels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.presentation.theme.MangaroDesignSystem as Design
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.novels.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.WesternDigits

internal fun novelError(error: Exception): String {
    android.util.Log.w("MangaroNovels","Novel operation failed",error)
    return (error as? NovelSourceFailure)?.publicMessage ?: "تعذّر التحميل حاليًا. حاول مجددًا."
}

@Composable
internal fun NovelShell(title: String, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    val navigator=LocalNavigator.currentOrThrow
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(Modifier.fillMaxSize().background(Design.BackgroundDark).safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(end=12.dp),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick={navigator.pop()}) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"رجوع") }
                Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,
                    maxLines=1,overflow=TextOverflow.Ellipsis,color=Color.White)
                actions()
            }
            val storageError by NovelRepository.get(LocalContext.current).storageError.collectAsState()
            storageError?.let { Text(it,Modifier.padding(horizontal=20.dp,vertical=8.dp),color=Color(0xFFE5B5AB),style=MaterialTheme.typography.labelMedium) }
            content()
        }
    }
}

@Composable
internal fun NovelCover(novel: Novel, modifier: Modifier) {
    val loader=NovelRepository.get(LocalContext.current).covers
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(Design.SurfaceHigh),contentAlignment=Alignment.Center) {
        Icon(Icons.Outlined.MenuBook,null,Modifier.size(40.dp),tint=Design.GoldPrimary.copy(alpha=.5f))
        novel.cover?.let { AsyncImage(it,null,imageLoader=loader,modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Crop) }
    }
}

@Composable
private fun NovelCard(novel: Novel, onOpen: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Design.SurfaceDark).clickable(onClick=onOpen).padding(8.dp),
        verticalArrangement=Arrangement.spacedBy(8.dp)) {
        NovelCover(novel,Modifier.fillMaxWidth().aspectRatio(2f/3f))
        Text(novel.title,style=MaterialTheme.typography.bodyMedium.copy(textDirection=TextDirection.ContentOrRtl),
            color=Color.White,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
    }
}

@Composable
internal fun NovelFailure(message: String, url: String, retry: () -> Unit) {
    val browser=LocalUriHandler.current
    Column(Modifier.fillMaxWidth().padding(20.dp),horizontalAlignment=Alignment.CenterHorizontally,
        verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(message,color=Color(0xFFD0BEDD),style=MaterialTheme.typography.bodyMedium)
        TextButton(onClick=retry) { Text("حاول مجددًا",color=Design.GoldPrimary) }
        TextButton(onClick={runCatching { browser.openUri(url) }}) { Text("فتح الرواية في الموقع",color=Design.LavenderPrimary) }
    }
}

class NovelHomeScreen : Screen() {
    @Composable override fun Content() {
        val context=LocalContext.current
        val repository=remember(context) { NovelRepository.get(context) }
        val navigator=LocalNavigator.currentOrThrow
        val saved by repository.library.collectAsState()
        var sourceId by rememberSaveable { mutableStateOf("novel.kolnovel") }
        val source=repository.source(sourceId)
        var input by rememberSaveable { mutableStateOf("") }
        var term by rememberSaveable { mutableStateOf("") }
        var latest by rememberSaveable { mutableStateOf(true) }
        var genre by rememberSaveable { mutableStateOf<String?>(null) }
        var availableGenres by remember { mutableStateOf(emptyList<NovelGenre>()) }
        var moreJob by remember { mutableStateOf<Job?>(null) }
        var generation by remember { mutableIntStateOf(0) }
        var list by remember { mutableStateOf(emptyList<Novel>()) }
        var nextPage by remember { mutableStateOf<Int?>(null) }
        var loading by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<String?>(null) }
        var notice by remember { mutableStateOf<String?>(null) }
        val scope=rememberCoroutineScope()
        LaunchedEffect(sourceId,term,latest,genre,generation) {
            moreJob?.cancel()
            loading=true;error=null;list=emptyList();nextPage=null;notice=null
            try {
                val page=if(term.isBlank()) source.catalog(latest=latest,genre=genre) else source.search(term)
                list=page.novels;nextPage=page.nextPage;notice=page.notice;availableGenres=page.genres
            } catch(c: CancellationException) { throw c }
            catch(e: Exception) { error=novelError(e) }
            finally { loading=false }
        }
        fun more() {
            val page=nextPage ?: return
            if(loading) return
            loading=true
            val requestKey=listOf(sourceId,term,latest,genre)
            moreJob=scope.launch {
                try {
                    val result=if(term.isBlank()) source.catalog(page,latest=latest,genre=genre) else source.search(term,page)
                    if(requestKey!=listOf(sourceId,term,latest,genre)) return@launch
                    list=(list+result.novels).distinctBy { it.id };nextPage=result.nextPage
                } catch(c: CancellationException) { throw c }
                catch(e: Exception) { error=novelError(e) }
                finally {if(requestKey==listOf(sourceId,term,latest,genre)) loading=false}
            }
        }
        NovelShell("الروايات",actions={
            IconButton(onClick={navigator.push(NovelLibraryScreen())}) {Icon(Icons.Outlined.Bookmarks,"مكتبة الروايات",tint=Design.GoldPrimary)}
        }) {
            Text("عوالم تُقرأ، وحكايات تنتظرك.",Modifier.padding(horizontal=20.dp,vertical=4.dp),
                color=Color(0xFFBBA4CE),style=MaterialTheme.typography.bodyMedium)
            FlowRow(Modifier.padding(horizontal=16.dp,vertical=4.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                repository.sources.forEach { item ->
                    FilterChip(sourceId==item.id,onClick={sourceId=item.id;term="";input="";genre=null;availableGenres=emptyList()},
                        label={Text(item.name,style=MaterialTheme.typography.labelMedium)},border=null,
                        colors=FilterChipDefaults.filterChipColors(containerColor=Design.SurfaceDark,
                            selectedContainerColor=Color(0xFF342445),selectedLabelColor=Design.GoldPrimary))
                }
            }
            OutlinedTextField(input,{input=it},Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp),
                placeholder={Text("ابحث عن رواية")},singleLine=true,shape=RoundedCornerShape(16.dp),
                textStyle=MaterialTheme.typography.bodyMedium.copy(textDirection=TextDirection.Content),
                keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={term=input.trim()}),
                trailingIcon={IconButton(onClick={term=input.trim()}) {Icon(Icons.Outlined.Search,"بحث")}})
            if(term.isBlank() && sourceId in setOf("novel.kolnovel","novel.cenele")) Row(Modifier.padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(latest,onClick={latest=true},label={Text("آخر التحديثات")},border=null)
                FilterChip(!latest,onClick={latest=false},label={Text("الأكثر قراءة")},border=null)
            }
            if(term.isBlank() && availableGenres.isNotEmpty()) LazyRow(Modifier.fillMaxWidth(),contentPadding=PaddingValues(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                item {FilterChip(genre==null,onClick={genre=null},label={Text("كل الأنواع")},border=null)}
                items(availableGenres,key={it.value}) {item -> FilterChip(genre==item.value,onClick={genre=item.value},label={Text(item.name,style=MaterialTheme.typography.labelSmall)},border=null)}
            }
            saved.firstOrNull { it.position!=null }?.let { recent ->
                Surface(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp).clickable {
                    navigator.push(NovelReaderScreen(recent.novel,recent.position!!.chapter))
                },shape=RoundedCornerShape(16.dp),color=Color(0xFF261B30)) {
                    Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        NovelCover(recent.novel,Modifier.size(36.dp,50.dp))
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                            Text("تابع القراءة",color=Design.GoldPrimary,style=MaterialTheme.typography.labelMedium)
                            Text(recent.novel.title,color=Color.White,style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Outlined.PlayArrow,null,tint=Design.GoldPrimary)
                    }
                }
            }
            notice?.let { Text(it,Modifier.padding(horizontal=20.dp,vertical=3.dp),color=Color(0xFFAE99BE),style=MaterialTheme.typography.labelSmall) }
            if(error!=null) NovelFailure(error!!,source.baseUrl) { generation++ }
            if(loading && list.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp),color=Design.GoldPrimary,strokeWidth=2.dp)
            } else if(list.isEmpty() && error==null) Text("لا توجد نتائج لهذا البحث.",Modifier.padding(24.dp),color=Color(0xFFBBA4CE))
            else LazyVerticalGrid(GridCells.Adaptive(136.dp),Modifier.weight(1f),contentPadding=PaddingValues(16.dp),
                horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                items(list,key={it.id}) { novel -> NovelCard(novel) {navigator.push(NovelDetailsScreen(novel))} }
            }
            if(nextPage!=null) TextButton(onClick=::more,enabled=!loading,modifier=Modifier.align(Alignment.CenterHorizontally)) {
                Text(if(loading) "جارٍ التحميل…" else "عرض المزيد",color=Design.GoldPrimary)
            }
        }
    }
}

class NovelDetailsScreen(private val initial: Novel) : Screen() {
    @Composable override fun Content() {
        val context=LocalContext.current
        val repository=remember(context) { NovelRepository.get(context) }
        val navigator=LocalNavigator.currentOrThrow
        val library by repository.library.collectAsState()
        val stored=library.firstOrNull { it.novel.id==initial.id }
        val scope=rememberCoroutineScope()
        var novel by remember { mutableStateOf(initial) }
        var chapters by remember { mutableStateOf(emptyList<NovelChapter>()) }
        var nextPage by remember { mutableStateOf<Int?>(null) }
        var loading by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<String?>(null) }
        var retry by remember { mutableIntStateOf(0) }
        var descending by rememberSaveable { mutableStateOf(false) }
        var expandedDescription by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(initial.id,retry) {
            loading=true;error=null
            // Independent errors: usable metadata is not lost if a chapter request fails.
            val details=async { runCatching { repository.source(initial.sourceId).details(initial) } }
            val index=async { runCatching { repository.chapterPage(initial,1) } }
            details.await().onSuccess {novel=it}.onFailure {if(it is CancellationException) throw it;error=novelError(it as Exception)}
            index.await().onSuccess {chapters=it.chapters;nextPage=it.nextPage}.onFailure {if(it is CancellationException) throw it;error=novelError(it as Exception)}
            loading=false
        }
        NovelShell("الرواية",actions={
            IconButton(onClick={scope.launch { repository.setSaved(novel,stored?.saved!=true) }}) {
                Icon(if(stored?.saved==true) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                    if(stored?.saved==true) "إزالة من مكتبة الروايات" else "إضافة إلى مكتبة الروايات",tint=Design.GoldPrimary)
            }
        }) {
            LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                item {
                    Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                        NovelCover(novel,Modifier.width(105.dp).height(154.dp))
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text(novel.title,color=Color.White,style=MaterialTheme.typography.titleLarge.copy(textDirection=TextDirection.ContentOrRtl),fontWeight=FontWeight.Bold)
                            Text(repository.source(novel.sourceId).name,color=Design.GoldPrimary,style=MaterialTheme.typography.labelMedium)
                            novel.author?.let {Text(it,color=Color(0xFFC3B1D0),style=MaterialTheme.typography.bodySmall.copy(textDirection=TextDirection.Content))}
                            novel.status?.let { val label=when(it.lowercase()) {"ongoing" -> "مستمرة";"completed" -> "مكتملة";else -> it};Text(label,color=Color(0xFFAE99BE),style=MaterialTheme.typography.labelSmall)}
                            novel.chapterCount?.let {Text(WesternDigits.isolate(it.toString())+" فصل",color=Color(0xFFAE99BE),style=MaterialTheme.typography.labelMedium)}
                        }
                    }
                }
                if(chapters.isNotEmpty() || stored?.position!=null) item {
                    Button(onClick={navigator.push(NovelReaderScreen(novel,stored?.position?.chapter ?: chapters.first()))},
                        modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp),
                        colors=ButtonDefaults.buttonColors(containerColor=Design.GoldPrimary,contentColor=Design.BackgroundDark)) {
                        Text(if(stored?.position!=null) "متابعة القراءة" else "ابدأ القراءة")
                    }
                }
                if(novel.description.isNotBlank()) item {
                    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(novel.description,color=Color(0xFFD2C7DB),maxLines=if(expandedDescription) Int.MAX_VALUE else 4,
                            overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium.copy(textDirection=TextDirection.ContentOrRtl))
                        TextButton(onClick={expandedDescription=!expandedDescription},contentPadding=PaddingValues(0.dp)) {
                            Text(if(expandedDescription) "عرض أقل" else "قراءة الوصف كاملًا",color=Design.LavenderPrimary)
                        }
                    }
                }
                if(novel.genres.isNotEmpty()) item {
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        novel.genres.forEach { genre -> Surface(shape=RoundedCornerShape(10.dp),color=Design.SurfaceHigh) {
                            Text(genre,Modifier.padding(horizontal=9.dp,vertical=5.dp),style=MaterialTheme.typography.labelSmall,color=Color(0xFFCDBADD))
                        } }
                    }
                }
                item {Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text("الفصول",Modifier.weight(1f),fontWeight=FontWeight.Bold,color=Color.White)
                    TextButton(onClick={descending=!descending}) {Text(if(descending) "الأحدث أولًا" else "الأقدم أولًا",color=Design.LavenderPrimary)}
                }}
                error?.let { item {NovelFailure(it,novel.url) {retry++}} }
                if(loading) item {LinearProgressIndicator(Modifier.fillMaxWidth(),color=Design.GoldPrimary)}
                items(if(descending) chapters.reversed() else chapters,key={it.id}) { ch ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Design.SurfaceDark)
                        .clickable {navigator.push(NovelReaderScreen(novel,ch))}.padding(14.dp),
                        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text(ch.title,Modifier.weight(1f),color=if(stored?.position?.chapter?.id==ch.id) Design.GoldPrimary else Color(0xFFE7DEEF),
                            style=MaterialTheme.typography.bodyMedium.copy(textDirection=TextDirection.ContentOrRtl))
                        Icon(Icons.Outlined.ChevronLeft,null,Modifier.size(18.dp),tint=Color(0xFF9984AB))
                    }
                }
                if(nextPage!=null) item {
                    TextButton(enabled=!loading,onClick={
                        val page=nextPage ?: return@TextButton
                        loading=true
                        scope.launch {
                            try {val result=repository.chapterPage(novel,page);chapters=(chapters+result.chapters).distinctBy {it.id};nextPage=result.nextPage}
                            catch(c: CancellationException) {throw c} catch(e: Exception) {error=novelError(e)}
                            finally {loading=false}
                        }
                    },modifier=Modifier.fillMaxWidth()) {Text("عرض المزيد من الفصول",color=Design.GoldPrimary)}
                }
                if(!loading && chapters.isEmpty() && error==null) item { Text("لا توجد فصول متاحة حاليًا.",color=Color(0xFFAE99BE)) }
            }
        }
    }
}

class NovelLibraryScreen : Screen() {
    @Composable override fun Content() {
        val context=LocalContext.current
        val repository=remember(context) {NovelRepository.get(context)}
        val navigator=LocalNavigator.currentOrThrow
        val library by repository.library.collectAsState()
        val scope=rememberCoroutineScope()
        var ready by remember {mutableStateOf(false)}
        LaunchedEffect(Unit) {repository.awaitLocal();ready=true}
        NovelShell("مكتبة الروايات") {
            if(ready && library.none {it.saved}) Text("أضف الروايات التي تحبها لتعود إليها بسهولة.",Modifier.padding(24.dp),color=Color(0xFFBBA4CE))
            LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                items(library.filter {it.saved},key={it.novel.id}) {entry ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Design.SurfaceDark)
                        .clickable {navigator.push(NovelDetailsScreen(entry.novel))}.padding(12.dp),
                        horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                        NovelCover(entry.novel,Modifier.width(58.dp).height(82.dp))
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Text(entry.novel.title,color=Color.White,fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.bodyMedium.copy(textDirection=TextDirection.ContentOrRtl))
                            Text(repository.source(entry.novel.sourceId).name,color=Color(0xFFAE99BE),style=MaterialTheme.typography.labelSmall)
                            entry.position?.let {pos ->
                                Text(pos.chapter.title,color=Color(0xFFCDBADD),maxLines=1,overflow=TextOverflow.Ellipsis,
                                    style=MaterialTheme.typography.labelSmall.copy(textDirection=TextDirection.ContentOrRtl))
                                TextButton(onClick={navigator.push(NovelReaderScreen(entry.novel,pos.chapter))},contentPadding=PaddingValues(0.dp)) {
                                    Text("متابعة القراءة",color=Design.GoldPrimary)
                                }
                            }
                        }
                        IconButton(onClick={scope.launch {repository.setSaved(entry.novel,false)}}) {Icon(Icons.Outlined.BookmarkRemove,"إزالة من المكتبة",tint=Color(0xFFAE99BE))}
                    }
                }
            }
        }
    }
}
