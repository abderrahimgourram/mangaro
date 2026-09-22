package eu.kanade.presentation.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.theme.MangaroDesignSystem
import eu.kanade.tachiyomi.ui.home.HomeSourceItem

@Composable
fun MangaroSourceChip(
    source: HomeSourceItem,
    onSourceClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    chipWidth: Dp = 142.dp,
) {
    Surface(
        onClick = { onSourceClick(source.id) },
        shape = RoundedCornerShape(16.dp),
        color = MangaroDesignSystem.SurfaceDark,
        border = BorderStroke(1.dp, MangaroDesignSystem.BorderSubtle),
        tonalElevation = 4.dp,
        modifier = modifier.width(chipWidth),
    ) {
        Row(
            modifier = Modifier
                .background(MangaroDesignSystem.SurfaceCardGradient)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            // Source Avatar Badge
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MangaroDesignSystem.LavenderPrimary.copy(alpha = 0.15f))
                    .border(BorderStroke(1.dp, MangaroDesignSystem.GoldBorder), shape = CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = source.name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    ),
                    color = MangaroDesignSystem.GoldPrimary,
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = source.name,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        textDirection = TextDirection.Content,
                    ),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(2.dp))

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MangaroDesignSystem.LavenderPrimary.copy(alpha = 0.18f))
                        .padding(horizontal = 6.dp, vertical = 1.5.dp),
                ) {
                    Text(
                        text = if (source.lang == "ar") "العربية" else source.lang.uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            textDirection = TextDirection.Content,
                        ),
                        color = MangaroDesignSystem.GoldPrimary,
                    )
                }
            }
        }
    }
}
