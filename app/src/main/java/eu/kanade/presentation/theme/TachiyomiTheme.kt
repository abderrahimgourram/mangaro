package eu.kanade.presentation.theme

import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import eu.kanade.presentation.theme.colorscheme.TachiyomiColorScheme

@Composable
fun TachiyomiTheme(
    content: @Composable () -> Unit,
) {
    BaseTachiyomiTheme(content = content)
}

@Composable
fun TachiyomiPreviewTheme(
    content: @Composable () -> Unit,
) = BaseTachiyomiTheme(content)

@Composable
private fun BaseTachiyomiTheme(
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
    MaterialExpressiveTheme(
        colorScheme = TachiyomiColorScheme.darkScheme,
        content = content,
    )
    }
}
