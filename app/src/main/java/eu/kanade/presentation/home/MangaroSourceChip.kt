package eu.kanade.presentation.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.ui.home.HomeSourceItem

@Composable
fun MangaroSourceChip(
    source: HomeSourceItem,
    onSourceClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    chipWidth: Dp = 112.dp,
) {
    Card(
        modifier = modifier
            .width(chipWidth)
            .clickable { onSourceClick(source.id) },
        colors = CardDefaults.cardColors(containerColor = MangaroVisualTokens.SurfaceHigh),
        border = BorderStroke(1.dp, MangaroVisualTokens.CardBorderColor),
        shape = RoundedCornerShape(MangaroVisualTokens.ChipCornerRadius),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = source.name,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                ),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (source.lang == "ar") "العربية" else source.lang.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = MangaroVisualTokens.GoldAccent,
            )
        }
    }
}
