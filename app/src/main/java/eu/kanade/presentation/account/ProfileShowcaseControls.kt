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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.novels.NovelDetailsScreen
import eu.kanade.presentation.theme.MangaroDesignSystem
import eu.kanade.tachiyomi.data.account.ProfileShowcaseRepository
import eu.kanade.tachiyomi.data.account.PublicFavoriteResolver
import eu.kanade.tachiyomi.novels.Novel
import eu.kanade.tachiyomi.novels.NovelRepository
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.domain.account.*
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.core.common.util.lang.WesternDigits
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private data class ShowcaseChoice(
    val key: String,
    val title: String,
    val cover: Any?,
    val isNovel: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileShowcaseControls(profile: MangaroProfile, account: AccountFoundation) {
    val repository = remember(account) { ProfileShowcaseRepository(account) }
    val scope = rememberCoroutineScope()
    val navigator = LocalNavigator.current
    val navigationGate = remember(profile.userId) { AccountActionGate() }
    val context = LocalContext.current
    val novelRepo = remember(context) { NovelRepository.get(context) }
    var snapshot by remember(profile.userId) { mutableStateOf<ProfileShowcaseRepository.Snapshot?>(null) }
    var local by remember(profile.userId) { mutableStateOf<Map<String, Manga>>(emptyMap()) }
    var localNovels by remember(profile.userId) { mutableStateOf<Map<String, Novel>>(emptyMap()) }
    var metadata by remember(profile.userId) { mutableStateOf<Map<String, ProfileShowcaseRepository.Favorite>>(emptyMap()) }
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
    val slots = ProfileIdentity.favoriteSlots(profile.level, profile.role)
    val accent = rankAccent(profile.level)

    LaunchedEffect(profile.userId, retry) {
        try {
            val mangaMap = withContext(Dispatchers.Default) { Injekt.get<GetLibraryManga>().await().associate { ProfileShowcaseRepository.key(it.manga) to it.manga } }
            local = mangaMap
            novelRepo.awaitLocal()
            val novelMap = withContext(Dispatchers.Default) { novelRepo.library.value.associate { ProfileShowcaseRepository.key(it.novel) to it.novel } }
            localNovels = novelMap
            val loadedSnapshot = repository.load(profile.userId)
            snapshot = loadedSnapshot

            val mangaMeta = mangaMap.mapValues { (key, manga) -> ProfileShowcaseRepository.Favorite(key, manga.title.take(300), is_novel = false) }
            val novelMeta = mutableMapOf<String, ProfileShowcaseRepository.Favorite>()
            for ((key, novel) in novelMap) {
                val count = novelRepo.indexSnapshot(novel)?.chapters?.size?.takeIf { it > 0 }
                novelMeta[key] = ProfileShowcaseRepository.Favorite(key, novel.title.take(300), is_novel = true, chapter_count = count)
            }
            metadata = mangaMeta + novelMeta + loadedSnapshot.favorites.associateBy { it.manga_key }
            error = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "تعذّر تحميل مكتبتك العامة" }
    }

    fun cover(key: String): Any? = local[key]?.asMangaCover() ?: localNovels[key]?.cover ?: metadata[key]?.cover_path?.let { snapshot?.covers?.get(it) }

    val choices = remember(local, localNovels, search) {
        val mangaChoices = local.map { (key, manga) -> ShowcaseChoice(key, manga.title, manga.asMangaCover(), isNovel = false) }
        val novelChoices = localNovels.map { (key, novel) -> ShowcaseChoice(key, novel.title, novel.cover, isNovel = true) }
        (mangaChoices + novelChoices)
            .filter { it.title.contains(search.trim(), ignoreCase = true) }
            .sortedBy { it.title }
    }

    Surface(color = Color(0xFF1B1523), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("مكتبتي العامة", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Color(0xFFF0E7F7))
                    Text(if (snapshot?.enabled == true) "أعمالك التي يراها القرّاء" else "خاصة حتى تختار إظهارها",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(enabled = snapshot != null && !saving, onClick = {
                    val items = snapshot?.favorites.orEmpty()
                    enabled = snapshot?.enabled ?: false
                    draft = ShowcaseDraft(items.map { it.manga_key }, items.filter { it.featured }.map { it.manga_key }.toSet())
                    tab = 0; preview = false; search = ""; error = null; open = true
                }, modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFE3D5EF), containerColor = Color(0xFF291D35)),
                    contentPadding = PaddingValues(horizontal = 12.dp)) { Text("تعديل العرض", style = MaterialTheme.typography.labelMedium) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${WesternDigits.isolate("${snapshot?.favorites?.size ?: 0} / $slots")} أعمال", color = accent, style = MaterialTheme.typography.labelMedium)
                Text("حتى 3 أعمال مميزة", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (snapshot?.enabled == true && snapshot?.favorites.orEmpty().isNotEmpty()) {
                LibraryShowcase(snapshot!!.favorites.map {
                    val isNovel = it.is_novel || localNovels.containsKey(it.manga_key)
                    val chapterCount = it.chapter_count ?: metadata[it.manga_key]?.chapter_count
                    ShowcaseDisplayItem(it.manga_key, it.title, cover(it.manga_key), it.featured, isNovel = isNovel, chapterCount = chapterCount)
                }, showHeader = false, onOpen = { key ->
                    val item = snapshot?.favorites?.firstOrNull { it.manga_key == key }
                    if (item != null && navigator != null && navigationGate.tryStart()) scope.launch {
                        try {
                            val manga = local[key] ?: PublicFavoriteResolver().resolve(key, item.title)
                            val novel = localNovels[key]
                            if ((account.session.value as? AccountSession.Authenticated)?.profile?.userId == profile.userId) {
                                if (manga != null) navigator.push(MangaScreen(manga.id))
                                else if (novel != null) navigator.push(NovelDetailsScreen(novel))
                                else error = "يتطلب توفر المحتوى على هذا الجهاز"
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { error = "تعذّر فتح العمل الآن" }
                        finally { navigationGate.finish() }
                    }
                })
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall); if (snapshot == null) TextButton(onClick = { retry++ }) { Text("إعادة المحاولة") } }
        }
    }
    if (open) ModalBottomSheet(onDismissRequest = { if (!saving) open = false }, containerColor = MangaroDesignSystem.SurfaceDark,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).navigationBarsPadding().imePadding().padding(horizontal = 18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("مكتبتي العامة", style = MaterialTheme.typography.titleLarge)
                TextButton(enabled = !saving, onClick = { preview = !preview }) { Text(if (preview) "عودة للتعديل" else "معاينة") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("إظهار في الملف العام", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = enabled, onCheckedChange = { enabled = it }, enabled = !saving)
            }
            Text("تظهر الأعمال المختارة والعدد الإجمالي للفصول المحفوظة في حسابك فقط. يبقى سجل القراءة والموضع خاصين.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                RankEmblem(profile.level, Modifier.size(22.dp))
                Text("${WesternDigits.isolate("${draft.keys.size} / $slots")} أعمال", color = accent, style = MaterialTheme.typography.labelLarge)
                Text("${WesternDigits.isolate("${draft.featured.size} / 3")} مميزة", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (preview) {
                LazyColumn(Modifier.weight(1f)) { item {
                    Text(if (enabled) "هكذا ستظهر مكتبتك للآخرين" else "معاينة فقط — مكتبتك غير ظاهرة للآخرين", style = MaterialTheme.typography.labelSmall, color = accent, modifier = Modifier.padding(bottom = 12.dp))
                    LibraryShowcase(draft.keys.mapNotNull { key -> metadata[key]?.let {
                        val isNovel = it.is_novel || localNovels.containsKey(key)
                        val chapterCount = it.chapter_count ?: metadata[key]?.chapter_count
                        ShowcaseDisplayItem(key, it.title, cover(key), key in draft.featured, isNovel = isNovel, chapterCount = chapterCount)
                    } })
                } }
            } else {
                TabRow(selectedTabIndex = tab, containerColor = MangaroDesignSystem.SurfaceDark, contentColor = accent) {
                    listOf("ترتيب العرض", "اختيار أعمال").forEachIndexed { i, label -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) }) }
                }
                if (tab == 1) OutlinedTextField(search, { search = it }, placeholder = { Text("ابحث في مكتبتك") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), shape = RoundedCornerShape(12.dp))
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (tab == 0) {
                        if (draft.keys.isEmpty()) item {
                            Text("اختر أعمالًا من مكتبتك لتكوين رفك العام", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 20.dp))
                            TextButton(onClick = { tab = 1 }) { Text("اختيار أعمال") }
                        }
                        items(draft.keys, key = { it }) { key ->
                            val index = draft.keys.indexOf(key)
                            val item = metadata[key]
                            val isNovel = item?.is_novel == true || localNovels.containsKey(key)
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MangaroDesignSystem.SurfaceHigh).padding(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                MangaCover.Book(cover(key), Modifier.width(46.dp), shape = RoundedCornerShape(6.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (isNovel) Surface(color = MangaroDesignSystem.GoldPrimary.copy(alpha = 0.18f), shape = RoundedCornerShape(4.dp)) {
                                            Text("رواية", Modifier.padding(horizontal = 4.dp, vertical = 1.dp), color = MangaroDesignSystem.GoldPrimary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                        }
                                        Text(item?.title.orEmpty(), style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(enabled = !saving && (key in draft.featured || draft.featured.size < 3), onClick = { draft = draft.feature(key) }, modifier = Modifier.size(48.dp)) {
                                            Icon(if (key in draft.featured) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                                                if (key in draft.featured) "إلغاء تمييز العمل" else "تمييز العمل", Modifier.size(20.dp), tint = if (key in draft.featured) MangaroDesignSystem.GoldPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Text(if (key in draft.featured) "عمل مميز" else "${index + 1}", style = MaterialTheme.typography.labelSmall)
                                        IconButton(enabled = !saving, onClick = { draft = draft.toggle(key, slots) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Close, "إزالة من العرض", Modifier.size(18.dp)) }
                                    }
                                }
                                Column {
                                    IconButton(enabled = !saving && index > 0, onClick = { draft = draft.move(key, -1) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.ArrowUpward, "رفع العمل", Modifier.size(18.dp)) }
                                    IconButton(enabled = !saving && index < draft.keys.lastIndex, onClick = { draft = draft.move(key, 1) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.ArrowDownward, "خفض العمل", Modifier.size(18.dp)) }
                                }
                            }
                        }
                    } else {
                        items(choices, key = { it.key }) { entry ->
                            val chosen = entry.key in draft.keys
                            Row(Modifier.fillMaxWidth().clickable(enabled = !saving && (chosen || draft.keys.size < slots)) { draft = draft.toggle(entry.key, slots) }
                                .padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                MangaCover.Book(entry.cover, Modifier.width(44.dp), shape = RoundedCornerShape(6.dp))
                                Column(Modifier.weight(1f)) {
                                    if (entry.isNovel) Surface(color = MangaroDesignSystem.GoldPrimary.copy(alpha = 0.18f), shape = RoundedCornerShape(4.dp)) {
                                        Text("رواية", Modifier.padding(horizontal = 4.dp, vertical = 1.dp), color = MangaroDesignSystem.GoldPrimary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                    }
                                    Text(entry.title, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                Checkbox(checked = chosen, onCheckedChange = null)
                            }
                        }
                        if (choices.isEmpty()) item { Text(if (local.isEmpty() && localNovels.isEmpty()) "أضف أعمالًا إلى مكتبتك أولًا" else "لا توجد أعمال مطابقة", Modifier.padding(vertical = 20.dp)) }
                    }
                }
            }
            Button(enabled = !saving && draft.keys.size <= slots, onClick = {
                if (gate.tryStart()) {
                    saving = true; error = null
                    val rows = draft.keys.mapNotNull { key -> metadata[key]?.copy(featured = key in draft.featured) }
                    scope.launch {
                        try { repository.save(context, profile.userId, enabled, rows, local, localNovels); snapshot = repository.load(profile.userId); open = false }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { error = "تعذّر حفظ مكتبتك العامة — حاول مرة أخرى" }
                        finally { saving = false; gate.finish() }
                    }
                }
            }, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) { Text(if (saving) "جارٍ الحفظ…" else "حفظ العرض") }
        }
    }
}
