package eu.kanade.presentation.reader.mangaro

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.BrightnessMedium
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.kanade.domain.manga.model.readingMode
import eu.kanade.presentation.theme.MangaroDesignSystem
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import tachiyomi.presentation.core.util.collectAsState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MangaroReaderSettingsSheet(
    onDismissRequest: () -> Unit,
    onOpenAdvancedSettings: () -> Unit,
    viewModel: ReaderSettingsViewModel,
) {
    val preferences = viewModel.preferences
    val manga by viewModel.mangaFlow.collectAsState()
    val currentMode = ReadingMode.fromPreference(manga?.readingMode?.toInt())
    val primaryMode = currentMode.toPrimaryMode()

    val customBrightness by preferences.customBrightness.collectAsState()
    val brightness by preferences.customBrightnessValue.collectAsState()
    val fullscreen by preferences.fullscreen.collectAsState()
    val keepScreenOn by preferences.keepScreenOn.collectAsState()
    val cropPager by preferences.cropBorders.collectAsState()
    val cropWebtoon by preferences.cropBordersWebtoon.collectAsState()
    val usesWebtoonCrop = currentMode == ReadingMode.WEBTOON || currentMode == ReadingMode.CONTINUOUS_VERTICAL

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF100B15),
        contentColor = Color(0xFFF1EAF4),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .width(38.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF8F8099).copy(alpha = 0.5f)),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "إعدادات القارئ",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                modifier = Modifier.padding(top = 4.dp),
            )

            ReaderSection(title = "السطوع") {
                SettingSwitchRow(
                    title = "سطوع النظام",
                    subtitle = if (customBrightness) "$brightness%" else "تلقائي",
                    checked = !customBrightness,
                    onCheckedChange = { useSystem -> preferences.customBrightness.set(!useSystem) },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.BrightnessMedium,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (customBrightness) MangaroDesignSystem.GoldPrimary else Color(0xFF81768A),
                    )
                    Slider(
                        value = brightness.toFloat(),
                        onValueChange = { preferences.customBrightnessValue.set(it.toInt()) },
                        valueRange = 0f..100f,
                        enabled = customBrightness,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = MangaroDesignSystem.GoldPrimary,
                            activeTrackColor = MangaroDesignSystem.GoldPrimary,
                            inactiveTrackColor = Color(0xFF55455F),
                            disabledThumbColor = Color(0xFF675D6D),
                            disabledActiveTrackColor = Color(0xFF403747),
                            disabledInactiveTrackColor = Color(0xFF302936),
                        ),
                    )
                    Text(
                        text = if (customBrightness) "$brightness%" else "تلقائي",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = if (customBrightness) MangaroDesignSystem.GoldPrimary else Color(0xFF9A8FA2),
                    )
                }
            }

            ReaderSection(title = "طريقة القراءة") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PrimaryReadingMode.entries.forEach { mode ->
                        ReaderChoice(
                            label = mode.label,
                            selected = primaryMode == mode,
                            onClick = {
                                viewModel.onChangeReadingMode(
                                    when (mode) {
                                        PrimaryReadingMode.PAGES -> currentMode.takeIf {
                                            it == ReadingMode.RIGHT_TO_LEFT || it == ReadingMode.LEFT_TO_RIGHT
                                        } ?: ReadingMode.RIGHT_TO_LEFT
                                        PrimaryReadingMode.VERTICAL -> ReadingMode.VERTICAL
                                        PrimaryReadingMode.LONG_STRIP -> ReadingMode.WEBTOON
                                    },
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                AnimatedVisibility(visible = primaryMode == PrimaryReadingMode.PAGES) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "اتجاه القراءة",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFFBBAFC3),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ReaderChoice(
                                label = "يمين ← يسار",
                                selected = currentMode == ReadingMode.RIGHT_TO_LEFT,
                                onClick = { viewModel.onChangeReadingMode(ReadingMode.RIGHT_TO_LEFT) },
                                modifier = Modifier.weight(1f),
                            )
                            ReaderChoice(
                                label = "يسار → يمين",
                                selected = currentMode == ReadingMode.LEFT_TO_RIGHT,
                                onClick = { viewModel.onChangeReadingMode(ReadingMode.LEFT_TO_RIGHT) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            ReaderSection(title = "العرض") {
                SettingSwitchRow(
                    title = "قص الهوامش",
                    checked = if (usesWebtoonCrop) cropWebtoon else cropPager,
                    onCheckedChange = {
                        if (usesWebtoonCrop) preferences.cropBordersWebtoon.set(it) else preferences.cropBorders.set(it)
                    },
                )
                SettingSwitchRow(
                    title = "ملء الشاشة",
                    checked = fullscreen,
                    onCheckedChange = preferences.fullscreen::set,
                )
                SettingSwitchRow(
                    title = "منع إيقاف الشاشة",
                    checked = keepScreenOn,
                    onCheckedChange = preferences.keepScreenOn::set,
                )
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable {
                        onDismissRequest()
                        onOpenAdvancedSettings()
                    },
                color = Color(0xFF1C1423),
                contentColor = Color.White,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MangaroDesignSystem.LavenderPrimary,
                    )
                    Text(
                        text = "إعدادات إضافية",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = Color(0xFF988AA1),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = MangaroDesignSystem.GoldPrimary.copy(alpha = 0.9f),
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFF19121F),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = Color(0xFFECE4F0),
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF9F93A7),
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF211628),
                checkedTrackColor = MangaroDesignSystem.GoldPrimary,
                uncheckedThumbColor = Color(0xFFB3A8BA),
                uncheckedTrackColor = Color(0xFF3C3045),
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun ReaderChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = if (selected) Color(0xFF6F4B83) else Color(0xFF271D2E),
        contentColor = if (selected) Color.White else Color(0xFFC4B8CB),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

private enum class PrimaryReadingMode(val label: String) {
    PAGES("صفحات"),
    VERTICAL("تمرير عمودي"),
    LONG_STRIP("شريط طويل"),
}

private fun ReadingMode.toPrimaryMode(): PrimaryReadingMode = when (this) {
    ReadingMode.LEFT_TO_RIGHT, ReadingMode.RIGHT_TO_LEFT, ReadingMode.DEFAULT -> PrimaryReadingMode.PAGES
    ReadingMode.VERTICAL -> PrimaryReadingMode.VERTICAL
    ReadingMode.WEBTOON, ReadingMode.CONTINUOUS_VERTICAL -> PrimaryReadingMode.LONG_STRIP
}
