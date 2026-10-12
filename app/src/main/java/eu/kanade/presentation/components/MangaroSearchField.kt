package eu.kanade.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.theme.MangaroDesignSystem as Design

@Composable
fun MangaroSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    inputModifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val submit = { onSearch(); keyboard?.hide(); focus.clearFocus() }
    Surface(
        modifier = modifier,
        shape = Design.ShapeCard,
        color = Design.SurfaceDark,
        border = BorderStroke(1.dp, if (focused) Design.GoldBorder else Design.BorderSubtle),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Design.TextPrimary, textDirection = TextDirection.ContentOrRtl),
            cursorBrush = SolidColor(Design.GoldPrimary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() }),
            modifier = inputModifier.fillMaxWidth().heightIn(min = 56.dp).onFocusChanged { focused = it.isFocused },
            decorationBox = { input ->
                Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { submit() }, modifier = Modifier.size(Design.TouchTarget)) {
                        Icon(Icons.Outlined.Search, "بحث", tint = Design.GoldPrimary)
                    }
                    Box(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                        if (value.isEmpty()) Text(placeholder, color = Design.TextSecondary, style = MaterialTheme.typography.bodyLarge)
                        input()
                    }
                    if (value.isNotEmpty()) IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(Design.TouchTarget)) {
                        Icon(Icons.Outlined.Close, "مسح البحث", tint = Design.TextSecondary)
                    }
                }
            },
        )
    }
}
