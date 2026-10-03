package eu.kanade.presentation.manga.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.DownloadDropdownMenu
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.presentation.theme.MangaroDesignSystem
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.SECONDARY_ALPHA
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun ChapterHeader(
    enabled: Boolean,
    chapterCount: Int?,
    missingChapterCount: Int,
    freshness: String? = null,
    newestFirst: Boolean,
    onSortClick: () -> Unit,
    onDownloadClick: ((DownloadAction) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(MR.strings.chapters),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                    )
                    chapterCount?.let {
                        Text(
                            text = pluralStringResource(MR.plurals.manga_num_chapters, count = it, it),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFFB7ABBF),
                            maxLines = 1,
                        )
                    }
                }
                freshness?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFAA9DB5),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.055f))
                    .clickable(enabled = enabled, onClick = onSortClick)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .widthIn(min = 76.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (newestFirst) Icons.Outlined.ArrowDownward else Icons.Outlined.ArrowUpward,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = MangaroDesignSystem.LavenderPrimary,
                )
                Text(
                    text = if (newestFirst) "الأحدث" else "الأقدم",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = Color(0xFFD7CCDF),
                    maxLines = 1,
                )
            }

            onDownloadClick?.let { downloadClick ->
                var downloadExpanded by remember { mutableStateOf(false) }
                Box {
                    IconButton(
                        onClick = { downloadExpanded = true },
                        enabled = enabled,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF24172F)),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Download,
                            contentDescription = stringResource(MR.strings.manga_download),
                            tint = MangaroDesignSystem.GoldPrimary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    DownloadDropdownMenu(
                        expanded = downloadExpanded,
                        onDismissRequest = { downloadExpanded = false },
                        onDownloadClicked = downloadClick,
                    )
                }
            }
        }
        MissingChaptersWarning(missingChapterCount)
    }
}

@Composable
private fun MissingChaptersWarning(count: Int) {
    if (count == 0) {
        return
    }

    Text(
        text = pluralStringResource(MR.plurals.missing_chapters, count = count, count),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error.copy(alpha = SECONDARY_ALPHA),
    )
}
