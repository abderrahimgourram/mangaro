package eu.kanade.tachiyomi.search

import java.text.Normalizer
import java.util.Locale

/** Exact historical identity policy at ebfc1c26a, evaluation only; never used in production. */
internal object HistoricalSearchIdentity {
    data class Thresholds(val exactCharacters: Int = 10, val exactTokens: Int = 2, val fuzzySimilarity: Double = .92,
        val sameSourceTitleTokens: Int = 6, val sameSourceTokenDice: Double = .83)
    private val unknown = setOf("unknown", "anonymous", "غير معروف", "مجهول", "n a", "none")
    private val generic = setOf("the", "a", "of", "king", "dark", "الملك", "المظلم", "رواية", "novel")
    private val qualifiers = Regex("\\b(?:\\d+|ii|iii|iv|v|vi|wn|ln|sequel|prequel|remake|reboot|adaptation|season|part|volume|vol|side story|fan fiction)\\b|(?:الجزء|الموسم|المجلد|تكملة|اقتباس|جانبية)\\s*\\S*")
    private val trusted = setOf("anilist", "myanimelist", "mangadex", "mangaupdates", "kitsu", "novelupdates")
    private val marks=Regex("\\p{M}+");private val alefs=Regex("[أإآٱ]")
    private val punctuation=Regex("[^\\p{L}\\p{N}]+");private val spaces=Regex("\\s+")
    private val normalized=java.util.concurrent.ConcurrentHashMap<String,String>()
    fun normalize(value: String): String {
        if(normalized.size>2048)normalized.clear()
        return normalized.getOrPut(value) { Normalizer.normalize(value, Normalizer.Form.NFKD)
            .replace(marks, "").replace("ـ", "").replace(alefs, "ا").replace('ى','ي')
            .lowercase(Locale.ROOT).replace(punctuation, " ").trim().replace(spaces, " ") }
    }
    private fun name(value: String, kind: SearchWorkKind) = normalize(value).let { if(kind==SearchWorkKind.NOVEL) it.removePrefix("رواية ") else it }
    private fun names(c:SearchWorkCandidate) = (c.aliases+c.title).flatMap { listOf(it)+it.split('|') }
        .map { name(it,c.kind) }.filter {it.isNotBlank()}.toSet()
    private val titleGlue = setOf("a", "an", "the", "of", "in", "to", "and", "for")
    internal fun possibleSameSourceTitles(a:SearchWorkCandidate,b:SearchWorkCandidate,t:Thresholds=Thresholds()):Boolean {
        if (a.id==b.id || a.source!=b.source || a.kind!=b.kind) return false
        val x=name(a.title,a.kind).split(' ').filter{it !in titleGlue}.toSet()
        val y=name(b.title,b.kind).split(' ').filter{it !in titleGlue}.toSet()
        return minOf(x.size,y.size)>=t.sameSourceTitleTokens && 2.0*x.intersect(y).size/(x.size+y.size)>=t.sameSourceTokenDice
    }
    fun enrichmentCandidates(input:List<SearchWorkCandidate>):Set<String> = (
        input.flatMap { c -> names(c).filter{it.length>=5}.map{(c.kind to it) to c} }.groupBy{it.first}
            .values.filter{rows->rows.map{it.second.id}.distinct().size>1}.flatten().map{it.second.id} +
            input.filter {a->input.any{b->possibleSameSourceTitles(a,b)}}.map{it.id}).toSet()
    private fun declared(s:String?) = s?.let(::normalize)?.takeIf { it.isNotBlank() && it !in unknown }
    private fun creator(s:String?) = declared(s)?.takeIf {it.length>=3}
    private fun contradictory(a:String?,b:String?) = declared(a)?.let {x -> declared(b)?.let {x!=it} }==true
    fun matches(a:SearchWorkCandidate,b:SearchWorkCandidate,t:Thresholds=Thresholds()):Boolean {
        if(a.id==b.id)return true
        if(a.kind!=b.kind || a.title.isBlank() || b.title.isBlank())return false
        if(contradictory(a.format,b.format)||contradictory(a.edition,b.edition)||
            a.year!=null&&b.year!=null&&a.year!=b.year)return false
        val qa=names(a).flatMap {qualifiers.findAll(it).map{m->m.value}.toList()}.toSet()
        val qb=names(b).flatMap {qualifiers.findAll(it).map{m->m.value}.toList()}.toSet()
        if(qa!=qb)return false
        val ids=a.externalIds.keys.intersect(b.externalIds.keys).intersect(trusted).filter {k->
            listOf(a.externalIds[k],b.externalIds[k]).all {v->!v.isNullOrBlank()&&normalize(v) !in unknown&&v!="0"}
        }
        if(ids.any {a.externalIds[it]!=b.externalIds[it]})return false
        if(ids.any {!a.externalIds[it].isNullOrBlank()&&a.externalIds[it]==b.externalIds[it]})return true
        val mainA=name(a.title,a.kind);val mainB=name(b.title,b.kind)
        // A shared franchise alias cannot erase a meaningful subtitle/sequel suffix.
        if(mainA!=mainB && (mainA.startsWith(mainB+" ")||mainB.startsWith(mainA+" ")) &&
            !(a.aliases.any{name(it,a.kind)==mainB}&&b.aliases.any{name(it,b.kind)==mainA}))return false
        if(contradictory(a.author,b.author)||contradictory(a.artist,b.artist))return false
        val verifiedArt=a.coverDigest?.matches(Regex("[0-9a-f]{64}"))==true && a.coverDigest==b.coverDigest &&
            declared(a.format)!=null && declared(a.format)==declared(b.format)
        // A byte-verified identical cover only corroborates a long, distinctive title.
        // Neither a shared URL/filename nor cover resemblance is identity authority.
        if (possibleSameSourceTitles(a,b,t) && verifiedArt) return true
        val supported=listOf(a.author to b.author,a.artist to b.artist).any {(x,y)->creator(x)!=null&&creator(x)==creator(y)}
        val an=names(a);val bn=names(b);val exact=an.intersect(bn)
        if(exact.any {supported || it.length>=t.exactCharacters&&it.split(' ').count {word->word !in generic}>=t.exactTokens}) {
            // A provider can catalogue genuinely different editions under one name.
            // Same-provider equality alone needs a creator or independently verified art.
            return a.source!=b.source || mainA!=mainB || supported ||
                verifiedArt
        }
        // Fuzzy titles require a corroborated creator AND an independent matching year/format.
        if(!supported || !(a.year!=null&&a.year==b.year || creator(a.format)!=null&&creator(a.format)==creator(b.format)))return false
        return an.any {x->bn.any {y->
            val xx=x.split(' ').toSet();val yy=y.split(' ').toSet()
            xx.size>=3&&yy.size>=3&&xx.intersect(yy).size.toDouble()/xx.union(yy).size>=t.fuzzySimilarity
        }}
    }
}

