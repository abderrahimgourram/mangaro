package eu.kanade.tachiyomi.novels

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

class NovelReaderProgressPresentationTest {
    @Test fun `production controls show percentage without exposing internal segment fraction`() {
        val root = listOf(File("."), File("..")).first { File(it, "app/src/main").isDirectory }
        val source = File(root, "app/src/main/java/eu/kanade/presentation/novels/NovelReaderScreen.kt").readText()
        val progress = source.substringAfter("private fun ReaderProgress(")
        progress.contains("progress.segment") shouldBe false
        progress.contains("$" + "count") shouldBe false
        progress.contains("$" + "percent%") shouldBe true
        source.contains("ReaderProgress(contentProgress, gold)") shouldBe true
        // This is a source integration guard; end reachability is covered by the layout and emulator suites.
    }
}
