package eu.kanade.presentation.account

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection

/** Only the handle is LTR; the enclosing Arabic layout stays RTL. */
@Composable
fun UsernameHandle(username: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified,
    style: TextStyle = MaterialTheme.typography.labelSmall) {
    Text(isolateUsername("@$username"), modifier, color = color, style = style.copy(textDirection = TextDirection.Ltr))
}

/** Presentation-only isolation. Never use this decorated value in input, validation or storage. */
internal fun isolateUsername(username: String): String = "\u2066$username\u2069"

/** Names retain their own script direction; username fallbacks are always LTR identifiers. */
internal fun profileNameForDisplay(displayName: String?, username: String?): String =
    if (displayName == null || displayName == username) isolateUsername(username.orEmpty())
    else "\u2068$displayName\u2069"
