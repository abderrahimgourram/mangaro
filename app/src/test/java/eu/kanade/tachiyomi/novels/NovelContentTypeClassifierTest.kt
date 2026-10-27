package eu.kanade.tachiyomi.novels

import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.ObjectStreamClass

/** Authored fixtures. Real public identity/reader measurements are named explicitly below. */
class NovelContentTypeClassifierTest {
    private val novel = Novel("novel.cenele", "https://cenele.com/cont/authored-fixture/", "Fixture novel")
    private fun type(format: String) = novel.copy(contentFormat = NovelContentTypeClassifier.declared(listOf(format)))
    private fun classify(value: Novel) = NovelContentTypeClassifier.classify(value).type

    @Test fun explicitManhwaTaxonomyIsComic() {
        classify(
            novel.copy(
                contentFormat = NovelContentTypeClassifier.declared(
                    listOf("مانهوا"),
                    NovelFormatProvenance.SOURCE_TAXONOMY,
                ),
            ),
        ) shouldBe
            NovelContentType.COMIC
    }

    @Test fun explicitMangaFieldIsComic() {
        classify(type("Manga")) shouldBe NovelContentType.COMIC
    }

    @Test fun explicitManhuaFieldIsComic() {
        classify(type("Manhua")) shouldBe NovelContentType.COMIC
    }

    @Test fun explicitTextNovelRemainsNovel() {
        classify(type("Light Novel")) shouldBe NovelContentType.TEXT_NOVEL
    }

    @Test fun proseWithAManhwaAdaptationRemainsNovel() {
        classify(type("novel").copy(description = "A manhwa adaptation of this prose novel exists.")) shouldBe
            NovelContentType.TEXT_NOVEL
    }

    @Test fun descriptionMentioningMangaDoesNotAuthorizeClassification() {
        classify(novel.copy(description = "A novelist who loves manga.")) shouldBe NovelContentType.UNKNOWN
    }

    @Test fun titleCoverCountAndGenreAreNotFormatAuthority() {
        val value = novel.copy(
            title = "Manhwa illustrated manga",
            cover = "https://cenele.com/colorful.webp",
            genres = listOf("manhwa", "action"),
            chapterCount = 33333,
        )
        classify(value) shouldBe NovelContentType.UNKNOWN
        classify(value.copy(chapterCount = 0)) shouldBe NovelContentType.UNKNOWN
    }

    @Test fun inlineIllustrationsDoNotTurnValidatedProseIntoComic() {
        // Ordinary illustrations do not carry the source's sequential-panel format marker.
        NovelContentTypeClassifier.readingFormat(
            NovelContentTypeClassifier.ReadingSample(200, true, 12, 4000, 0),
        ) shouldBe
            NovelContentType.TEXT_NOVEL
    }

    @Test fun confirmedSequentialPanelsWithNoProseAreComic() {
        NovelContentTypeClassifier.readingFormat(NovelContentTypeClassifier.ReadingSample(200, true, 0, 0, 37)) shouldBe
            NovelContentType.COMIC
    }

    @Test fun anImageOnlyResponseWithoutFormatEvidenceStaysUnknown() {
        NovelContentTypeClassifier.readingFormat(NovelContentTypeClassifier.ReadingSample(200, true, 0, 0, 0)) shouldBe
            NovelContentType.UNKNOWN
    }

    @Test fun shortProseIsNotProofOfComicFormat() {
        NovelContentTypeClassifier.readingFormat(NovelContentTypeClassifier.ReadingSample(200, true, 1, 50, 0)) shouldBe
            NovelContentType.UNKNOWN
    }

    @Test fun errorsAndWrongChapterOwnershipNeverBecomeComicEvidence() {
        for (status in listOf(403, 404, 429, 500, 504)) {
            NovelContentTypeClassifier.readingFormat(
                NovelContentTypeClassifier.ReadingSample(status, true, 0, 0, 37),
            ) shouldBe
                NovelContentType.UNKNOWN
        }
        NovelContentTypeClassifier.readingFormat(
            NovelContentTypeClassifier.ReadingSample(200, false, 0, 0, 37),
        ) shouldBe
            NovelContentType.UNKNOWN
    }

    @Test fun unknownDeclaredValueRemainsExplicitlyUnknown() {
        val value = type("Manhwa adaptation available")
        classify(value) shouldBe NovelContentType.UNKNOWN
        NovelContentTypeClassifier.classify(value).reason.startsWith("SOURCE_FORMAT:") shouldBe true
        NovelContentTypeClassifier.eligible(value) shouldBe true
    }

    @Test fun genericTypeAndGenreKeysAreNotDedicatedFormatFields() {
        NovelContentTypeClassifier.jsonFormat(
            buildJsonObject {
                put("type", "manhwa")
                put("genre", "manga")
                put("name", "manga")
            },
        ) shouldBe
            null
    }

