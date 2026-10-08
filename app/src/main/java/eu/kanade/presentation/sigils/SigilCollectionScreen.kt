package eu.kanade.presentation.sigils

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.theme.MangaroDesignSystem
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.sigils.SigilRepository
import kotlinx.coroutines.launch
import mihon.domain.sigils.*
import mihon.domain.account.AccountFoundation
import mihon.domain.account.AccountSession
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.core.common.util.lang.WesternDigits
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Date

class SigilCollectionScreen(private val initialBadgeId: String? = null) : Screen() {
    @Composable override fun Content() {
        val navigator=LocalNavigator.currentOrThrow
        val repo=remember {Injekt.get<SigilRepository>()}
        val snapshot=activeSnapshot(repo)
        val error by repo.error.collectAsState()
        val counts=remember(snapshot) {SigilProgress.calculate(snapshot.facts,snapshot.community)}
        var filter by rememberSaveable {mutableIntStateOf(0)}
        var selected by rememberSaveable {mutableStateOf(initialBadgeId)}
        var showArtCredits by rememberSaveable {mutableStateOf(false)}
        LaunchedEffect(Unit) {repo.requestRefresh()}
        val earned=snapshot.unlocks.filterNot(SigilUnlock::revoked).map(SigilUnlock::id).toSet()
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Scaffold(topBar={scroll -> AppBar(title="أختام العوالم",navigateUp=navigator::pop,scrollBehavior=scroll)}) {padding ->
                LazyVerticalGrid(columns=GridCells.Adaptive(145.dp),modifier=Modifier.fillMaxSize().background(MangaroDesignSystem.BackgroundDark).padding(padding),
                    contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    item(key="header",span={GridItemSpan(maxLineSpan)}) {
                        Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF2A1B3C),Color(0xFF15111E))),RoundedCornerShape(22.dp)).padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                            Text("رحلتك تترك أثرًا",color=Color(0xFFE4C576),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
                            Text("ثلاثون ختمًا، وستة عوالم. كل ختم حكاية صنعتها بنفسك.",color=Color(0xFFB7A9C4))
                            if(snapshot.owner==null) Text("جارٍ جمع الأختام…",color=Color(0xFFB7A9C4)) else LtrNumber("${earned.size} / 30",Modifier.fillMaxWidth())
                            LinearProgressIndicator(progress={earned.size/30f},modifier=Modifier.fillMaxWidth(),color=Color(0xFFE4C576),trackColor=Color(0xFF382A47))
                            Text("الأختام للزينة فقط، ومستقلة تمامًا عن نقاط الخبرة.",color=Color(0xFF9F90AC),style=MaterialTheme.typography.bodySmall)
                            Text("الأنواع تُحتسب من بيانات العمل الموثقة فقط. التنزيلات القديمة التي لا يوجد لها سجل إكمال لا تُقدّر بأرقام افتراضية.",color=Color(0xFF9F90AC),style=MaterialTheme.typography.bodySmall)
                            TextButton(onClick={showArtCredits=true}) {Text("فن الأختام وتراخيصه",color=Color(0xFFE4C576))}
                        }
                    }
                    item(key="equipment",span={GridItemSpan(maxLineSpan)}) {EquippedSigils(snapshot.slots,onEmpty={selected=earned.firstOrNull()},onTap={selected=it},animated=selected==null && !showArtCredits)}
                    item(key="filters",span={GridItemSpan(maxLineSpan)}) {
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            listOf("الكل","المفتوحة","المغلقة").forEachIndexed {i,label-> FilterChip(selected=filter==i,onClick={filter=i},label={Text(label)})}
                        }
                    }
                    error?.let {item(key="sync",span={GridItemSpan(maxLineSpan)}) {Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(it,Modifier.weight(1f),color=Color(0xFFB7A9C4),style=MaterialTheme.typography.bodySmall)
                        TextButton(onClick=repo::requestRefresh) {Text("إعادة المحاولة")}
                    }}}
                    SigilWorld.entries.forEach {world ->
                        val definitions=RealmSigils.all.filter {it.world==world && (filter==0 || (it.id in earned)==(filter==1))}
                        if(definitions.isNotEmpty()) {
                            item(key=world.name,span={GridItemSpan(maxLineSpan)}) {Text(world.title,Modifier.padding(top=16.dp,bottom=4.dp),color=Color(world.accent),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)}
                            items(definitions,key={it.id}) {definition ->
                                val unlocked=definition.id in earned; val progress=(counts[definition.id] ?: 0).coerceAtMost(definition.required)
                                val equipped=definition.id in snapshot.slots
                                Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(world.accent).copy(alpha=if(unlocked).13f else .035f),Color(0xFF191320))),RoundedCornerShape(20.dp)).border(1.dp,Color(if(definition.rarity==SigilRarity.LEGENDARY)0xFFE4C576 else world.accent).copy(alpha=if(unlocked).4f else .1f),RoundedCornerShape(20.dp))
                                    .clickable {selected=definition.id}.padding(12.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(7.dp)) {
                                    SigilArtwork(definition,unlocked,Modifier.size(120.dp),animated=selected==null && !showArtCredits)
                                    Text(definition.name,color=if(unlocked) Color(0xFFF0E5F5) else Color(0xFF9F90AC),style=MaterialTheme.typography.titleSmall,textAlign=TextAlign.Center)
                                    SigilRarityLabel(definition,unlocked)
                                    if(equipped) Text("مجهّز",color=Color(world.accent),style=MaterialTheme.typography.labelSmall)
                                    else if(!unlocked) Text("ختم مغلق",color=Color(0xFF9F90AC),style=MaterialTheme.typography.labelSmall)
                                    if(!unlocked) {
                                        LinearProgressIndicator(progress={progress.toFloat()/definition.required},modifier=Modifier.fillMaxWidth(),color=Color(world.accent),trackColor=Color(0xFF2F263A))
                                        LtrNumber("$progress / ${definition.required}")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            selected?.let {id -> RealmSigils.byId[id]?.let {definition ->
                SigilDetail(definition,snapshot,counts[id] ?: 0,repo,onDismiss={selected=null})
            }}
            if(showArtCredits) SigilArtCredits(onDismiss={showArtCredits=false})
        }
    }
}

