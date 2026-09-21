package eu.kanade.presentation.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    cardWidth: Dp = 118.dp,
) {
    val gradientBrush = MangaroCoverColors.rememberAtmosphericGradient(
        mangaId = item.mangaId,
        title = item.title,
    )

    val accessibilityLabel = "${item.title} من ${item.sourceName}"

    Card(
        modifier = modifier
            .width(cardWidth)
            .mangaroPressAndEntranceMotion(
                key = item.mangaId,
                onClick = { onMangaClick(item.mangaId) },
            )
            .semantics { contentDescription = accessibilityLabel },
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, MangaroVisualTokens.CardBorderColor),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradientBrush)
                .padding(6.dp),
        ) {
            // Prominent 2:3 Cover Poster
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .shadow(4.dp, RoundedCornerShape(10.dp))
                    .clip(RoundedCornerShape(10.dp))
                    .border(
                        BorderStroke(1.dp, Color(0x33A78BFA)),
                        RoundedCornerShape(10.dp),
                    ),
            ) {
                MangaCover.Book(
                    data = item.coverData,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Bottom subtle shadow overlay over cover
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .align(Alignment.BottomCenter)
                        .background(MangaroVisualTokens.CoverCardGradient),
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Manga Title
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                ),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
            )

            Spacer(modifier = Modifier.height(3.dp))

            // Source Name (No chapter information displayed)
            Text(
                text = item.sourceName,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MangaroVisualTokens.GoldAccent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
            )
        }
    }
}
