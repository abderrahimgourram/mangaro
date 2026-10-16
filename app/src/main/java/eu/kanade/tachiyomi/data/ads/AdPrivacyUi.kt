package eu.kanade.tachiyomi.data.ads

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Explicit vendor-specific choices; previous Google/web consent is not reused. */
@Composable
fun AdPrivacyDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val ads = remember(context) { InMobiAds.get(context) }
    val privacyError by ads.privacyError.collectAsStateWithLifecycle()
    val links = LocalUriHandler.current
    val activity = remember(context) {
        generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<android.app.Activity>().firstOrNull()
    }
    fun choose(choice: AdPrivacyChoice) {
        ads.setPrivacyChoice(choice)
        if (choice != AdPrivacyChoice.DISABLED) ads.preparePrivacy(activity, showOptions = true)
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = { choose(AdPrivacyChoice.DISABLED) },
        title = { Text("اختيارات الإعلانات") },
        text = { Column {
            Text("هل تسمح بعرض الإعلانات لدعم Mangaro؟ نستخدم InMobi، وقد يعالج معرّف الإعلان وعنوان IP ومعلومات الجهاز والتفاعل. قد يلزم اختيار منفصل من منصة موافقة معتمدة بحسب بلدك؛ هذا الاختيار لا يصنع موافقة IAB ولا يتجاوزها. يمكنك رفض الإعلانات أو تغيير اختيارك من المزيد ← خصوصية الإعلانات. القراءة والتنزيل متاحان دائمًا.")
            if (privacyError != null) Text("تعذّر تحميل أو حفظ اختيارات الخصوصية. الإعلانات متوقفة؛ القراءة والتنزيل متاحان. أعد فتح التطبيق ثم حاول مجددًا.")
            TextButton(onClick = { links.openUri("https://www.inmobi.com/privacy-policy/") }) { Text("سياسة InMobi") }
            TextButton(onClick = { links.openUri("https://mangaro-web.vercel.app/privacy") }) { Text("سياسة Mangaro") }
            TextButton(onClick = { ads.preparePrivacy(activity, showOptions = true); onDismiss() }) { Text("إدارة موافقات الخصوصية") }
            TextButton(onClick = { choose(AdPrivacyChoice.BASIC) }) { Text("إعلانات غير مخصصة") }
        } },
        confirmButton = { TextButton(onClick = { choose(AdPrivacyChoice.PERSONALIZED) }) { Text("أوافق على التخصيص") } },
        dismissButton = { TextButton(onClick = { choose(AdPrivacyChoice.DISABLED) }) { Text("رفض الإعلانات") } },
    )
}

/** CMP starts after the real interface renders. It owns regional prompting and stored decisions. */
@Composable
fun InitializeAdvertisingPrivacy() {
    val context = LocalContext.current
    val ads = remember(context) { InMobiAds.get(context) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val activity = remember(context) {
        generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<android.app.Activity>().firstOrNull()
    }
    LaunchedEffect(ads, lifecycle, activity) {
        (context.applicationContext as? eu.kanade.tachiyomi.App)?.awaitFirstUsableFrame()
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            ads.preparePrivacy(activity)
            // Returning from a CMP/reader never force-opens the consent UI.
            kotlinx.coroutines.awaitCancellation()
        }
    }
}
