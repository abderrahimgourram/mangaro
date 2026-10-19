package eu.kanade.tachiyomi.novels

import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.util.zip.GZIPOutputStream

class NovelContentQualityTest {
    @TempDir lateinit var root:File
    private val good=NovelText(List(6){"فتح المسافر الباب ثم قرأ الرسالة وحافظ على وعده قبل أن يعود إلى المدينة القديمة. ".repeat(5)})
    private val bad=NovelText(List(12){"هيس نوت هاد دون هيم وويولد هاو يفين افتير يونديرستاند ويذ فروم ذيس يور هاند واز وات شود كود وود بيينغ ".repeat(4)})
    @Test fun normalArabicRemainsReadableWithoutTextRewriting() {
        NovelContentQuality.assess(good).verdict shouldBe NovelContentQuality.Verdict.READABLE
        for(source in listOf(MknovSource.SOURCE_ID,SeaNovelSource.SOURCE_ID)) validateNovelRepositoryContent(source,good)
    }
    @Test fun knownSeverePatternRequiresSeveralIndependentDensityAndSpreadChecks() {
        val a=NovelContentQuality.assess(bad)
        a.verdict shouldBe NovelContentQuality.Verdict.CORRUPTED_UPSTREAM
        a.reason shouldBe "DENSE_UNQUOTED_PHONETIC_FUNCTION_WORDS"
        for(source in listOf(MknovSource.SOURCE_ID,SeaNovelSource.SOURCE_ID))
            assertThrows<NovelSourceFailure>{validateNovelRepositoryContent(source,bad)}.code shouldBe "NOVEL_TEXT_CORRUPTED"
    }
    @Test fun quotedMultilingualPassagesAndPoetryAreNotDiscarded() {
        val quoted=bad.copy(paragraphs=bad.paragraphs.map {"«$it»"})
        NovelContentQuality.assess(quoted).verdict shouldBe NovelContentQuality.Verdict.UNKNOWN
        NovelContentQuality.requireReadable(quoted)
        val poetry=NovelText(listOf("يا ليل طال بنا المسير حتى رأينا نور الصباح"))
        NovelContentQuality.assess(poetry).verdict shouldBe NovelContentQuality.Verdict.UNKNOWN
    }
    @Test fun properNamesAndIncidentalForeignTextAreNotCorruption() {
        val named=good.copy(paragraphs=good.paragraphs.map {it+" 王小明 김철수 山田太郎 لي王 "})
        NovelContentQuality.assess(named).verdict shouldBe NovelContentQuality.Verdict.UNKNOWN
        NovelContentQuality.requireReadable(named)
        val repeated=good.copy(paragraphs=good.paragraphs.map {it+"عبد王 ".repeat(40)})
        NovelContentQuality.assess(repeated).verdict shouldBe NovelContentQuality.Verdict.UNKNOWN
    }
    @Test fun manyDistinctUnquotedMixedScriptTokensAcrossScriptsAreRejected() {
        val pollution=NovelText(List(4){row->(1..40).joinToString(" "){"عر${('ا'.code+row).toChar()}${('ا'.code+it%20).toChar()}中文한글"}})
        NovelContentQuality.assess(pollution).reason shouldBe "DENSE_INTRA_WORD_SCRIPT_POLLUTION"
    }
    @Test fun encodingDamageIsDistinctFromAnIsolatedReplacementCharacter() {
        NovelContentQuality.assess(good.copy(paragraphs=good.paragraphs+"\ufffd")).verdict shouldBe NovelContentQuality.Verdict.UNKNOWN
        NovelContentQuality.assess(NovelText(List(4){"هذه فقرة تعرضت لتلف الترميز "+"\ufffd".repeat(30)})).reason shouldBe "DENSE_REPLACEMENT_CHARACTERS"
    }
    @Test fun emptyUnknownAndTemporaryFailuresNeverBecomePermanentComicOrWorkIdentity() {
        NovelContentQuality.assess(NovelText(emptyList())).verdict shouldBe NovelContentQuality.Verdict.INCOMPLETE
        NovelContentQuality.assess(NovelText(listOf("Loading"))).verdict shouldBe NovelContentQuality.Verdict.UNKNOWN
        validatedNovelCachedText(MknovSource.SOURCE_ID,NovelText(listOf("Loading"))) shouldBe null
        validatedNovelCachedText("novel.cenele",bad) shouldBe bad
    }
    @Test fun severeCorruptionCannotBecomeANewCompletedDownload() {
        for(source in listOf(MknovSource.SOURCE_ID,SeaNovelSource.SOURCE_ID)) {
            val disk=NovelDownloadDisk(root)
            assertThrows<NovelSourceFailure>{disk.write(source+":work","chapter",bad)}.code shouldBe "NOVEL_TEXT_CORRUPTED"
            disk.complete(source+":work","chapter") shouldBe false
        }
    }
    @Test fun invalidReplacementAndCancellationPreserveExistingGoodPayload() {
        for(source in listOf(MknovSource.SOURCE_ID,SeaNovelSource.SOURCE_ID)) {
            val disk=NovelDownloadDisk(root);val edition=source+":work"
            disk.write(edition,"chapter",good)
            runCatching {disk.write(edition,"chapter",bad)}.isFailure shouldBe true
            runCatching {disk.write(edition,"chapter",good.copy(paragraphs=good.paragraphs.reversed())){false}}.isFailure shouldBe true
            (NovelDownloadDisk(root).read(edition,"chapter")==good) shouldBe true
        }
    }
    @Test fun oldCorruptCacheIsNotReturnedAndOriginalBytesAreRetained() {
        for(source in listOf(MknovSource.SOURCE_ID,SeaNovelSource.SOURCE_ID)) {
            val edition=source+":work";val chapter="chapter"
            val payload=buildJsonObject {
                put("editionId",edition);put("chapterId",chapter)
                put("checksum",novelDigest(Json.encodeToString(bad)));put("text",Json.encodeToJsonElement(bad))
            }
            val file=File(root,"chapters/${novelDigest(edition)}/${novelDigest(chapter)}.json.gz")
            file.parentFile.mkdirs();GZIPOutputStream(file.outputStream()).use{it.write(payload.toString().toByteArray())}
            val original=file.readBytes()
            NovelDownloadDisk(root).read(edition,chapter) shouldBe null
            file.readBytes().contentEquals(original) shouldBe true
            NovelDownloadDisk(root).write(edition,chapter,good)
            (NovelDownloadDisk(root).read(edition,chapter)==good) shouldBe true
        }
    }
    @Test fun cacheRevalidationIsSourceScopedAndCannotLeakBetweenAccounts() {
        validatedNovelCachedText(MknovSource.SOURCE_ID,bad) shouldBe null
        validatedNovelCachedText(SeaNovelSource.SOURCE_ID,bad) shouldBe null
        (validatedNovelCachedText(MknovSource.SOURCE_ID,good)===good) shouldBe true
        novelDownloadRoot(root,"A").equals(novelDownloadRoot(root,"B")) shouldBe false
        novelTextCacheKey("A","edition","chapter").equals(novelTextCacheKey("B","edition","chapter")) shouldBe false
    }
    @Test fun assessmentDoesNotLogOrContainChapterText() {
        val a=NovelContentQuality.assess(good).toString()
        a.contains(good.paragraphs.first()) shouldBe false
        a.contains("فتح المسافر") shouldBe false
    }
}
