package eu.kanade.presentation.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.tachiyomi.ui.home.HomeDiscoveryItem

@Composable
fun MangaroMangaCard(
    item: HomeDiscoveryItem,
    onMangaClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    cardWidth: Dp = 108.dp,
) {
    Card(
        modifier = modifier
            .width(cardWidth)
            .clickable { onMangaClick(item.mangaId) },
        colors = CardDefaults.cardColors(containerColor = MangaroVisualTokens.SurfaceHigh),
        border = BorderStroke(1.dp, MangaroVisualTokens.CardBorderColor),
        shape = RoundedCornerShape(MangaroVisualTokens.CardCornerRadius),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(modifier = Modifier.padding(6.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(10.dp)),
            ) {
                MangaCover.Book(
                    data = item.coverData,
                    contentDescription = item.title,
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .align(Alignment.BottomCenter)
                        .background(MangaroVisualTokens.CoverCardGradient),
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = item.title,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp,
                ),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = item.sourceName,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = MangaroVisualTokens.GoldAccent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
