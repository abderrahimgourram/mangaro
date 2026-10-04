package eu.kanade.presentation.history.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.presentation.util.formatChapterNumber
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

private val HistoryItemHeight = 132.dp

@Composable
fun HistoryItem(
    history: HistoryWithRelations,
    onClickCover: () -> Unit,
    onClickResume: () -> Unit,
    onClickDelete: () -> Unit,
    onClickFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clickable(onClick = onClickResume)
            .heightIn(min = HistoryItemHeight)
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MangaCover.Book(
            modifier = Modifier.height(96.dp),
            data = history.coverData,
            onClick = onClickCover,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = MaterialTheme.padding.medium, end = MaterialTheme.padding.small),
        ) {
            val textStyle = MaterialTheme.typography.bodyMedium
            Text(
                text = history.title,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = textStyle,
            )
            val readAt = remember(history.readAt) { history.readAt?.let { eu.kanade.presentation.inbox.inboxTime(it.time) } ?: "" }
            Text(
                text = history.chapterName?.takeIf { it.isNotBlank() } ?: if (history.chapterNumber > -1) "الفصل ${formatChapterNumber(history.chapterNumber)}" else "آخر فصل قرأته",
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
                style = textStyle,
            )
            if (history.totalPages > 0) {
                Text("الصفحة ${(history.lastPageRead + 1).coerceIn(1, history.totalPages)} من ${history.totalPages}", style = MaterialTheme.typography.bodySmall)
            } else if (history.lastPageRead >= 0) {
                Text("الصفحة ${history.lastPageRead + 1}", style = MaterialTheme.typography.bodySmall)
            }
            Text("آخر قراءة: $readAt", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onClickResume) { Text("متابعة") }
        }

        if (!history.coverData.isMangaFavorite) {
            IconButton(onClick = onClickFavorite) {
                Icon(
                    imageVector = Icons.Outlined.FavoriteBorder,
                    contentDescription = stringResource(MR.strings.add_to_library),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        IconButton(onClick = onClickDelete) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = stringResource(MR.strings.action_delete),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@PreviewLightDark
@Composable
private fun HistoryItemPreviews(
    @PreviewParameter(HistoryWithRelationsProvider::class)
    historyWithRelations: HistoryWithRelations,
) {
    TachiyomiPreviewTheme {
        Surface {
            HistoryItem(
                history = historyWithRelations,
                onClickCover = {},
                onClickResume = {},
                onClickDelete = {},
                onClickFavorite = {},
            )
        }
    }
}
