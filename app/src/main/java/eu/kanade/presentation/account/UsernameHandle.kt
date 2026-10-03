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
    Text("@$username", modifier, color = color, style = style.copy(textDirection = TextDirection.Ltr))
}
