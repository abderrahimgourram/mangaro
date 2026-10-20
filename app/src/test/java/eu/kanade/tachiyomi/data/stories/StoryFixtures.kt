package eu.kanade.tachiyomi.data.stories

import eu.kanade.tachiyomi.novels.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import tachiyomi.domain.manga.model.Manga

internal object StoryFixtures {
    val root: File get() = listOf(File("."),File("..")).first { File(it,"app/src/main").isDirectory }
    val text: String get() = File(root,"app/src/main/res/raw/connected_story_relationships.json").readText()
    val registry get() = VerifiedStoryRelationshipRegistry.parse(text)
    val solo get() = registry.records.first { it.id == "solo-leveling-original" }
    val tbate get() = registry.records.first { it.id == "tbate-original" }
    fun comic(ref: StoryEditionRef, id: Long = 10): Manga = Manga.create().copy(
        id=id,source=ref.source.toLong(),url=ref.url,title=if ("BATE" in ref.url || "beginning" in ref.url) "The Beginning After the End" else if ("overgeared" in ref.url) "أوفرغيرد" else "Solo Leveling",initialized=true,
        thumbnailUrl="https://starzmanga.com/wp-content/uploads/public-cover.jpg")
    fun novel(ref: StoryEditionRef): Novel = Novel(ref.source,ref.url,if ("618" in ref.url) "البداية بعد النهاية" else if ("overgeared" in ref.url) "مدجج بالعتاد" else "رفع المستوى منفرداً",
        cover="https://seanovel.org/covers/solo-leveling.webp",originalTitle="Solo Leveling")
    fun comics(record: StoryRelationship = solo) = record.adaptation.editions.mapIndexed { i,r ->
        StoryCandidate(StoryDestination.Comic(comic(r,10L+i)))
    }
    fun novels(record: StoryRelationship = solo) = record.original.editions.map { StoryCandidate(StoryDestination.Prose(novel(it))) }
    fun state(status: String): VerifiedStoryRelationshipRegistry {
        val doc=Json.parseToJsonElement(text) as JsonObject
        val records=doc.getValue("records") as kotlinx.serialization.json.JsonArray
        return VerifiedStoryRelationshipRegistry.parse(JsonObject(doc+("records" to kotlinx.serialization.json.JsonArray(records.map {
            JsonObject((it as JsonObject)+("status" to JsonPrimitive(status)))
        }))).toString())
    }
}