    @Test fun dedicatedJsonFieldsAreReadWithoutLookingAtTheDescription() {
        val evidence = NovelContentTypeClassifier.jsonFormat(
            buildJsonObject {
                put("content_format", "novel")
                put("description", "manga")
            },
        )
        evidence!!.type shouldBe NovelContentType.TEXT_NOVEL
    }

    @Test fun conflictingDeclaredFieldsStayUnknown() {
        NovelContentTypeClassifier.jsonFormat(
            buildJsonObject {
                put("content_type", "manhwa")
                put("contentFormat", "novel")
            },
        )!!.type shouldBe
            NovelContentType.UNKNOWN
    }

    @Test fun longOrOversizedMetadataCannotBeTruncatedIntoAFalseComicTag() {
        classify(type("manhwa" + " ".repeat(200) + "adaptation")) shouldBe NovelContentType.UNKNOWN
        NovelContentTypeClassifier.declared(List(9) { "manga" })!!.type shouldBe NovelContentType.UNKNOWN
    }

    @Test fun htmlFormatReadsTheScopedFormatFieldNotAdaptationSynopsis() {
        val card = Jsoup.parseBodyFragment(
            "<article data-content-type=novel><p>manga adaptation</p></article>",
        ).selectFirst("article")
        NovelContentTypeClassifier.htmlFormat(card)!!.type shouldBe NovelContentType.TEXT_NOVEL
    }

    @Test fun leadingTaxonomyReadsOnlyTheFirstControlledLabelToken() {
        NovelContentTypeClassifier.leadingTaxonomy("مانهوا Shadow Slave")!!.type shouldBe NovelContentType.COMIC
        NovelContentTypeClassifier.leadingTaxonomy("رواية shadow slave")!!.type shouldBe NovelContentType.TEXT_NOVEL
        NovelContentTypeClassifier.leadingTaxonomy("  رواية   Anything")!!.type shouldBe NovelContentType.TEXT_NOVEL
    }

    @Test fun leadingTaxonomyRejectsGenericTitlesAndUnknownWords() {
        NovelContentTypeClassifier.leadingTaxonomy("Shadow Slave") shouldBe null
        NovelContentTypeClassifier.leadingTaxonomy("A manhwa adaptation is mentioned here") shouldBe null
        NovelContentTypeClassifier.leadingTaxonomy("عبد الظل") shouldBe null
        NovelContentTypeClassifier.leadingTaxonomy(null) shouldBe null
    }

    @Test fun mergeKeepsKnownFormatAcrossSparseOrUnknownRefreshes() {
        val known = NovelContentTypeClassifier.declared(listOf("مانهوا"), NovelFormatProvenance.SOURCE_TAXONOMY)
        NovelContentTypeClassifier.merge(null, known) shouldBe known
        val unknown = NovelContentTypeClassifier.declared(listOf("Manhwa adaptation available"))
        NovelContentTypeClassifier.merge(unknown, known) shouldBe known
        NovelContentTypeClassifier.merge(known, null) shouldBe known
    }

    @Test fun mergeLetsRecognizedCorrectionAndVerifiedRevocationWin() {
        val previous = NovelContentTypeClassifier.declared(listOf("رواية"))
        val correction = NovelContentTypeClassifier.declared(listOf("مانهوا"), NovelFormatProvenance.SOURCE_TAXONOMY)
        NovelContentTypeClassifier.merge(correction, previous) shouldBe correction
        val revoked = NovelContentFormat(
            NovelContentType.UNKNOWN,
            NovelFormatProvenance.VERIFIED_READER,
            "review-revoked",
            1,
        )
        NovelContentTypeClassifier.merge(revoked, previous) shouldBe revoked
    }

    @Test fun realReviewedCeneleEditionUsesExactIdentityAndReadingEvidence() {
        val observed =
            Novel("novel.cenele", "https://cenele.com/cont/shadow-sl-manhwa/", "Renaming does not change the format")
        val at = NovelContentTypeClassifier.REVIEWED_AT + 1
        NovelContentTypeClassifier.classify(observed, at).type shouldBe NovelContentType.COMIC
        NovelContentTypeClassifier.classify(observed.copy(sourceId = "novel.kolnovel"), at).type shouldBe
            NovelContentType.UNKNOWN
        NovelContentTypeClassifier.classify(observed.copy(url = observed.url + "other/"), at).type shouldBe
            NovelContentType.UNKNOWN
    }

    @Test fun expiredTimeLimitedEvidenceDoesNotBecomePermanentCachePoisoning() {
        val observed = type("manhwa").let {
            it.copy(contentFormat = it.contentFormat!!.copy(observedAt = 100, validUntil = 200))
        }
        NovelContentTypeClassifier.classify(observed, 201).type shouldBe NovelContentType.UNKNOWN
    }