@Composable private fun LtrNumber(text: String,modifier: Modifier=Modifier) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Text(WesternDigits.isolate(text),modifier,color=Color(0xFFD4C5E0),style=MaterialTheme.typography.labelLarge,textAlign=TextAlign.Center)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SigilDetail(definition: SigilDefinition,snapshot: SigilSnapshot,progress: Int,repo: SigilRepository,onDismiss: ()->Unit) {
    val scope=rememberCoroutineScope();var busy by remember {mutableStateOf(false)};var message by remember {mutableStateOf<String?>(null)}
    val unlock=snapshot.unlocks.firstOrNull {it.id==definition.id && !it.revoked}
    ModalBottomSheet(onDismissRequest=onDismiss,containerColor=Color(0xFF15111E)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp).navigationBarsPadding(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
            SigilArtwork(definition,unlock!=null,Modifier.size(232.dp))
            Text(definition.name,color=Color.White,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
            Text(definition.world.title,color=Color(definition.accent))
            SigilRarityLabel(definition,unlock!=null)
            Text(definition.description,color=Color(0xFFB7A9C4),textAlign=TextAlign.Center)
            Text(definition.objective,color=Color(0xFFD4C5E0),textAlign=TextAlign.Center)
            LtrNumber("${progress.coerceAtMost(definition.required)} / ${definition.required}")
            if(definition.id=="murim_master") {
                val facts=snapshot.facts
                Text("التصنيفات الشخصية: ${facts.count {it.kind=="category"}.coerceAtMost(3)} من 3 · الأعمال المنظمة: ${facts.count {it.kind=="work" && it.organized}.coerceAtMost(10)} من 10",color=Color(0xFFB7A9C4),style=MaterialTheme.typography.bodySmall)
            }
            unlock?.let {
                Text(it.unlockedAt?.let {date->"فُتح في ${WesternDigits.date(Date(date))}"} ?: "مكتسب من سجلات موثوقة؛ تاريخ الفتح الأصلي غير معروف",color=Color(0xFF9F90AC),style=MaterialTheme.typography.bodySmall,textAlign=TextAlign.Center)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    repeat(3) {slot ->
                        val occupied=snapshot.slots[slot];val equipped=occupied==definition.id
                        OutlinedButton(enabled=!busy && (equipped || definition.id !in snapshot.slots),onClick={
                            busy=true;scope.launch {try {if(!repo.equip(slot,if(equipped)null else definition.id)) message="تعذّر تجهيز الختم"} finally {busy=false}}
                        },modifier=Modifier.weight(1f),contentPadding=PaddingValues(6.dp)) {Text(if(equipped) "إزالة" else "الخانة ${slot+1}",style=MaterialTheme.typography.labelSmall)}
                    }
                }
            }
            message?.let {Text(it,color=Color(0xFFB7A9C4))}
            TextButton(onClick=onDismiss) {Text("إغلاق")}
        }
    }
}

