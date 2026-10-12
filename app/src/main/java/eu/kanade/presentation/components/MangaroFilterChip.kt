package eu.kanade.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.kanade.presentation.theme.MangaroDesignSystem as Design

@Composable
fun MangaroFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        modifier = Modifier.heightIn(min = Design.TouchTarget),
        shape = Design.ShapeChip,
        border = BorderStroke(Design.StrokeSubtle.width, if (selected) Design.GoldBorder else Design.BorderSubtle),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Design.SurfaceDark,
            labelColor = Design.TextSecondary,
            selectedContainerColor = Design.SurfaceHigh,
            selectedLabelColor = Design.GoldPrimary,
        ),
    )
}