    @Test fun reviewedComicSurvivesRefreshWithinItsEvidenceWindowThenBecomesUnknown() {
        val observed = Novel("novel.cenele", "https://cenele.com/cont/shadow-sl-manhwa/", "Any title")
        repeat(3) {
            NovelContentTypeClassifier.classify(observed, NovelContentTypeClassifier.REVIEWED_AT + it).type shouldBe
                NovelContentType.COMIC
        }
        NovelContentTypeClassifier.classify(observed, NovelContentTypeClassifier.REVIEWED_UNTIL + 1).type shouldBe
            NovelContentType.UNKNOWN
    }

    @Test fun contradictoryOwnerMetadataInvalidatesTheReviewedUrlObservation() {
        val changed =
            Novel(
                "novel.cenele",
                "https://cenele.com/cont/shadow-sl-manhwa/",
                "Any title",
                author = "Different creator",
            )
        NovelContentTypeClassifier.classify(changed, NovelContentTypeClassifier.REVIEWED_AT + 1).type shouldBe
            NovelContentType.UNKNOWN
    }

    @Test fun freshVerifiedTextCanCorrectTheEarlierPublicReadingObservation() {
        val updated = Novel(
            "novel.cenele",
            "https://cenele.com/cont/shadow-sl-manhwa/",
            "Any title",
            contentFormat =
            NovelContentFormat(
                NovelContentType.TEXT_NOVEL,
                NovelFormatProvenance.VERIFIED_READER,
                "validated-prose",
                NovelContentTypeClassifier.REVIEWED_AT + 2,
            ),
        )
        NovelContentTypeClassifier.classify(updated, NovelContentTypeClassifier.REVIEWED_AT + 3).type shouldBe
            NovelContentType.TEXT_NOVEL
    }

    @Test fun weakerProviderLabelDoesNotEraseVerifiedSequentialReadingEvidence() {
        val observed =
            Novel(
                "novel.cenele",
                "https://cenele.com/cont/shadow-sl-manhwa/",
                "Any title",
                contentFormat = NovelContentTypeClassifier.declared(listOf("novel")),
            )
        NovelContentTypeClassifier.classify(observed, NovelContentTypeClassifier.REVIEWED_AT + 1).type shouldBe
            NovelContentType.COMIC
    }

    @Test fun explicitNewVerifiedRevocationDoesNotRequireDeletingAnyRecord() {
        val observed = Novel(
            "novel.cenele",
            "https://cenele.com/cont/shadow-sl-manhwa/",
            "Any title",
            contentFormat =
            NovelContentFormat(
                NovelContentType.UNKNOWN,
                NovelFormatProvenance.VERIFIED_READER,
                "review-revoked",
                NovelContentTypeClassifier.REVIEWED_AT + 1,
            ),
        )
        NovelContentTypeClassifier.classify(observed, NovelContentTypeClassifier.REVIEWED_AT + 2).type shouldBe
            NovelContentType.UNKNOWN
    }

    @Test fun noMutableDecisionCacheCanRetainOldFormatAfterMetadataChanges() {
        repeat(2000) { classify(type("manga").copy(url = novel.url + it)) shouldBe NovelContentType.COMIC }
        classify(type("novel")) shouldBe NovelContentType.TEXT_NOVEL
        classify(novel) shouldBe NovelContentType.UNKNOWN
    }

    @Test fun legacyAndNewJsonPreserveIdentityWithoutMigration() {
        val old = """
            {
              "sourceId": "novel.cenele",
              "url": "https://cenele.com/cont/authored-fixture/",
              "title": "Fixture novel"
            }
        """.trimIndent()
        Json.decodeFromString<Novel>(old) shouldBe novel
        Json.decodeFromString<Novel>(Json.encodeToString(type("novel"))) shouldBe type("novel")
        type("novel").id shouldBe novel.id
    }

    @Test fun optionalFormatMetadataRemainsCompatibleWithExistingSerializableNavigationModels() {
        val bytes = ByteArrayOutputStream().also { b ->
            ObjectOutputStream(b).use { it.writeObject(type("novel")) }
        }.toByteArray()
        ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() } shouldBe type("novel")
    }

    @Test fun javaNavigationRecordWrittenByOriginalHeadStillRestoresWithNoFormatField() {
        val encoded = javaClass.getResource("/novels/content-types/legacy-novel-java.base64")!!.readText().trim()
        val bytes = java.util.Base64.getDecoder().decode(encoded)
        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() } as Novel
        restored.id shouldBe "novel.cenele:https://cenele.com/cont/legacy-serialization-fixture/"
        restored.contentFormat shouldBe null
        ObjectStreamClass.lookup(Novel::class.java).serialVersionUID shouldBe -7532512653567989702L
    }
}