/** Exactly three ordered public cosmetics; empty slots reveal no collection or reading data. */
@Composable fun EquippedSigils(slots: List<String?>,onEmpty: (() -> Unit)? = null,onTap: ((String)->Unit)? = null,animated: Boolean = true) {
    var previewId by remember(slots) {mutableStateOf<String?>(null)}
    Row(Modifier.fillMaxWidth().padding(vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        repeat(3) {slot ->
            val definition=RealmSigils.byId[slots.getOrNull(slot)]
            Box(Modifier.weight(1f).height(78.dp).background(Brush.verticalGradient(listOf(Color(definition?.accent ?: 0xFF9F90AC).copy(alpha=.09f),Color(0xFF16111D))),RoundedCornerShape(16.dp))
                .border(1.dp,Color(definition?.accent ?: 0xFF9F90AC).copy(alpha=.18f),RoundedCornerShape(16.dp))
                .clickable(enabled=definition!=null || onEmpty!=null) {if(definition!=null) {if(onTap!=null)onTap(definition.id) else previewId=definition.id} else onEmpty?.invoke()},contentAlignment=Alignment.Center) {
                if(definition!=null) SigilArtwork(definition,true,Modifier.size(74.dp),animated=animated && previewId==null,compact=true)
                else Text("ختم ${slot+1}",color=Color(0xFF756782),style=MaterialTheme.typography.labelSmall)
            }
        }
    }
    previewId?.takeIf {it in slots}?.let {id -> RealmSigils.byId[id]?.let {definition ->
        // Only the public equipped cosmetic is shown; never private progress.
        AlertDialog(onDismissRequest={previewId=null},containerColor=Color(0xFF15111E),
            title={Text(definition.name,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth())},
            text={Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                SigilArtwork(definition,true,Modifier.size(220.dp))
                Text(definition.world.title,color=Color(definition.accent),textAlign=TextAlign.Center)
                SigilRarityLabel(definition,true)
            }},confirmButton={TextButton(onClick={previewId=null}){Text("إغلاق")}})
    }}
}

@Composable fun OwnProfileSigils() {
    val repo=remember {Injekt.get<SigilRepository>()};val snapshot=activeSnapshot(repo);val navigator=LocalNavigator.currentOrThrow
    EquippedSigils(snapshot.slots,onEmpty={navigator.push(SigilCollectionScreen())},onTap={navigator.push(SigilCollectionScreen(it))})
}
@Composable fun PublicProfileSigils(userId: String) {
    val repo=remember {Injekt.get<SigilRepository>()};var slots by remember(userId) {mutableStateOf<List<String?>>(listOf(null,null,null))}
    LaunchedEffect(userId) {slots=repo.publicSlots(userId)}
    EquippedSigils(slots)
}

/** Presented only on Home, never over Reader or Downloads. Dismissible and acknowledged before replay. */
@Composable fun SigilUnlockHost() {
    val repo=remember {Injekt.get<SigilRepository>()};val unlock by repo.announcement.collectAsState()
    val snapshot=activeSnapshot(repo)
    val definition=RealmSigils.byId[unlock?.id]?.takeIf {snapshot.unlocks.any {u->u.id==it.id && !u.revoked}} ?: return
    var appeared by remember(definition.id) {mutableStateOf(false)}
    val motion=rememberSigilMotionAllowed()
    val reveal by animateFloatAsState(if(appeared)1f else 0f,tween(if(motion)1200 else 0,easing=FastOutSlowInEasing),label="sigil-materialize")
    val scale=.4f+.6f*reveal
    androidx.activity.compose.BackHandler {repo.dismissAnnouncement()}
    LaunchedEffect(definition.id) {repo.markAnnouncementPresented();appeared=true}
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.76f)).clickable {repo.dismissAnnouncement()},contentAlignment=Alignment.Center) {
        Column(Modifier.fillMaxWidth(.85f).verticalScroll(rememberScrollState()).background(Brush.radialGradient(listOf(Color(definition.accent).copy(alpha=.15f),Color(0xFF191120))),RoundedCornerShape(28.dp)).padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("إنجاز جديد!",color=Color(0xFFE4C576),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
            Text("لقد فتحت ختمًا جديدًا",color=Color(0xFFD4C5E0))
            Box(Modifier.size(220.dp),contentAlignment=Alignment.Center) {
                if(motion && reveal<1f) SigilOpeningParticles(definition,reveal,Modifier.fillMaxSize())
                SigilArtwork(definition,true,Modifier.fillMaxSize().graphicsLayer {scaleX=scale;scaleY=scale;alpha=scale;rotationZ=if(motion)-6f*(1f-reveal) else 0f})
            }
            Text(definition.name,Modifier.graphicsLayer {alpha=if(motion)reveal else 1f},color=Color.White,style=MaterialTheme.typography.headlineSmall,textAlign=TextAlign.Center)
            Text(definition.objective,color=Color(0xFFB7A9C4),textAlign=TextAlign.Center)
            TextButton(onClick=repo::dismissAnnouncement) {Text("متابعة",color=Color(definition.accent))}
        }
    }
}

@Composable private fun activeSnapshot(repo: SigilRepository): SigilSnapshot {
    val snapshot by repo.state.collectAsState()
    val session by remember {Injekt.get<AccountFoundation>()}.session.collectAsState()
    val owner=when(val s=session) {is AccountSession.Authenticated -> s.profile.userId; AccountSession.Guest -> SigilRepository.GUEST; AccountSession.Loading -> null}
    return if(snapshot.owner==owner && owner!=null) snapshot else SigilSnapshot()
}
