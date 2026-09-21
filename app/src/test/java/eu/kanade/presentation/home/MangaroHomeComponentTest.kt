package eu.kanade.presentation.home

import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MangaroHomeComponentTest {

    @Test
    fun `verify Mangaro visual tokens radii and design values`() {
        MangaroVisualTokens.CardCornerRadius shouldBe 14.dp
        MangaroVisualTokens.FeaturedBannerRadius shouldBe 20.dp
        MangaroVisualTokens.ChipCornerRadius shouldBe 10.dp
    }
}
