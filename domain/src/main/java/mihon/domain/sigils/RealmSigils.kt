package mihon.domain.sigils

import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.util.Locale

/** Cosmetic collection only. This module has no XP, rank or permission dependencies. */
enum class SigilWorld(val title: String, val accent: Long) {
    GATES("عالم البوابات", 0xFF48D9ED), TOWERS("عالم الأبراج", 0xFFB993FF),
    MURIM("عالم الموريم", 0xFFFF6479), COURTS("عالم القصور", 0xFFEAB9A0),
    MANUSCRIPTS("عالم المخطوطات", 0xFFE4C576), COMMUNITY("عالم المجتمع", 0xFF65D9A6),
}
enum class SigilRarity(val title: String) { COMMON("عادي"), RARE("نادر"), EPIC("ملحمي"), LEGENDARY("أسطوري") }
data class SigilDefinition(val id: String, val name: String, val objective: String, val world: SigilWorld, val required: Int, val motif: Int, val rarity: SigilRarity) {
    val accent: Long get() = palette[motif]
    val description: String get() = stories[motif]
    private companion object {
        val stories = listOf(
            "شرارة صغيرة توقظ عالمًا من الحكايات.",
            "خلف كل باب مغامرة تستحق الاكتشاف.",
            "كل فصل يقرّبك من بوابة جديدة.",
            "من يعبر العوالم يعرف كيف يقود الرحلة.",
            "ألف فصل، وبوابة لا يحرسها إلا المثابرون.",
            "خطوتك الأولى ترسم طريقك نحو النجوم.",
            "تعلو مع كل حكاية، طابقًا بعد طابق.",
            "اجتزت المحن، وما زالت الحكاية مستمرة.",
            "تقرأ ما تخبئه النجوم بين السطور.",
            "قصص كثيرة، وعوالم لا تنتهي.",
            "علامتك تحفظ صفحة تستحق العودة.",
            "بين يديك فنون لا تُترك للنسيان.",
            "طريق الموريم يبدأ بخطوة ثابتة.",
            "صقلت قراءتك حتى استحقت إرث السيف.",
            "لكل مخطوطة مكان في مجلسك.",
            "تفتح أبواب القصر على حكاية حب.",
            "وردة تنمو بين همسات البلاط.",
            "تحرس حكايات العروش من النسيان.",
            "بعض المصائر تستحق بداية أخرى.",
            "تجمع ذاكرة الممالك، حكاية بعد حكاية.",
            "عثرت على جوهرة تستحق مكانًا في مكتبتك.",
            "آثار من عوالم شتى، وكنوز لا تُنسى.",
            "في رفوفك تجد كل حكاية موطنها.",
            "خمسة مسارات تفتح لك أبعادًا جديدة.",
            "حكاياتك معك، حتى حين تنقطع الطرق.",
            "كلمة واحدة تفتح باب النقاش.",
            "حبرك يترك أثرًا بين القرّاء.",
            "بالحوار تتسع دائرة الحكاية.",
            "نجومك ترشد القرّاء إلى عوالم تستحق.",
            "كلماتك تجمع حولها كوكبة من القرّاء.",
        )
        val palette = longArrayOf(
        0xFF70DBFF,0xFF459EEE,0xFF49E8D2,0xFF769CFB,0xFF9FEAFF,
        0xFFAD9BEE,0xFFBF80ED,0xFF8FA9F9,0xFFE0AEFF,0xFFD5C3FF,
        0xFFEA8D88,0xFFDC677F,0xFFFF685C,0xFFE39CAC,0xFFFFB597,
        0xFFE7B49B,0xFFE89FB9,0xFFDFC78F,0xFFCEAAC5,0xFFF0D1BA,
        0xFFE0C77F,0xFFCCA462,0xFFF0D99C,0xFFE6B865,0xFFFFE7A6,
        0xFF7BDCB2,0xFF55CFCC,0xFF98DB98,0xFFA0E0CC,0xFFC5F3BC,
        )
    }
}

