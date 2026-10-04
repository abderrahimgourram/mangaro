package eu.kanade.presentation.updates

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.shape.RoundedCornerShape
import eu.kanade.presentation.theme.MangaroDesignSystem
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadIndicator
import eu.kanade.presentation.manga.components.DotSeparatorText
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.updates.UpdatesItem
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ListGroupHeader
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.selectedBackground

internal fun LazyListScope.updatesLastUpdatedItem(
    lastUpdated: Long,
) {
    item(key = "updates-lastUpdated") {
        Box(
            modifier = Modifier
                .animateItem(fadeInSpec = null, fadeOutSpec = null)
                .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        ) {
            Text(
                text = "آخر محاولة تحديث: ${relativeTimeSpanString(lastUpdated)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun LazyListScope.updatesUiItems(
    uiModels: List<UpdatesUiModel>,
    selectionMode: Boolean,
    onUpdateSelected: (UpdatesItem, Boolean, Boolean) -> Unit,
    onClickCover: (UpdatesItem) -> Unit,
    onClickUpdate: (UpdatesItem) -> Unit,
    onDownloadChapter: (List<UpdatesItem>, ChapterDownloadAction) -> Unit,
) {
    items(
        items = uiModels,
        contentType = {
            when (it) {
                is UpdatesUiModel.Header -> "header"
                is UpdatesUiModel.Item -> "item"
            }
        },
        key = {
            when (it) {
                is UpdatesUiModel.Header -> "updatesHeader-${it.date}"
                is UpdatesUiModel.Item -> "updates-${it.item.update.mangaId}-${it.item.update.chapterId}"
            }
        },
    ) { item ->
        when (item) {
            is UpdatesUiModel.Header -> {
                ListGroupHeader(
                    modifier = Modifier.animateItem(),
                    text = relativeDateText(item.date),
                )
            }
            is UpdatesUiModel.Item -> {
                val updatesItem = item.item
                UpdatesUiItem(
                    modifier = Modifier.animateItem(),
                    update = updatesItem.update,
                    selected = updatesItem.selected,
                    readProgress = updatesItem.update.lastPageRead
                        .takeIf { !updatesItem.update.read && it > 0L }
                        ?.let {
                            stringResource(
                                MR.strings.chapter_progress,
                                it + 1,
                            )
                        },
                    onLongClick = {
                        onUpdateSelected(updatesItem, !updatesItem.selected, true)
                    },
                    onClick = {
                        when {
                            selectionMode -> onUpdateSelected(updatesItem, !updatesItem.selected, false)
                            else -> onClickUpdate(updatesItem)
                        }
                    },
                    onClickCover = { onClickCover(updatesItem) }.takeIf { !selectionMode },
                    onDownloadChapter = { action: ChapterDownloadAction ->
                        onDownloadChapter(listOf(updatesItem), action)
                    }.takeIf { !selectionMode },
                    downloadStateProvider = updatesItem.downloadStateProvider,
                    downloadProgressProvider = updatesItem.downloadProgressProvider,
                )
            }
        }
    }
}

@Composable
private fun UpdatesUiItem(
    update: UpdatesWithRelations,
    selected: Boolean,
    readProgress: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onClickCover: (() -> Unit)?,
    onDownloadChapter: ((ChapterDownloadAction) -> Unit)?,
    // Download Indicator
    downloadStateProvider: () -> Download.State,
    downloadProgressProvider: () -> Int,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val textAlpha = if (update.read) 0.7f else 1f

    Row(
        modifier = modifier
            .selectedBackground(selected)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    onLongClick()
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
            )
            .heightIn(min = 88.dp)
            .padding(horizontal = MaterialTheme.padding.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MangaCover.Book(
            modifier = Modifier
                .padding(vertical = 8.dp)
                .width(46.dp),
            shape = RoundedCornerShape(8.dp),
            contentDescription = update.mangaTitle,
            data = update.coverData,
            onClick = onClickCover,
        )

        Column(
            modifier = Modifier
                .padding(horizontal = MaterialTheme.padding.medium)
                .weight(1f),
        ) {
            Text(
                text = update.mangaTitle,
                maxLines = 2,
                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                color = LocalContentColor.current.copy(alpha = textAlpha),
                overflow = TextOverflow.Ellipsis,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!update.read) {
                    Icon(
                        imageVector = Icons.Filled.Circle,
                        contentDescription = stringResource(MR.strings.unread),
                        modifier = Modifier
                            .height(8.dp)
                            .padding(end = 4.dp),
                        tint = MangaroDesignSystem.GoldPrimary,
                    )
                }
                if (update.bookmark) {
                    Icon(
                        imageVector = Icons.Filled.Bookmark,
                        contentDescription = stringResource(MR.strings.action_filter_bookmarked),
                        modifier = Modifier
                            .size(14.dp),
                        tint = MangaroDesignSystem.GoldPrimary,
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                }
                Text(
                    text = update.chapterName,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                    color = LocalContentColor.current.copy(alpha = textAlpha),
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(weight = 1f, fill = false),
                )
                if (readProgress != null) {
                    DotSeparatorText()
                    Text(
                        text = readProgress,
                        maxLines = 1,
                        color = LocalContentColor.current.copy(alpha = DISABLED_ALPHA),
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        ChapterDownloadIndicator(
            enabled = onDownloadChapter != null,
            modifier = Modifier.padding(start = 4.dp),
            downloadStateProvider = downloadStateProvider,
            downloadProgressProvider = downloadProgressProvider,
            onClick = { onDownloadChapter?.invoke(it) },
        )
    }
}
