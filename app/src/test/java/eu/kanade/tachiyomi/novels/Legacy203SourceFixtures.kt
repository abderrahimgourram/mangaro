package eu.kanade.tachiyomi.novels
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.jsoup.nodes.Document
import okhttp3.HttpUrl.Companion.toHttpUrl
// Exact adapter bodies from Git 2f401d710 (versionCode 21 / 2.0.3), renamed only.
// These replay the old adapters with the current bounded transport; they do not claim old-APK UI execution.
class KolNovel203Fixture(http: NovelHttp) : HtmlNovelSource(http) {
    override val id = "novel.kolnovel"
    override val name = "ملوك الروايات"
    override val baseUrl = "https://kolnovel.com/"
    private fun cards(d: Document, page: Int): NovelPage {
        val entries = d.select(selector("catalogCards","article.maindet")).mapNotNull { card ->
            val a = card.selectFirst(selector("catalogTitle",".mdinfo h2 a, h2 a")) ?: return@mapNotNull null
            if(!NovelHttp.allowed(a.absUrl("href")) || a.absUrl("href").toHttpUrl().host!=baseUrl.toHttpUrl().host) return@mapNotNull null
            a.text().takeIf { it.isNotBlank() }?.let { Novel(id,a.absUrl("href"),it,image(card.selectFirst(selector("catalogCover",".mdthumb img")))) }
        }.distinctBy { it.id }
        return NovelPage(entries, if(next(d)) page+1 else null,genres=genres(d,"a[href*='/genre/']"))
    }
    override suspend fun catalog(page: Int, latest: Boolean, genre: String?) = withContext(Dispatchers.IO) {
        val link = if(genre!=null) genrePage(genre,page) else query("series/", "order" to if(latest) "update" else "popular", "page" to page.toString())
        cards(doc(http.get(link,cache=true),link),page)
    }
    override suspend fun search(query: String, page: Int) = withContext(Dispatchers.IO) {
        val link = query("", "s" to query, "paged" to page.toString())
        cards(doc(http.get(link,cache=true),link),page)
    }
    override suspend fun details(novel: Novel) = withContext(Dispatchers.IO) {
        val d=doc(http.get(novel.url,cache=true),novel.url)
        novel.copy(title=d.selectFirst(selector("detailsTitle","h1.entry-title"))?.text() ?: novel.title,
            cover=image(d.selectFirst(selector("detailsCover",".sertothumb img"))) ?: image(d.selectFirst("meta[property=og:image]")) ?: novel.cover,
            description=d.selectFirst(selector("description",".sersys[itemprop=description]"))?.wholeText()?.trim().orEmpty(),
            author=d.selectFirst("[itemprop=author]")?.text()?.takeIf { it.isNotBlank() },
            status=d.selectFirst(".sertostat, .status")?.text()?.takeIf { it.isNotBlank() },
            genres=d.select(".sertogenre a, a[href*='/genre/']").map { it.text() }.distinct(),
            chapterCount=d.select(".eplister li[data-id]").size.takeIf { it>0 },
            originalTitle=d.selectFirst(selector("originalTitle",".alter"))?.text()?.takeIf {it.isNotBlank()})
    }
    override suspend fun chapters(novel: Novel, page: Int) = withContext(Dispatchers.IO) {
        val d=doc(http.get(novel.url,cache=true),novel.url)
        ChapterPage(chapterLinks(d,selector("chapterLinks",".eplister li[data-id] > a"),selector("chapterTitle",".epl-title"),reverse=true))
    }
    override suspend fun chapter(chapter: NovelChapter) = withContext(Dispatchers.IO) {
        text(doc(http.get(chapter.url),chapter.url),selector("text","#kol_content"))
    }
}

class SeaNovel203Fixture(http: NovelHttp) : HtmlNovelSource(http) {
    override val id = "novel.seanovel"
    override val name = "بحر الروايات"
    override val baseUrl = "https://seanovel.org/"
    private suspend fun list(): List<Novel> {
        val data=Json.parseToJsonElement(http.get(url("api/novels?sort=views"),cache=true)).jsonArray
        if(data.size>1000) throw NovelSourceFailure("تعذّر تحميل الروايات حاليًا.", "Sea catalogue exceeds safe bound")
        return data.mapNotNull { value ->
            val item=value.jsonObject;val slug=item.string("slug");val title=item.string("title_ar").ifBlank { item.string("title_original") }
            if(slug.isBlank() || title.isBlank()) null else Novel(id,url("novels/"+slug),title,url("api/novel/"+slug+"/cover?type=webp"),
                genres=(item["genres"] as? JsonArray)?.map {it.jsonPrimitive.content}.orEmpty())
        }
    }
    override suspend fun catalog(page: Int, latest: Boolean, genre: String?) = withContext(Dispatchers.IO) {
        val catalog=list();val all=if(genre!=null) catalog.filter {genre in it.genres} else catalog;val start=(page-1)*24
        NovelPage(all.drop(start).take(24),if(start+24<all.size) page+1 else null,
            "الروايات المتاحة في فهرس المصدر.",catalog.flatMap {it.genres}.distinct().map {NovelGenre(it,it)})
    }
    override suspend fun search(query: String, page: Int) = withContext(Dispatchers.IO) {
        val all=list().filter { it.title.contains(query,ignoreCase=true) };val start=(page-1)*24
        NovelPage(all.drop(start).take(24),if(start+24<all.size) page+1 else null,"البحث ضمن فهرس المصدر المتاح.")
    }
    override suspend fun details(novel: Novel) = withContext(Dispatchers.IO) {
        val slug=novel.url.toHttpUrl().pathSegments.last { it.isNotBlank() }
        val item=Json.parseToJsonElement(http.get(url("api/novel/"+slug),cache=true)).jsonObject
        novel.copy(title=item.string("title_ar").ifBlank { novel.title },cover=novel.cover ?: url("api/novel/"+slug+"/cover?type=webp"),description=item.string("description"),
            author=item.string("author").takeIf { it.isNotBlank() },status=item.string("status"),
            genres=(item["genres"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty(),
            chapterCount=item["chapters_count"]?.jsonPrimitive?.intOrNull,
            originalTitle=item.string("title_original").takeIf {it.isNotBlank()})
    }
    override suspend fun chapters(novel: Novel, page: Int) = withContext(Dispatchers.IO) {
        val slug=novel.url.toHttpUrl().pathSegments.last { it.isNotBlank() }
        val item=Json.parseToJsonElement(http.get(url("api/novel/"+slug),cache=true)).jsonObject
        val entries=item["chapters"] as? JsonArray ?: throw NovelSourceFailure("تعذّر تحميل الفصول. حاول مجددًا.", "Sea chapter index missing")
        val chapters=entries.mapIndexed { index,value ->
            val ch=value.jsonObject
            NovelChapter(url("novels/"+slug+"/chapters/"+ch.string("id")),ch.string("title"),index)
        }
        ChapterPage(chapters)
    }
    override suspend fun chapter(chapter: NovelChapter) = withContext(Dispatchers.IO) {
        text(doc(http.get(chapter.url),chapter.url),selector("text","article.reader-content"))
    }
}
