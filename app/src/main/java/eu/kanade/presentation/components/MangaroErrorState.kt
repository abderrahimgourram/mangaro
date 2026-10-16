package eu.kanade.presentation.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.theme.MangaroDesignSystem as Design
import eu.kanade.presentation.util.contentErrorMessage

@Composable
fun MangaroErrorState(message: String, retry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Surface(modifier.fillMaxWidth().animateContentSize(), shape = RoundedCornerShape(20.dp),
            color = Design.SurfaceDark, border = Design.StrokeSubtle) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.ErrorOutline, null, tint = Design.LavenderPrimary, modifier = Modifier.size(28.dp))
                Text(message.contentErrorMessage().orEmpty(), color = Design.TextPrimary,
                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                if (retry != null) Button(onClick = retry, modifier = Modifier.heightIn(min = 48.dp),
                    shape = Design.ShapeButton, colors = ButtonDefaults.buttonColors(containerColor = Design.SurfaceHigh, contentColor = Design.GoldPrimary)) {
                    Text("إعادة المحاولة")
                }
            }
        }
    }
}

@Composable
fun MangaroSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        SnackbarHost(hostState, modifier) { data ->
            Snackbar(data, shape = Design.ShapeCard, containerColor = Design.SurfaceHigh,
                contentColor = Design.TextPrimary, actionColor = Design.GoldPrimary,
                dismissActionContentColor = Design.LavenderPrimary)
        }
    }
}

/** Native reader/WebView surfaces share the same quiet error palette as Compose. */
fun android.view.View.showMangaroErrorSnackbar(message: String) {
    val bar = com.google.android.material.snackbar.Snackbar.make(this, message.contentErrorMessage().orEmpty(), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT)
        .setBackgroundTint(0xFF22172B.toInt()).setTextColor(0xFFF3ECF8.toInt()).setActionTextColor(0xFFFFB800.toInt())
    bar.view.layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL
    bar.view.background = android.graphics.drawable.GradientDrawable().apply {
        setColor(0xFF22172B.toInt()); cornerRadius = 14 * resources.displayMetrics.density
    }
    bar.show()
}

fun android.content.Context.showMangaroErrorSnackbar(message: String) {
    val activity = generateSequence(this) { context ->
        (context as? android.content.ContextWrapper)?.baseContext?.takeIf { it !== context }
    }.take(8).filterIsInstance<android.app.Activity>().firstOrNull()
    if (activity != null) {
        if (!activity.isFinishing && !activity.isDestroyed) activity.window.decorView.showMangaroErrorSnackbar(message)
    } else {
        // A receiver/application context has no Snackbar host; keep feedback safe and contextual.
        android.widget.Toast.makeText(this, message.contentErrorMessage(), android.widget.Toast.LENGTH_SHORT).show()
    }
}
