package eu.kanade.tachiyomi.novels

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import eu.kanade.presentation.util.Screen

/** Minimal shell around the real preflight, Navigator and reader; no alternate parser or reader. */
class SunovelsAuditActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(eu.kanade.tachiyomi.BuildConfig.SUNOVELS_RUNTIME_AUDIT)
        @Suppress("DEPRECATION") val novel = intent.getSerializableExtra("novel") as Novel
        @Suppress("DEPRECATION") val chapter = intent.getSerializableExtra("chapter") as NovelChapter
        setContent { eu.kanade.presentation.theme.TachiyomiTheme {
            cafe.adriel.voyager.navigator.Navigator(SunovelsAuditEntry(novel, chapter))
        } }
    }
}
private class SunovelsAuditEntry(private val novel: Novel, private val chapter: NovelChapter) : Screen() {
    @Composable override fun Content() {
        val open = eu.kanade.presentation.novels.rememberSunovelsReaderEntry()
        Button(onClick = { open(novel, chapter) }) { Text("متابعة القراءة") }
    }
}
