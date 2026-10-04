package eu.kanade.presentation.account

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.theme.MangaroDesignSystem
import eu.kanade.tachiyomi.data.account.ProfileShowcaseRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.domain.account.*
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileShowcaseControls(profile: MangaroProfile, account: AccountFoundation) {
    val repository = remember(account) { ProfileShowcaseRepository(account) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var snapshot by remember(profile.userId) { mutableStateOf<ProfileShowcaseRepository.Snapshot?>(null) }
    var local by remember(profile.userId) { mutableStateOf<Map<String, Manga>>(emptyMap()) }
    var error by remember(profile.userId) { mutableStateOf<String?>(null) }
    var open by remember(profile.userId) { mutableStateOf(false) }
    var saving by remember(profile.userId) { mutableStateOf(false) }
    var retry by remember(profile.userId) { mutableIntStateOf(0) }
    val gate = remember(profile.userId) { AccountActionGate() }
    var draft by remember(profile.userId) { mutableStateOf(ShowcaseDraft()) }
    var enabled by remember(profile.userId) { mutableStateOf(false) }
    var tab by remember(profile.userId) { mutableIntStateOf(0) }
    var preview by remember(profile.userId) { mutableStateOf(false) }
    var search by remember(profile.userId) { mutableStateOf("") }
    val sortedLibrary = remember(local) { local.entries.sortedBy { it.value.title } }
    val slots = ProfileIdentity.favoriteSlots(profile.level, profile.role)
    val accent = rankAccent(profile.level)
    LaunchedEffect(profile.userId, retry) {
        try {
            local = withContext(Dispatchers.Default) { Injekt.get<GetLibraryManga>().await().associate { ProfileShowcaseRepository.key(it.manga) to it.manga } }
            snapshot = repository.load(profile.userId)
            error = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "تعذّر تحميل مكتبتك العامة" }
    }
    val metadata = remember(local, snapshot) {
        local.mapValues { (key,manga) -> ProfileShowcaseRepository.Favorite(key,manga.title.take(300)) } + snapshot?.favorites.orEmpty().associateBy { it.manga_key }
    }
    fun cover(key: String): Any? = local[key]?.asMangaCover() ?: metadata[key]?.cover_path?.let { snapshot?.covers?.get(it) }
    Surface(color = MangaroDesignSystem.SurfaceDark, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
                    Text("مكتبتي العامة", style = MaterialTheme.typography.titleSmall)
                    Text(if(snapshot?.enabled==true) "${snapshot?.favorites?.size ?: 0} أعمال معروضة" else "خاصة حتى تختار إظهارها",
                        style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(enabled = snapshot != null && !saving, onClick = {
                    val items=snapshot?.favorites.orEmpty()
                    enabled=snapshot?.enabled ?: false
                    draft=ShowcaseDraft(items.map {it.manga_key},items.filter {it.featured}.map {it.manga_key}.toSet())
                    tab=0;preview=false;search="";error=null;open=true
                }) { Text("تعديل مكتبتي العامة") }
            }
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                RankEmblem(profile.level,Modifier.size(20.dp))
                Text("${snapshot?.favorites?.size ?: 0} / $slots أعمال",color=accent,style=MaterialTheme.typography.labelMedium)
                Text("حتى 3 أعمال مميزة",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall); if (snapshot == null) TextButton(onClick = { retry++ }) { Text("إعادة المحاولة") } }
        }
    }
    if(open) ModalBottomSheet(onDismissRequest={if(!saving) open=false},containerColor=MangaroDesignSystem.SurfaceDark,
        sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).navigationBarsPadding().imePadding().padding(horizontal=18.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                Text("مكتبتي العامة",style=MaterialTheme.typography.titleLarge)
                TextButton(enabled=!saving,onClick={preview=!preview}) {Text(if(preview) "عودة للتعديل" else "معاينة")}
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                Text("إظهار في الملف العام",style=MaterialTheme.typography.bodyMedium)
                Switch(checked=enabled,onCheckedChange={enabled=it},enabled=!saving)
            }
            Text("تظهر الأعمال المختارة والعدد الإجمالي للفصول المحفوظة في حسابك فقط. يبقى سجل القراءة والموضع خاصين.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.padding(vertical=10.dp),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
                RankEmblem(profile.level,Modifier.size(22.dp))
                Text("${draft.keys.size} / $slots أعمال",color=accent,style=MaterialTheme.typography.labelLarge)
                Text("${draft.featured.size} / 3 مميزة",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
            if(preview) {
                LazyColumn(Modifier.weight(1f)) {item {
                    Text(if(enabled) "هكذا ستظهر مكتبتك للآخرين" else "معاينة فقط — مكتبتك غير ظاهرة للآخرين",style=MaterialTheme.typography.labelSmall,color=accent,modifier=Modifier.padding(bottom=12.dp))
                    LibraryShowcase(draft.keys.mapNotNull { key -> metadata[key]?.let {ShowcaseDisplayItem(key,it.title,cover(key),key in draft.featured)} })
                } }
            } else {
                TabRow(selectedTabIndex=tab,containerColor=MangaroDesignSystem.SurfaceDark,contentColor=accent) {
                    listOf("ترتيب العرض","اختيار أعمال").forEachIndexed { i,label -> Tab(selected=tab==i,onClick={tab=i},text={Text(label)}) }
                }
                if(tab==1) OutlinedTextField(search,{search=it},placeholder={Text("ابحث في مكتبتك")},singleLine=true,
                    modifier=Modifier.fillMaxWidth().padding(vertical=8.dp),shape=RoundedCornerShape(12.dp))
                val choices=remember(sortedLibrary,search) {sortedLibrary.filter {it.value.title.contains(search.trim(),ignoreCase=true)}}
                LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(vertical=8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    if(tab==0) {
                        if(draft.keys.isEmpty()) item {
                            Text("اختر أعمالًا من مكتبتك لتكوين رفك العام",style=MaterialTheme.typography.bodyMedium,modifier=Modifier.padding(vertical=20.dp))
                            TextButton(onClick={tab=1}) {Text("اختيار أعمال")}
                        }
                        items(draft.keys,key={it}) { key ->
                            val index=draft.keys.indexOf(key)
                            val item=metadata[key]
                            Row(Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(MangaroDesignSystem.SurfaceHigh).padding(8.dp),
                                horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
                                MangaCover.Book(cover(key),Modifier.width(46.dp),shape=RoundedCornerShape(6.dp))
                                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                                    Text(item?.title.orEmpty(),style=MaterialTheme.typography.bodySmall.copy(textDirection=TextDirection.Content),maxLines=2,overflow=TextOverflow.Ellipsis)
                                    Row(verticalAlignment=Alignment.CenterVertically) {
                                        IconButton(enabled=!saving&&(key in draft.featured||draft.featured.size<3),onClick={draft=draft.feature(key)},modifier=Modifier.size(48.dp)) {
                                            Icon(if(key in draft.featured) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                                                if(key in draft.featured) "إلغاء تمييز العمل" else "تمييز العمل",Modifier.size(20.dp),tint=if(key in draft.featured) MangaroDesignSystem.GoldPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Text(if(key in draft.featured) "عمل مميز" else "${index+1}",style=MaterialTheme.typography.labelSmall)
                                        IconButton(enabled=!saving,onClick={draft=draft.toggle(key,slots)},modifier=Modifier.size(48.dp)) {Icon(Icons.Outlined.Close,"إزالة من العرض",Modifier.size(18.dp))}
                                    }
                                }
                                Column {
                                    IconButton(enabled=!saving&&index>0,onClick={draft=draft.move(key,-1)},modifier=Modifier.size(48.dp)) {Icon(Icons.Outlined.ArrowUpward,"رفع العمل",Modifier.size(18.dp))}
                                    IconButton(enabled=!saving&&index<draft.keys.lastIndex,onClick={draft=draft.move(key,1)},modifier=Modifier.size(48.dp)) {Icon(Icons.Outlined.ArrowDownward,"خفض العمل",Modifier.size(18.dp))}
                                }
                            }
                        }
                    } else {
                        items(choices,key={it.key}) { entry ->
                            val chosen=entry.key in draft.keys
                            Row(Modifier.fillMaxWidth().clickable(enabled=!saving&&(chosen||draft.keys.size<slots)) {draft=draft.toggle(entry.key,slots)}
                                .padding(vertical=5.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                                MangaCover.Book(entry.value.asMangaCover(),Modifier.width(44.dp),shape=RoundedCornerShape(6.dp))
                                Text(entry.value.title,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium.copy(textDirection=TextDirection.Content),maxLines=2,overflow=TextOverflow.Ellipsis)
                                Checkbox(checked=chosen,onCheckedChange=null)
                            }
                        }
                        if(choices.isEmpty()) item {Text(if(local.isEmpty()) "أضف أعمالًا إلى مكتبتك أولًا" else "لا توجد أعمال مطابقة",Modifier.padding(vertical=20.dp))}
                    }
                }
            }
            Button(enabled=!saving&&draft.keys.size<=slots,onClick={
                if(gate.tryStart()) {
                    saving=true;error=null
                    val rows=draft.keys.mapNotNull {key -> metadata[key]?.copy(featured=key in draft.featured)}
                    scope.launch {
                        try {repository.save(context,profile.userId,enabled,rows,local);snapshot=repository.load(profile.userId);open=false}
                        catch(cancelled:CancellationException) {throw cancelled}
                        catch(_:Exception) {error="تعذّر حفظ مكتبتك العامة — حاول مرة أخرى"}
                        finally {saving=false;gate.finish()}
                    }
                }
            },modifier=Modifier.fillMaxWidth().padding(vertical=10.dp)) {Text(if(saving) "جارٍ الحفظ…" else "حفظ العرض")}
        }
    }
}
