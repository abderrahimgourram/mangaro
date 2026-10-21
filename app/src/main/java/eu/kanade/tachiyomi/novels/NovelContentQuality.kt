package eu.kanade.tachiyomi.novels

import java.text.Normalizer

/** Local, conservative format/quality evidence. READABLE is not a translation-quality guarantee.
 * Thresholds are engineering rules calibrated on the reviewed corpus, never probabilities.
 * Nothing here fetches text, rewrites it, changes identity, or stores prose in diagnostics.
 */
internal object NovelContentQuality {
    enum class Verdict { READABLE, INCOMPLETE, CORRUPTED_UPSTREAM, PARSER_FAILURE, ACCESS_UNAVAILABLE, UNKNOWN }
    data class Assessment(val verdict: Verdict, val reason: String, val characters: Int, val words: Int,
        val replacementCharacters: Int, val mixedScriptWords: Int, val phoneticMarkers: Int,
        val distinctMarkers: Int, val affectedParagraphs: Int)
    private val marks = Regex("[\\u064b-\\u065f\\u0670\\u0640]")
    private val words = Regex("[\\p{L}\\p{M}]+")
    // Function words, not character names or genre terminology. Ambiguous common Arabic words
    // such as هي/بي/مي/كان are deliberately absent. A few loanwords never establish corruption.
    private val phonetic = setOf("هيس", "نوت", "هاد", "دون", "هيم", "وويولد", "هاو", "يفين", "افتير",
        "يونديرستاند", "ويذ", "فروم", "ذيس", "يور", "يورس", "هاند", "واز", "وات", "شود", "كود", "وود", "بيينغ")
    private val quotes = setOf('"', '“', '”', '«', '»', '「', '」')
    private fun arabic(c: Char) = c in '\u0621'..'\u064a' || c in '\u066e'..'\u06d3'
    private fun eastAsian(c: Char) = c in '\u3400'..'\u9fff' || c in '\u3040'..'\u30ff' || c in '\uac00'..'\ud7af'

    fun assess(text: NovelText): Assessment {
        var chars = 0; var totalWords = 0; var replacements = 0; var mixed = 0; var markers = 0
        var affected = 0; var unquotedAffected = 0; var mixedParagraphs = 0; var unquotedReplacements = 0
        val mixedNames = mutableSetOf<String>(); val eastScripts = mutableSetOf<Int>()
        val distinct = mutableSetOf<String>()
        for (paragraph in text.paragraphs) {
            chars += paragraph.length; replacements += paragraph.count { it == '\ufffd' }
            if (paragraph.none { it in quotes }) unquotedReplacements += paragraph.count { it == '\ufffd' }
            val normalized = Normalizer.normalize(paragraph, Normalizer.Form.NFKC)
                .replace(marks, "")
            var localMarkers = 0; var localMixed = 0
            words.findAll(normalized).forEach { match ->
                val word = match.value
                totalWords++
                if (word in phonetic) { markers++; localMarkers++; distinct += word }
                if (word.any(::arabic) && word.any(::eastAsian)) {
                    mixed++; localMixed++; if (mixedNames.size < 12) mixedNames += word
                    word.filter(::eastAsian).forEach { eastScripts += when (it) {
                        in '\u3040'..'\u30ff' -> 1
                        in '\uac00'..'\ud7af' -> 2
                        else -> 3
                    } }
                }
            }
            if (localMarkers > 0) { affected++; if (paragraph.none { it in quotes }) unquotedAffected++ }
            if (localMixed > 0 && paragraph.none { it in quotes }) mixedParagraphs++
        }
        val reason = when {
            chars == 0 -> Verdict.INCOMPLETE to "EMPTY_CONTENT"
            // Scattered encoding damage and names/quoted CJK text remain UNKNOWN/eligible.
            unquotedReplacements >= 16 && unquotedReplacements * 100L >= chars -> Verdict.CORRUPTED_UPSTREAM to "DENSE_REPLACEMENT_CHARACTERS"
            mixed >= 30 && mixedNames.size >= 12 && eastScripts.size >= 2 && mixed * 10L >= totalWords && mixedParagraphs >= 3 -> Verdict.CORRUPTED_UPSTREAM to "DENSE_INTRA_WORD_SCRIPT_POLLUTION"
            markers >= 50 && distinct.size >= 8 && markers * 100L >= totalWords * 6L &&
                unquotedAffected >= 8 && affected * 3 >= text.paragraphs.size ->
                Verdict.CORRUPTED_UPSTREAM to "DENSE_UNQUOTED_PHONETIC_FUNCTION_WORDS"
            replacements > 0 || mixed > 0 || markers >= 10 -> Verdict.UNKNOWN to "LIMITED_QUALITY_SIGNALS"
            chars < 200 -> Verdict.UNKNOWN to "SHORT_CONTENT_NOT_PROOF_OF_CORRUPTION"
            else -> Verdict.READABLE to "NO_STRONG_CORRUPTION_SIGNAL"
        }
        return Assessment(reason.first, reason.second, chars, totalWords, replacements, mixed, markers, distinct.size, affected)
    }
    fun requireReadable(text: NovelText) {
        val assessment = assess(text)
        if (assessment.verdict == Verdict.CORRUPTED_UPSTREAM) throw NovelSourceFailure(
            "نص الفصل غير سليم من المصدر. جرّب فصلًا آخر أو أعد المحاولة لاحقًا.",
            assessment.reason, code = "NOVEL_TEXT_CORRUPTED")
    }
}
