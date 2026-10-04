package eu.kanade.presentation.account

import android.text.format.DateUtils
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import mihon.domain.account.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Quiet infrastructure utility; only an actual account-binding ambiguity asks for a decision. */
@Composable
fun CloudSyncControls(userId: String, repository: AccountCloudSync) {
    val status by remember(userId, repository) { repository.observe(userId) }.collectAsState()
    val scope = rememberCoroutineScope()
    val gate = remember(userId) { AccountActionGate() }
    val context = LocalContext.current
    var busy by remember(userId) { mutableStateOf(false) }
    var confirm by remember(userId) { mutableStateOf(false) }
    var message by remember(userId) { mutableStateOf<String?>(null) }
    fun perform(action: suspend () -> AccountOperation) {
        if(!gate.tryStart()) return
        busy=true;message=null
        scope.launch {
            try { message=(action() as? AccountOperation.Failed)?.message }
            catch(cancelled: CancellationException) {throw cancelled}
            catch(_:Exception) {message="تعذر المزامنة — سنحاول مرة أخرى"}
            finally {busy=false;gate.finish()}
        }
    }
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
            Text(when {
                status.needsMerge -> "بيانات هذا الجهاز مرتبطة بحساب آخر"
                status.running || busy -> "تتم المزامنة…"
                !status.loaded || !status.enabled -> "جارٍ تجهيز المزامنة"
                status.error!=null || status.pending>0 -> "بانتظار الاتصال"
                status.lastSuccess!=null -> "تمت المزامنة"
                else -> "المزامنة التلقائية"
            },style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(enabled=status.loaded && !status.running && !busy,onClick={
                if(status.needsMerge) confirm=true else perform { repository.syncNow(userId) }
            }) {Text(if(status.needsMerge) "دمج مع هذا الحساب" else "مزامنة الآن",style=MaterialTheme.typography.labelSmall)}
        }
        status.lastSuccess?.let { time ->
            val formatted=remember(time,context) {DateUtils.formatDateTime(context,time,DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL)}
            Text("آخر مزامنة: $formatted",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        message?.let {Text(it,style=MaterialTheme.typography.labelSmall)}
    }
    if(confirm) AlertDialog(onDismissRequest={confirm=false},title={Text("دمج بيانات هذا الجهاز")},
        text={Text("تحتوي مكتبة هذا الجهاز على بيانات حساب آخر. دمجها مع هذا الحساب يحفظ البيانات المحلية والسحابية ولا يحذف التنزيلات.")},
        confirmButton={TextButton(enabled=!busy,onClick={confirm=false;perform {repository.configure(userId,true)}}) {Text("دمج مع هذا الحساب")}},
        dismissButton={TextButton(onClick={confirm=false}) {Text("لاحقًا")}})
}
