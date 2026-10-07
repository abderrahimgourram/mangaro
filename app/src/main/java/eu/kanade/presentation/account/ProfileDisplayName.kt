package eu.kanade.presentation.account

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.text.TextDirectionHeuristicsCompat

/** Display-only isolation; content determines direction, with LTR for names containing no strong character. */
internal fun isolateDisplayName(name: String): String {
    val start = if (TextDirectionHeuristicsCompat.FIRSTSTRONG_LTR.isRtl(name, 0, name.length)) "\u2067" else "\u2066"
    return "$start$name\u2069"
}

@Composable
internal fun ProfileDisplayName(
    displayName: String?,
    username: String? = null,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val text = displayName?.let(::isolateDisplayName) ?: profileNameForDisplay(null, username)
    Text(text, modifier, color = color, style = style.copy(textDirection = TextDirection.ContentOrLtr),
        fontWeight = fontWeight, maxLines = maxLines, overflow = overflow)
}
