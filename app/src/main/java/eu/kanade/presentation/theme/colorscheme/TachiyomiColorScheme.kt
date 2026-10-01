package eu.kanade.presentation.theme.colorscheme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

internal object TachiyomiColorScheme {
    val darkScheme = darkColorScheme(
        primary = Color(0xFFA78BFA), // luminous lavender
        onPrimary = Color(0xFF130822), // deep dark contrast
        primaryContainer = Color(0xFF381F5C),
        onPrimaryContainer = Color(0xFFE2CBF8),
        inversePrimary = Color(0xFF7C3AED),
        secondary = Color(0xFFFFB800), // gold accent
        onSecondary = Color(0xFF261A00),
        secondaryContainer = Color(0xFF4D3600),
        onSecondaryContainer = Color(0xFFFFDF7D),
        tertiary = Color(0xFFFFB800),
        onTertiary = Color(0xFF261A00),
        tertiaryContainer = Color(0xFF4D3600),
        onTertiaryContainer = Color(0xFFFFDF7D),
        background = Color(0xFF0A070F), // deep violet-black background depth
        onBackground = Color(0xFFF3ECF8),
        surface = Color(0xFF140E1B), // dark purple surface
        onSurface = Color(0xFFF3ECF8),
        surfaceVariant = Color(0xFF22172B), // elevated card surface
        onSurfaceVariant = Color(0xFFCBBED5), // muted lavender text
        surfaceTint = Color(0xFFA78BFA),
        inverseSurface = Color(0xFFE8DDF0),
        inverseOnSurface = Color(0xFF140E1B),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        outline = Color(0xFF5D5168), // refined outline contrast
        outlineVariant = Color(0xFF392E45), // subtle divider
        surfaceContainerLowest = Color(0xFF0A070F),
        surfaceContainerLow = Color(0xFF110B18),
        surfaceContainer = Color(0xFF17101E),
        surfaceContainerHigh = Color(0xFF22172B),
        surfaceContainerHighest = Color(0xFF2C1F38),
    )
}
