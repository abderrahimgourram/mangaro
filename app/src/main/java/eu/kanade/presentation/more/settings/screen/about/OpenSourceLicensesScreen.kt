package eu.kanade.presentation.more.settings.screen.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import com.mikepenz.aboutlibraries.ui.compose.variant.LibraryDetailMode
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

class OpenSourceLicensesScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        var showNovelNotices by rememberSaveable { mutableStateOf(false) }
        if (showNovelNotices) {
            val context = LocalContext.current.applicationContext
            val notices by produceState<String?>(null, context) {
                value = withContext(Dispatchers.IO) {
                    runCatching {
                        listOf("novels/ATTRIBUTION.txt", "novels/licenses/NotoNaskhArabic-OFL.txt")
                            .joinToString("\n\n") { path -> context.assets.open(path).bufferedReader().use { it.readText() } }
                    }.getOrElse { "تعذّر تحميل الإشعارات. أغلق النافذة وحاول مجددًا." }
                }
            }
            AlertDialog(
                onDismissRequest = { showNovelNotices = false },
                title = { Text("حقوق الروايات والخط العربي") },
                text = {
                    SelectionContainer {
                        Text(
                            notices ?: "جارٍ تحميل الإشعارات…",
                            Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                        )
                    }
                },
                confirmButton = { TextButton(onClick = { showNovelNotices = false }) { Text("إغلاق") } },
            )
        }
        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.licenses),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            val libraries by produceLibraries(R.raw.aboutlibraries)
            Column(Modifier.fillMaxSize()) {
                LibrariesContainer(
                    libraries = libraries,
                    modifier = Modifier.weight(1f),
                    contentPadding = contentPadding,
                    detailMode = LibraryDetailMode.Sheet,
                )
                TextButton(onClick = { showNovelNotices = true }, modifier = Modifier.fillMaxWidth().padding(bottom = contentPadding.calculateBottomPadding())) {
                    Text("حقوق الروايات والخط العربي")
                }
            }
        }
    }
}
