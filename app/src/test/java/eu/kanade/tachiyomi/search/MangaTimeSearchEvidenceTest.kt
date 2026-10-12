package eu.kanade.tachiyomi.search

import eu.kanade.tachiyomi.source.internal.mangatime.MangaTime
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.RankedSearchResult
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.ChapterListIntegrity
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.fullType

/** Deterministic native-adapter fixtures, not source health/publication authority. */
class MangaTimeSearchEvidenceTest {
    private fun exercise(transient:Boolean):Pair<List<SearchWorkGroup>,List<Int>> = kotlinx.coroutines.runBlocking {
        Injekt.addSingletonFactory(fullType<Json>()){Json{ignoreUnknownKeys=true}}
        val names=mapOf("working" to "Survival Story of a Sword King in a Fantasy World",
            "empty" to "Survival Diary of the Sword King in a Fantasy World")
        val limits=java.util.Collections.synchronizedList(mutableListOf<Int>())
        val gif=java.util.Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")
        val client=OkHttpClient.Builder().addInterceptor{chain->
            val r=chain.request();var status=200
            val input=r.url.queryParameter("input")?.let{Json.parseToJsonElement(it).jsonObject["json"]!!.jsonObject}
            val body=if(r.url.encodedPath.endsWith("getSeriesBySlug")) {
                val key=input!!["slug"]!!.jsonPrimitive.content
                """{"result":{"data":{"json":{"id":"$key","title":"${names[key]}","type":"manhwa","alternativeTitles":[],"coverUrl":"/uploads/fixture-only/$key.gif"}}}}""".toResponseBody()
            } else if(r.url.encodedPath.endsWith("getChapters")) {
                limits+=input!!["limit"]!!.jsonPrimitive.int
                val key=input["seriesId"]!!.jsonPrimitive.content
                if(transient&&key=="empty"){status=503;"Unavailable".toResponseBody()}
                else if(key=="empty") """{"result":{"data":{"json":{"chapters":[],"hasMore":false,"nextCursor":null}}}}""".toResponseBody()
                else """{"result":{"data":{"json":{"chapters":[{"id":"ch1","seriesId":"working","number":1}],"hasMore":true,"nextCursor":"ch1"}}}}""".toResponseBody()
            } else gif.toResponseBody()
            Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(status).message("fixture").body(body).build()
        }.build()
        val source=MangaTime(client)
        fun entry(key:String,id:Long,count:Int):Pair<Manga,List<Chapter>> {
            val m=Manga.create().copy(id=id,source=source.id,url="/manhwa/$key#$key",title=names.getValue(key),initialized=true,
                thumbnailUrl="https://mangatime.org/uploads/fixture-only/$key.gif")
            val rows=(1..count).map{Chapter.create().copy(id=it.toLong(),mangaId=id,url="/$key/$it",name="Chapter $it")}
            return m.copy(memo=ChapterListIntegrity.memo(m,"COMPLETE",rows)) to rows
        }
        val entries=listOf(entry("working",99101,304),entry("empty",99102,999))
        val db=mockk<GetManga>();coEvery{db.await(any())} answers{entries.first{it.first.id==firstArg<Long>()}.first}
        val evidence=MangaSearchEvidence(db,{id->entries.first{it.first.id==id}.second},
            {bytes->javax.imageio.ImageIO.read(bytes.inputStream())!=null})
        val rows=entries.map{RankedSearchResult(source,it.first)};evidence.refresh(rows)
        val external=evidence.candidate(rows.first(),0).copy(id="other-provider",source="other-provider",coverDigest=null)
        SearchWorkSelection().select(rows.map{evidence.candidate(it,0)}+external) to limits
    }
    @Test fun ownedCachedCountsAndRealAdapterProofChooseWorkingVariantWithoutIndexTraversal() {
        val (groups,limits)=exercise(false);val group=groups.single()
        group.members.size shouldBe 3;group.winner.chapters.distinct shouldBe 304
        group.members.first{it.chapters.distinct==999}.chapters.indexUsable shouldBe false
        limits shouldBe listOf(1,1)
    }
    @Test fun transientProbeFailureKeepsVariantAndNeverBecomesConfirmedEmpty() {
        val (groups,limits)=exercise(true);val group=groups.single()
        group.winner.chapters.distinct shouldBe 304;group.provisional shouldBe true
        val retryable=group.members.first{it.chapters.distinct==999}
        retryable.chapters.indexUsable shouldBe null;retryable.chapters.indexUncertain shouldBe true
        limits shouldBe listOf(1,1)
    }
}
