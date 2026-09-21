package eu.kanade.presentation.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

object MangaroCoverColors {

    /**
     * Generates a restrained, cover-inspired atmospheric gradient brush.
     * Uses deterministic HSL color derivation based on manga metadata,
     * delivering 100% GPU-accelerated rendering with zero main-thread bitmap cost.
     */
    @Composable
    fun rememberAtmosphericGradient(mangaId: Long, title: String): Brush {
        return remember(mangaId, title) {
            val hash = (title.hashCode() xor (mangaId.hashCode())) and 0x7FFFFFFF
            val hue = (hash % 360).toFloat()

            // Subtle dark-saturated tones tailored for Mangaro's premium dark surfaces
            val accentTone = Color.hsl(hue = hue, saturation = 0.32f, lightness = 0.22f)
            val midTone = Color.hsl(hue = (hue + 20f) % 360f, saturation = 0.25f, lightness = 0.14f)
            val darkBase = Color(0xFF120C18)

            Brush.horizontalGradient(
                colors = listOf(
                    darkBase,
                    midTone,
                    accentTone,
                ),
            )
        }
    }
}
