package eu.kanade.tachiyomi.data.ads

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler

/** External advertising links are opened only by an explicit user action in the browser. */
@Composable
fun AdPrivacyDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val privacy = remember(context) { SmartLinkPrivacy.get(context) }
    val links = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("خصوصية الروابط الإعلانية") },
        text = { Column {
            Text("يفتح زر الإعلان موقعًا خارجيًا في متصفحك. لا توجد إعلانات SDK داخل القارئ.")
            TextButton(onClick = { links.openUri("https://mangaro-web.vercel.app/privacy") }) { Text("سياسة Mangaro") }
        } },
        confirmButton = { TextButton(onClick = { privacy.setEnabled(true); onDismiss() }) { Text("السماح بالروابط") } },
        dismissButton = { TextButton(onClick = { privacy.setEnabled(false); onDismiss() }) { Text("إيقاف الروابط") } },
    )
}
