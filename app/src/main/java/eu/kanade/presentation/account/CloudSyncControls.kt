package eu.kanade.presentation.account

import android.text.format.DateUtils
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.theme.MangaroDesignSystem
import mihon.domain.account.AccountActionGate
import mihon.domain.account.AccountCloudSync
import mihon.domain.account.AccountOperation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun CloudSyncControls(userId: String, repository: AccountCloudSync) {
    val status by remember(userId, repository) { repository.observe(userId) }.collectAsState()
    val scope = rememberCoroutineScope()
    val gate = remember(userId) { AccountActionGate() }
    val context = LocalContext.current
    var busy by remember(userId) { mutableStateOf(false) }
    var message by remember(userId) { mutableStateOf<String?>(null) }
    var confirm by rememberSaveable(userId) { mutableStateOf(false) }
    var shown by rememberSaveable(userId) { mutableStateOf(false) }
    fun perform(action: suspend () -> AccountOperation) {
        if (!gate.tryStart()) return
        busy = true
        message = null
        scope.launch {
            try { message = (action() as? AccountOperation.Failed)?.message }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "تعذر المزامنة — سنحاول مرة أخرى" }
            finally { busy = false; gate.finish() }
        }
    }
    LaunchedEffect(userId, status.loaded, status.decisionMade) {
        if (status.loaded && status.error == null && !status.decisionMade && !shown) { shown = true; confirm = true }
    }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MangaroDesignSystem.BackgroundDark) {
        Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("المزامنة السحابية", style = MaterialTheme.typography.titleSmall, color = Color.White)
                Box(Modifier.size(6.dp).background(
                    if (status.enabled) MangaroDesignSystem.GoldPrimary.copy(alpha = 0.7f) else Color(0xFF746580), RoundedCornerShape(3.dp)))
            }
            Text(when {
                status.running || busy -> "تتم المزامنة..."
                !status.loaded -> "جارٍ تجهيز المزامنة"
                !status.enabled -> "بياناتك محفوظة على هذا الجهاز"
                status.pending > 0 -> "توجد تغييرات بانتظار المزامنة أو الاتصال"
                else -> "المزامنة مفعّلة"
            }, style = MaterialTheme.typography.bodySmall, color = Color(0xFFB7A9C4))
            status.lastSuccess?.let {
                val formatted = remember(it, context) { DateUtils.formatDateTime(context, it,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL) }
                Text("آخر مزامنة: $formatted", style = MaterialTheme.typography.labelSmall, color = Color(0xFF9F90AC))
            }
            if (status.enabled && !status.running && status.pending == 0) Text("لا توجد تغييرات معلّقة", style = MaterialTheme.typography.labelSmall, color = Color(0xFF9F90AC))
            if (status.unresolved > 0) Text("بعض الأعمال تتطلب توفر المصدر أو الفصول على هذا الجهاز", style = MaterialTheme.typography.labelSmall, color = Color(0xFF9F90AC))
            (message ?: status.error)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Color(0xFF9F90AC)) }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFB7A9C4)), enabled = status.loaded && !busy, onClick = {
                    if (status.enabled) perform { repository.configure(userId, false) } else confirm = true
                }) { Text(if (status.enabled) "إيقاف المزامنة" else "تفعيل المزامنة") }
                if (status.enabled) TextButton(colors = ButtonDefaults.textButtonColors(contentColor = MangaroDesignSystem.GoldPrimary), enabled = !status.running && !busy, onClick = { perform { repository.syncNow(userId) } }) { Text("مزامنة الآن") }
            }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false; perform { repository.configure(userId, false) } },
        containerColor = MangaroDesignSystem.SurfaceDark,
        title = { Text("المزامنة السحابية") },
        text = { Text("احفظ مكتبتك وسجل القراءة على حسابك واستعدهما على أجهزتك. دمج بيانات هذا الجهاز مع هذا الحساب لا يحذف بياناتك المحلية. التنزيلات تبقى على الجهاز.") },
        confirmButton = { TextButton(enabled = !busy, onClick = { confirm = false; perform { repository.configure(userId, true) } }) { Text("دمج بيانات هذا الجهاز") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { confirm = false; perform { repository.configure(userId, false) } }) { Text("لاحقًا") } })
}