object RealmSigils {
    val all: List<SigilDefinition> = buildList {
        fun world(w: SigilWorld, ids: List<String>, names: List<String>, objectives: List<String>, targets: List<Int>) {
            ids.indices.forEach { i -> add(SigilDefinition(ids[i], names[i], objectives[i], w, targets[i], w.ordinal * 5 + i,
                listOf(SigilRarity.COMMON, SigilRarity.RARE, SigilRarity.RARE, SigilRarity.EPIC, SigilRarity.LEGENDARY)[i])) }
        }
        world(SigilWorld.GATES, listOf("gates_awakened","gates_hunter","gates_opener","gates_leader","gates_guardian"),
            listOf("المستيقظ","صياد الزنزانات","فاتح البوابات","قائد الغارات","حارس البوابة الأخيرة"),
            listOf("أكمل أول فصل.","اقرأ 50 فصلًا مختلفًا.","اقرأ 200 فصل مختلف.","اقرأ 500 فصل مختلف.","اقرأ 1000 فصل مختلف."), listOf(1,50,200,500,1000))
        world(SigilWorld.TOWERS, listOf("tower_visitor","tower_climber","tower_survivor","tower_fates","tower_narrator"),
            listOf("زائر الطابق الأول","متسلق البرج","ناجي السيناريو","قارئ المصائر","الراوي العليم"),
            listOf("ابدأ قراءة 3 أعمال مختلفة.","ابدأ قراءة 10 أعمال مختلفة.","اقرأ 10 فصول من كل واحد من 3 أعمال مختلفة.","اقرأ 20 فصلًا من كل واحد من 5 أعمال مختلفة.","اقرأ 20 فصلًا من كل واحد من 10 أعمال مختلفة."), listOf(3,10,3,5,10))
        world(SigilWorld.MURIM, listOf("murim_scroll","murim_keeper","murim_student","murim_heir","murim_master"),
            listOf("حامل المخطوطة","حافظ الفنون السرية","تلميذ الموريم","وريث السيف","سيد الطائفة"),
            listOf("ضع إشارة مرجعية لفصل.","ضع إشارات مرجعية لـ20 فصلًا مختلفًا.","اقرأ 20 فصلًا مختلفًا من أعمال الفنون القتالية.","اقرأ 100 فصل مختلف من أعمال الفنون القتالية.","أنشئ 3 تصنيفات شخصية ونظّم فيها 10 أعمال مختلفة."), listOf(1,20,20,100,13))
        world(SigilWorld.COURTS, listOf("court_visitor","court_rose","court_regent","court_returner","court_historian"),
            listOf("زائر القصر","وردة العرش","وصي العرش","العائد من المصير","مؤرخ الإمبراطوريات"),
            listOf("ابدأ قراءة عمل رومانسي.","اقرأ 30 فصلًا مختلفًا من أعمال رومانسية.","اقرأ 50 فصلًا مختلفًا من أعمال تاريخية.","اقرأ 10 فصول من كل واحد من 3 أعمال عن التناسخ أو العودة بالزمن.","اقرأ 100 فصل مختلف من أعمال رومانسية أو تاريخية."), listOf(1,30,50,3,100))
        world(SigilWorld.MANUSCRIPTS, listOf("archive_gem","archive_relics","archive_keeper","archive_dimensions","archive_volumes"),
            listOf("صياد الجواهر","جامع الآثار","أمين الأرشيف","مفتاح الأبعاد","خازن المجلدات"),
            listOf("أضف أول عمل إلى مكتبتك.","أضف 20 عملًا مختلفًا إلى مكتبتك.","نظّم 10 أعمال مختلفة في تصنيفات شخصية.","اقرأ أعمالًا من 5 أنواع مختلفة.","أكمل تنزيل 100 فصل مختلف."), listOf(1,20,10,5,100))
        world(SigilWorld.COMMUNITY, listOf("social_voice","social_pen","social_council","social_witness","social_revered"),
            listOf("الصوت الأول","صاحب القلم الأبدي","سيد المجلس","شاهد الكوكبات","الموقّر"),
            listOf("انشر تعليقك الأول.","انشر 50 تعليقًا.","انشر 30 ردًا.","قيّم 20 عملًا مختلفًا.","احصل على 200 إعجاب من قرّاء مختلفين على تعليق واحد لك."), listOf(1,50,30,20,200))
    }.also { require(it.size == 30 && it.map(SigilDefinition::id).toSet().size == 30) }
    val byId = all.associateBy(SigilDefinition::id)
}

