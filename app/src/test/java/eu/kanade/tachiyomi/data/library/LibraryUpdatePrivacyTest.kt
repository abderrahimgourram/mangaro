package eu.kanade.tachiyomi.data.library

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LibraryUpdatePrivacyTest {
    @Test
    fun `public update report contains only friendly guidance and affected manga titles`() {
        formatLibraryUpdateErrors(listOf("عمل عربي", "English manga", "عمل عربي")) shouldBe
            "تعذّر تحديث بعض الأعمال. حاول مجددًا عند توفر الاتصال.\n\n• عمل عربي\n• English manga\n"
    }
}
