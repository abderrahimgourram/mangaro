package eu.kanade.presentation.util

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class MangaroErrorMessageTest {
    @Test fun `raw backend credentials source routes and class names never reach the UI`() {
        "SQL password=secret https://internal/source".contentErrorMessage() shouldBe "تعذر تحميل المحتوى، أعد المحاولة"
        "تعذر الطلب Bearer private-token".contentErrorMessage() shouldBe "تعذر تحميل المحتوى، أعد المحاولة"
        IllegalStateException("access_token=private").mangaroErrorMessage("تعذر مزامنة بياناتك") shouldBe "تعذر مزامنة بياناتك"
    }
    @Test fun `network and wrapped timeout failures have actionable Arabic messages`() {
        UnknownHostException("private-source.example").mangaroErrorMessage() shouldBe "تعذر الاتصال بالإنترنت"
        RuntimeException("secret", SocketTimeoutException("token")).mangaroErrorMessage() shouldBe "استغرق التحميل وقتًا أطول من المتوقع"
        "تعذر تحميل الفصل".contentErrorMessage() shouldBe "تعذر تحميل الفصل"
    }
}
