package eu.kanade.tachiyomi.data.ads

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.kanade.tachiyomi.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.LocalAccountScope as Account

/** Stable batch gate. The existing queue receives work only through its original callback. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberDownloadAdGate(readerActive: Boolean = false): (DownloadAdRequest, () -> Unit) -> Unit {
    val context=LocalContext.current
    val activity=remember(context) {generateSequence(context) {(it as? ContextWrapper)?.baseContext}.filterIsInstance<Activity>().firstOrNull()}
    val controller=remember(context) {SmartLinkController.get(context)}
    val scope=rememberCoroutineScope()
    val account by Account.changes.collectAsStateWithLifecycle()
    val state by controller.state.collectAsStateWithLifecycle()
    val problem by controller.problem.collectAsStateWithLifecycle()
    var target by rememberSaveable {mutableStateOf<String?>(null)}
    var pendingOwner by rememberSaveable {mutableStateOf<String?>(null)}
    var continuation by remember {mutableStateOf<OnceAction?>(null)}
    var busy by remember {mutableStateOf(false)}
    var error by rememberSaveable {mutableStateOf<String?>(null)}
    val token=remember(account.token) {account.token}
    LaunchedEffect(account.token,account.ready) {
        if(!account.ready || pendingOwner!=null && pendingOwner!=SmartLinkController.owner(account.token)) {target=null;continuation=null}
    }
    fun proceed() {
        if (state.batches[target]?.takeIf { it.owner==SmartLinkController.owner(token) }?.unlocked != true) return
        val next=continuation ?: return
        try {Account.check(token);next.run();target=null;continuation=null}
        catch(_:Account.Changed) {target=null;continuation=null}
        catch(_:Exception) {continuation=null;error="تعذّر بدء التنزيل؛ أغلق اللوحة وحدد الدفعة نفسها لإعادة المحاولة، وتقدم فتحها محفوظ"}
    }
    fun closeGate() {controller.abandon(target,token);target=null;continuation=null}
    val batch=state.batches[target]?.takeIf {account.ready && it.owner==SmartLinkController.owner(account.token)}
    LaunchedEffect(target,batch,problem) {
        if(target!=null && batch==null && problem!=null) {
            target=null;continuation=null
            android.widget.Toast.makeText(context,"تعذّر استعادة فتح الدفعة؛ لم يبدأ التنزيل. أعد المحاولة",android.widget.Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(target,batch?.unlocked,continuation) {
        if (target!=null && batch?.unlocked==true && continuation!=null) proceed()
    }
    if(BuildConfig.SMARTLINK_V3_ENABLED && target!=null && batch!=null) CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalBottomSheet(onDismissRequest={closeGate()},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
            Column(Modifier.fillMaxWidth().padding(horizontal=24.dp).navigationBarsPadding().padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Text("فتح التنزيل",style=MaterialTheme.typography.headlineSmall)
                Surface(shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.secondaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text("${batch.returnedActions} / ${batch.required}",style=MaterialTheme.typography.headlineLarge.copy(textDirection=androidx.compose.ui.text.style.TextDirection.Ltr))
                        LinearProgressIndicator(progress={batch.returnedActions.toFloat()/batch.required},modifier=Modifier.fillMaxWidth())
                        Text("افتح الإعلانات المطلوبة لبدء التنزيل")
                    }
                }
                Text("إعلان خارجي · Adsterra · asiafilm.org",style=MaterialTheme.typography.bodySmall)
                (error ?: problem)?.let {Text(it,color=MaterialTheme.colorScheme.error)}
                if(continuation==null) Text("أعد تحديد الفصول لاستئناف التنزيل")
                if(batch.unlocked) Button(onClick={proceed()},enabled=continuation!=null && !busy,modifier=Modifier.fillMaxWidth()) {Text("بدء التنزيل")}
                else Button(onClick={
                    if(!busy) {busy=true;scope.launch {
                        try {error=controller.launch(activity,batch.id,token)}
                        catch(c:CancellationException) {throw c}
                        catch(_:Exception) {error="تعذّر فتح الرابط؛ لم تُحتسب خطوة. استخدم الرجوع للخروج أو أعد المحاولة"}
                        finally {busy=false}
                    }}
                },enabled=!busy && state.pending==null,modifier=Modifier.fillMaxWidth()) {Text("فتح إعلان")}

            }
        }
    }
    return {request, action ->
        if(!busy && target==null && request.unique.isNotEmpty()) {
            val captured=Account.capture()
            if(!BuildConfig.SMARTLINK_V3_ENABLED) {
                busy=true
                val once=OnceAction {try {Account.check(captured);action()} finally {busy=false}}
                once.run()
            } else {
                busy=true
                var invokingQueue=false
                val continueOnce=OnceAction {invokingQueue=true;Account.check(captured);action()}
                scope.launch {
                    try {
                        Account.check(captured)
                        val prepared=controller.batch(request,captured)
                        pendingOwner=SmartLinkController.owner(captured)
                        continuation=continueOnce
                        error=null
                        if(prepared.unlocked) { continuation?.run();continuation=null }
                        else target=prepared.id
                    } catch(c:CancellationException) {throw c}
                    catch(_:Exception) {
                        // An unavailable ad journal cannot authorize a new batch. Existing queue/files are untouched.
                        continuation=null
                        android.widget.Toast.makeText(context,if(invokingQueue) "تعذّر بدء التنزيل؛ أعد تحديد الدفعة للمحاولة مجددًا" else "تعذّر حفظ تقدم فتح الدفعة؛ أعد المحاولة. لم يبدأ التنزيل",android.widget.Toast.LENGTH_LONG).show()
                    } finally {busy=false}
                }
            }
        }
    }
}
