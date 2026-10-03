package eu.kanade.presentation.manga.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.UpIcon
import eu.kanade.presentation.theme.MangaroDesignSystem

@Composable
fun MangaToolbar(
    navigateUp: () -> Unit,
    backgroundAlphaProvider: () -> Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                MangaroDesignSystem.BackgroundDark.copy(
                    alpha = backgroundAlphaProvider().coerceIn(0f, 1f),
                ),
            )
            .statusBarsPadding()
            .height(44.dp),
    ) {
        IconButton(
            onClick = navigateUp,
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            UpIcon()
        }
    }
}
