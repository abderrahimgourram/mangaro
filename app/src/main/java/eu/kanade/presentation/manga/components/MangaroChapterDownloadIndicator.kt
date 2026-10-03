package eu.kanade.presentation.manga.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.theme.MangaroDesignSystem
import eu.kanade.tachiyomi.data.download.model.Download
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun MangaroChapterDownloadIndicator(
    enabled: Boolean,
    downloadStateProvider: () -> Download.State,
    downloadProgressProvider: () -> Int,
    onClick: (ChapterDownloadAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = downloadStateProvider()
    val haptic = LocalHapticFeedback.current
    var menuExpanded by remember { mutableStateOf(false) }
    val background = when (state) {
        Download.State.DOWNLOADED -> MangaroDesignSystem.GoldPrimary.copy(alpha = 0.12f)
        Download.State.QUEUE, Download.State.DOWNLOADING -> MangaroDesignSystem.LavenderPrimary.copy(alpha = 0.11f)
        Download.State.ERROR -> Color(0xFFEF8D8D).copy(alpha = 0.09f)
        Download.State.NOT_DOWNLOADED -> Color(0xFF24172F).copy(alpha = 0.72f)
    }

    Box(
        modifier = modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = null,
                indication = ripple(bounded = true),
                onClick = {
                    when (state) {
                        Download.State.NOT_DOWNLOADED, Download.State.ERROR -> onClick(ChapterDownloadAction.START)
                        Download.State.QUEUE, Download.State.DOWNLOADING, Download.State.DOWNLOADED -> menuExpanded = true
                    }
                },
                onLongClick = {
                    when (state) {
                        Download.State.NOT_DOWNLOADED -> onClick(ChapterDownloadAction.START_NOW)
                        Download.State.QUEUE, Download.State.DOWNLOADING -> onClick(ChapterDownloadAction.CANCEL)
                        Download.State.DOWNLOADED -> menuExpanded = true
                        Download.State.ERROR -> onClick(ChapterDownloadAction.START)
                    }
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            Download.State.NOT_DOWNLOADED -> Icon(
                imageVector = Icons.Outlined.ArrowDownward,
                contentDescription = stringResource(MR.strings.manga_download),
                modifier = Modifier.size(18.dp),
                tint = MangaroDesignSystem.LavenderPrimary.copy(alpha = 0.78f),
            )
            Download.State.QUEUE, Download.State.DOWNLOADING -> {
                val rawProgress = downloadProgressProvider()
                val indeterminate = state == Download.State.QUEUE || rawProgress <= 0
                if (indeterminate) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = MangaroDesignSystem.LavenderPrimary,
                        strokeWidth = 2.dp,
                        trackColor = Color.Transparent,
                    )
                } else {
                    val progress by animateFloatAsState(
                        targetValue = (rawProgress / 100f).coerceIn(0f, 1f),
                        label = "chapterDownloadProgress",
                    )
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.size(22.dp),
                        color = MangaroDesignSystem.LavenderPrimary,
                        strokeWidth = 2.dp,
                        trackColor = Color.White.copy(alpha = 0.08f),
                    )
                }
                Icon(
                    imageVector = Icons.Outlined.ArrowDownward,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = MangaroDesignSystem.LavenderPrimary,
                )
            }
            Download.State.DOWNLOADED -> Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = stringResource(MR.strings.label_downloaded),
                modifier = Modifier.size(18.dp),
                tint = MangaroDesignSystem.GoldPrimary,
            )
            Download.State.ERROR -> Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = stringResource(MR.strings.chapter_error),
                modifier = Modifier.size(18.dp),
                tint = Color(0xFFE4A0A0),
            )
        }

        if (state == Download.State.QUEUE || state == Download.State.DOWNLOADING) {
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(MR.strings.action_start_downloading_now)) },
                    onClick = {
                        onClick(ChapterDownloadAction.START_NOW)
                        menuExpanded = false
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(MR.strings.action_cancel)) },
                    onClick = {
                        onClick(ChapterDownloadAction.CANCEL)
                        menuExpanded = false
                    },
                )
            }
        } else if (state == Download.State.DOWNLOADED) {
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(MR.strings.action_delete)) },
                    onClick = {
                        onClick(ChapterDownloadAction.DELETE)
                        menuExpanded = false
                    },
                )
            }
        }
    }
}