/** Metadata comparison only; never infer genres from titles or fuzzy substrings. */
object SigilGenres {
    private fun normalize(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        .replace(Regex("[\u0610-\u061A\u064B-\u065F\u0670\u0640]"), "")
        .replace('أ','ا').replace('إ','ا').replace('آ','ا').replace('ى','ي').replace(Regex("[\\p{Z}\\s]+")," ").trim()
    private val aliases = listOf(
        listOf("martial_arts","martial arts","فنون قتالية","الفنون القتالية","فنون القتال"),
        listOf("romance","romantic","رومانسي","رومانسية"), listOf("historical","تاريخي","تاريخية"),
        listOf("reincarnation","تناسخ","التناسخ","اعادة تجسد","إعادة التجسد"),
        listOf("regression","عودة بالزمن","رجوع بالزمن","العودة بالزمن"),
        listOf("action","أكشن"),listOf("fantasy","خيال","فانتازيا"),listOf("comedy","كوميدي","كوميديا"),
        listOf("adventure","مغامرة","مغامرات"),listOf("drama","دراما"),listOf("horror","رعب"),
        listOf("sci_fi","science fiction","sci-fi","خيال علمي"),listOf("mystery","غموض"),
        listOf("slice_of_life","slice of life","شريحة من الحياة"),listOf("sports","رياضة","رياضي"),
        listOf("psychological","نفسي"),listOf("supernatural","خارق للطبيعة"),listOf("school_life","school life","حياة مدرسية"),
    ).flatMap { group -> group.map { normalize(it) to group.first() } }.toMap()
    fun verified(values: List<String>?) = values.orEmpty().mapNotNull { aliases[normalize(it)] }.toSet()
}

@Serializable data class SigilFact(
    val key: String, val kind: String, val work: String = "", val genres: Set<String> = emptySet(),
    val read: Boolean = false, val started: Boolean = false, val bookmarked: Boolean = false,
    val downloaded: Boolean = false, val library: Boolean = false, val organized: Boolean = false,
    val occurredAt: Long? = null,
) {
    fun merge(other: SigilFact): SigilFact {
        require(key == other.key && kind == other.kind && work == other.work)
        return copy(genres = genres + other.genres, read = read || other.read, started = started || other.started,
            bookmarked = bookmarked || other.bookmarked, downloaded = downloaded || other.downloaded,
            library = library || other.library, organized = organized || other.organized,
            occurredAt = listOfNotNull(occurredAt, other.occurredAt).minOrNull())
    }
}
@Serializable data class SigilUnlock(val id: String, val unlockedAt: Long? = null, val revoked: Boolean = false)
@Serializable data class SigilSnapshot(val facts: List<SigilFact> = emptyList(), val unlocks: List<SigilUnlock> = emptyList(),
    val slots: List<String?> = listOf(null,null,null), val community: Map<String,Int> = emptyMap(), val revision: Long = 0, val owner: String? = null)

object SigilProgress {
    fun calculate(facts: Collection<SigilFact>, community: Map<String,Int> = emptyMap()): Map<String,Int> {
        val unique = facts.groupBy { it.kind to it.key }.values.map { group -> group.reduce(SigilFact::merge) }
        val chapters = unique.filter { it.kind == "chapter" }
        val read = chapters.filter(SigilFact::read)
        val works = unique.filter { it.kind == "work" }.associateBy(SigilFact::key)
        fun genres(f: SigilFact) = f.genres + works[f.work]?.genres.orEmpty()
        fun count(vararg g: String) = read.count { genres(it).intersect(g.toSet()).isNotEmpty() }
        val perWork = read.groupingBy(SigilFact::work).eachCount()
        val started = (unique.filter { it.kind == "work" && it.started }.map(SigilFact::key) + read.map(SigilFact::work)).toSet()
        val categories = unique.count { it.kind == "category" }
        val organized = works.values.count(SigilFact::organized)
        return buildMap {
            RealmSigils.all.filter { it.world == SigilWorld.GATES }.forEach { put(it.id, read.size) }
            put("tower_visitor",started.size);put("tower_climber",started.size)
            put("tower_survivor",perWork.count { it.value >= 10 })
            put("tower_fates",perWork.count { it.value >= 20 });put("tower_narrator",perWork.count { it.value >= 20 })
            put("murim_scroll",chapters.count(SigilFact::bookmarked));put("murim_keeper",chapters.count(SigilFact::bookmarked))
            put("murim_student",count("martial_arts"));put("murim_heir",count("martial_arts"))
            put("murim_master",minOf(3,categories)+minOf(10,organized))
            put("court_visitor",started.count { works[it]?.genres?.contains("romance") == true || read.any { f -> f.work == it && "romance" in genres(f) } })
            put("court_rose",count("romance"));put("court_regent",count("historical"))
            put("court_returner",read.filter { genres(it).intersect(setOf("reincarnation","regression")).isNotEmpty() }.groupingBy(SigilFact::work).eachCount().count { it.value >= 10 })
            put("court_historian",count("romance","historical"))
            put("archive_gem",works.values.count(SigilFact::library));put("archive_relics",works.values.count(SigilFact::library))
            put("archive_keeper",organized);put("archive_dimensions",(started.flatMap { works[it]?.genres.orEmpty() }+read.flatMap(::genres)).toSet().size)
            put("archive_volumes",chapters.count(SigilFact::downloaded))
            RealmSigils.all.filter { it.world == SigilWorld.COMMUNITY }.forEach { put(it.id,(community[it.id] ?: 0).coerceAtLeast(0)) }
        }
    }
    fun slotsValid(slots: List<String?>, unlocks: List<SigilUnlock>): Boolean {
        val ids = slots.filterNotNull()
        return slots.size == 3 && ids.size == ids.distinct().size && ids.all { id -> unlocks.any { it.id == id && !it.revoked } && id in RealmSigils.byId }
    }
}

/** Small successful-write hook. Owner is captured by the repository synchronously before asynchronous processing. */
object SigilEvents {
    enum class Kind { READ, STARTED, BOOKMARK, LIBRARY, ORGANIZED, CATEGORIES, DOWNLOADED, COMMUNITY }
    data class Event(val kind: Kind, val id: Long, val mangaId: Long = 0, val owner: String? = null, val evidence: SigilFact? = null)
    @Volatile var captureOwner: (() -> String?)? = null
    @Volatile var capture: ((Event) -> Unit)? = null
    fun emit(kind: Kind, id: Long, mangaId: Long = 0, owner: String? = null, evidence: SigilFact? = null) {
        if (mihon.domain.account.LocalCloudChanges.isRemoteApplication()) return
        try { capture?.invoke(Event(kind,id,mangaId,owner,evidence)) } catch (_: Exception) { /* Never interrupt the user's operation. */ }
    }
}

/** Reuses the existing source-scoped identities without exposing URLs to the cosmetic ledger. */
object SigilEvidence {
    fun chapter(source: Long,mangaUrl: String,chapterUrl: String,memo: kotlinx.serialization.json.JsonObject,
        genres: List<String>?,read: Boolean=false,bookmarked: Boolean=false,downloaded: Boolean=false,at: Long?=null): SigilFact {
        val manga=mihon.domain.community.CommunityMangaKey.fromSource(source,mangaUrl)
        val model=tachiyomi.domain.chapter.model.Chapter.create().copy(url=chapterUrl,memo=memo)
        val key=mihon.domain.community.CommunityChapterKey.fromSource(manga,chapterUrl,
            tachiyomi.domain.chapter.service.ChapterIdentity.remoteIds(model,source).singleOrNull()).value
        return SigilFact(key,"chapter",manga.value,SigilGenres.verified(genres),read=read,bookmarked=bookmarked,downloaded=downloaded,occurredAt=at)
    }
    fun work(source: Long,url: String,genres: List<String>?,started: Boolean=false,library: Boolean=false,organized: Boolean=false,at: Long?=null)=
        SigilFact(mihon.domain.community.CommunityMangaKey.fromSource(source,url).value,"work",genres=SigilGenres.verified(genres),started=started,library=library,organized=organized,occurredAt=at)
}

/** A loaded/preloaded chapter alone is not evidence: only the selected rendered image is. */
object SigilReadObservation {
    fun completed(ready: Boolean,index: Int,lastIndex: Int,selectedIndex: Int?): Boolean =
        ready && lastIndex>=0 && index==lastIndex && selectedIndex==index
}
