package eu.kanade.presentation.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.formatChapterDisplay
import tachiyomi.domain.history.model.HistoryWithRelations

@Composable
fun MangaroContinueReading(
    history: HistoryWithRelations,
    onResumeClick: () -> Unit,
    onMangaClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(82.dp)
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onMangaClick),
        colors = CardDefaults.cardColors(containerColor = MangaroVisualTokens.SurfaceHigh),
        border = BorderStroke(1.dp, MangaroVisualTokens.CardBorderColor),
        shape = RoundedCornerShape(MangaroVisualTokens.CardCornerRadius),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f),
            ) {
                Box(
                    modifier = Modifier
                        .width(42.dp)
                        .clip(RoundedCornerShape(8.dp)),
                ) {
                    MangaCover.Book(
                        data = history.coverData,
                        contentDescription = history.title,
                    )
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = history.title,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                        ),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Start,
                    )

                    Text(
                        text = formatChapterDisplay(history.chapterNumber),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                        color = MangaroVisualTokens.GoldAccent,
                        maxLines = 1,
                    )
                }
            }

            Button(
                onClick = onResumeClick,
                colors = ButtonDefaults.buttonColors(containerColor = MangaroVisualTokens.PurplePrimary),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                shape = RoundedCornerShape(10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = Color.Black,
                    )
                    Text(
                        text = "متابعة",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.Black,
                    )
                }
            }
        }
    }
}
