package eu.kanade.presentation.account

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.theme.MangaroDesignSystem
import eu.kanade.tachiyomi.data.account.ProfileShowcaseRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import mihon.domain.account.*
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.Manga
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
    var selected by remember(profile.userId) { mutableStateOf<List<ProfileShowcaseRepository.Favorite>>(emptyList()) }
    var enabled by remember(profile.userId) { mutableStateOf(false) }
    val slots = ProfileIdentity.favoriteSlots(profile.level, profile.role)
    LaunchedEffect(profile.userId, retry) {
        try {
            local = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Injekt.get<GetLibraryManga>().await().associate { ProfileShowcaseRepository.key(it.manga) to it.manga } }
            snapshot = repository.load(profile.userId)
            error = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "تعذّر تحميل المفضلة العامة" }
    }
    Surface(color = MangaroDesignSystem.SurfaceDark, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("المكتبة العامة", style = MaterialTheme.typography.titleSmall)
                TextButton(enabled = snapshot != null && !saving, onClick = {
                    enabled = snapshot?.enabled ?: false; selected = snapshot?.favorites.orEmpty(); open = true
                }) { Text("إدارة المفضلة") }
            }
            Text(if (snapshot?.enabled == true) "${snapshot?.favorites?.size ?: 0} أعمال ظاهرة في ملفك العام" else "خاصة — لا تظهر أعمالك للآخرين",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("حتى $slots أعمال مفضلة · يزداد الحد مع تقدمك", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall); if (snapshot == null) TextButton(onClick = { retry++ }) { Text("إعادة المحاولة") } }
        }
    }
    if (open) ModalBottomSheet(onDismissRequest = { if (!saving) open = false },
        containerColor = MangaroDesignSystem.SurfaceDark, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp)) {
            Text("أعمالي المفضلة", style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("إظهار المكتبة العامة")
                Switch(enabled = !saving, checked = enabled, onCheckedChange = { enabled = it })
            }
            Text("عند التفعيل تظهر المفضلة وعدد الفصول المحفوظة في حسابك فقط. يبقى السجل والموضع خاصين. المزامنة تُدرج قراءة هذا الجهاز في العدد.", style = MaterialTheme.typography.bodySmall)
            Text("${selected.size} / $slots", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 10.dp))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            val choices = remember(local, selected) {
                (selected + local.entries.sortedBy { it.value.title }.map { ProfileShowcaseRepository.Favorite(it.key, it.value.title.take(300)) }).distinctBy { it.manga_key }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                items(choices, key = { it.manga_key }) { item ->
                    val chosen = selected.any { it.manga_key == item.manga_key }
                    Row(Modifier.fillMaxWidth().clickable(enabled = !saving && (chosen || selected.size < slots)) {
                        selected = if (chosen) selected.filterNot { it.manga_key == item.manga_key } else selected + item
                    }.padding(vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = chosen, onCheckedChange = null)
                        Text(item.title, Modifier.weight(1f).padding(start = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (choices.isEmpty()) item { Text("أضف أعمالًا إلى مكتبتك لاختيار المفضلة", Modifier.padding(vertical = 20.dp)) }
            }
            Button(enabled = !saving && selected.size <= slots, onClick = {
                if (gate.tryStart()) {
                    saving = true; error = null
                    scope.launch {
                        try {
                            repository.save(context, profile.userId, enabled, selected, local)
                            snapshot = repository.load(profile.userId); open = false
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { error = "تعذّر حفظ المفضلة — حاول مرة أخرى" }
                        finally { saving = false; gate.finish() }
                    }
                }
            }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Text(if (saving) "جارٍ الحفظ…" else "حفظ")
            }
        }
    }
}
