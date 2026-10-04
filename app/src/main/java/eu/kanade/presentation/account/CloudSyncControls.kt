package eu.kanade.presentation.account

import android.text.format.DateUtils
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.theme.MangaroDesignSystem
import mihon.domain.account.AccountCloudSync
import kotlinx.coroutines.launch

@Composable
fun CloudSyncControls(userId:String,repository:AccountCloudSync) {
    val status by remember(userId,repository) {repository.observe(userId)}.collectAsState()
    val scope=rememberCoroutineScope()
    var confirm by remember(userId) {mutableStateOf(false)}
    var shown by remember(userId) {mutableStateOf(false)}
    LaunchedEffect(userId,status.loaded,status.decisionMade) {if(status.loaded && !status.decisionMade && !shown) {shown=true;confirm=true}}
    Column(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text("المزامنة السحابية",style=MaterialTheme.typography.titleSmall)
        Text(if(status.enabled) "مفعّلة" else "بياناتك محفوظة على هذا الجهاز",style=MaterialTheme.typography.bodySmall,color=Color(0xFFB7A9C4))
        status.lastSuccess?.let {Text("آخر مزامنة: "+DateUtils.getRelativeTimeSpanString(it),style=MaterialTheme.typography.labelSmall)}
        if(status.unresolved>0)Text("بعض الأعمال تتطلب توفر المصدر أو الفصول على هذا الجهاز",style=MaterialTheme.typography.labelSmall)
        status.error?.let {Text(it,style=MaterialTheme.typography.labelSmall)}
        Row {
            TextButton(onClick={if(status.enabled)scope.launch {repository.configure(userId,false)} else confirm=true}) {Text(if(status.enabled)"إيقاف المزامنة" else "تفعيل المزامنة")}
            if(status.enabled)TextButton(enabled=!status.running,onClick={scope.launch {repository.syncNow(userId)}}) {Text(if(status.running)"جارٍ المزامنة" else "مزامنة الآن")}
        }
    }
    if(confirm)AlertDialog(onDismissRequest={confirm=false;scope.launch {repository.configure(userId,false)}},containerColor=MangaroDesignSystem.SurfaceDark,
        title={Text("المزامنة السحابية")},text={Text("احفظ مكتبتك وسجل القراءة على حسابك واستعدهما على أجهزتك. دمج بيانات هذا الجهاز مع هذا الحساب لا يحذف بياناتك المحلية. التنزيلات تبقى على الجهاز.")},
        confirmButton={TextButton(onClick={confirm=false;scope.launch {repository.configure(userId,true)}}) {Text("دمج بيانات هذا الجهاز")}},
        dismissButton={TextButton(onClick={confirm=false;scope.launch {repository.configure(userId,false)}}) {Text("لاحقًا")}})
}
