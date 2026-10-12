package eu.kanade.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.theme.MangaroDesignSystem as Design

/** Shared frame, with the real source's cover renderer and feature-owned click action. */
@Composable
fun MangaroWorkCard(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    metadata: @Composable ColumnScope.() -> Unit = {},
    cover: @Composable (Modifier) -> Unit,
) {
    val titleLines = if (LocalDensity.current.fontScale >= 1.3f) 3 else 2
    Surface(
        modifier = modifier.clickable(role = Role.Button, onClick = onClick),
        shape = Design.ShapeCard,
        color = Design.SurfaceDark,
        border = Design.StrokeSubtle,
    ) {
        Column(Modifier.padding(6.dp)) {
            cover(Modifier.fillMaxWidth().aspectRatio(Design.CoverAspectRatio))
            Text(
                title,
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                style = Design.CardTitleStyle,
                minLines = titleLines,
                maxLines = titleLines,
                overflow = TextOverflow.Ellipsis,
            )
            Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                metadata()
            }
        }
    }
}
