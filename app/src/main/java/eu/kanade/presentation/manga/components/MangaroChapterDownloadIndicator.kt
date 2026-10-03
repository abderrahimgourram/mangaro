package eu.kanade.presentation.manga.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
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

    Box(
        modifier = modifier
            .size(32.dp)
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = null,
                indication = ripple(bounded = false, radius = 16.dp),
                onClick = {
                    onClick(
                        when (state) {
                            Download.State.NOT_DOWNLOADED, Download.State.ERROR -> ChapterDownloadAction.START
                            Download.State.QUEUE, Download.State.DOWNLOADING -> ChapterDownloadAction.CANCEL
                            Download.State.DOWNLOADED -> ChapterDownloadAction.DELETE
                        },
                    )
                },
                onLongClick = {
                    onClick(
                        when (state) {
                            Download.State.NOT_DOWNLOADED, Download.State.ERROR -> ChapterDownloadAction.START_NOW
                            Download.State.QUEUE, Download.State.DOWNLOADING -> ChapterDownloadAction.CANCEL
                            Download.State.DOWNLOADED -> ChapterDownloadAction.DELETE
                        },
                    )
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            Download.State.NOT_DOWNLOADED -> Icon(
                imageVector = Icons.Outlined.ArrowDownward,
                contentDescription = stringResource(MR.strings.manga_download),
                modifier = Modifier.size(16.dp),
                tint = MangaroDesignSystem.LavenderPrimary.copy(alpha = 0.65f),
            )
            Download.State.QUEUE, Download.State.DOWNLOADING -> {
                val rawProgress = downloadProgressProvider()
                if (state == Download.State.QUEUE || rawProgress <= 0) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(17.dp),
                        color = MangaroDesignSystem.LavenderPrimary,
                        strokeWidth = 1.8.dp,
                        trackColor = Color.Transparent,
                    )
                } else {
                    val progress by animateFloatAsState(
                        targetValue = (rawProgress / 100f).coerceIn(0f, 1f),
                        label = "chapterDownloadProgress",
                    )
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.size(17.dp),
                        color = MangaroDesignSystem.LavenderPrimary,
                        strokeWidth = 1.8.dp,
                        trackColor = Color.White.copy(alpha = 0.08f),
                    )
                }
            }
            Download.State.DOWNLOADED -> Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = stringResource(MR.strings.label_downloaded),
                modifier = Modifier.size(16.dp),
                tint = MangaroDesignSystem.GoldPrimary.copy(alpha = 0.82f),
            )
            Download.State.ERROR -> Icon(
                imageVector = Icons.Outlined.Refresh,
                contentDescription = stringResource(MR.strings.chapter_error),
                modifier = Modifier.size(16.dp),
                tint = Color(0xFFD09DAA),
            )
        }
    }
}
