package mihon.domain.community

import java.time.LocalDate
import java.time.temporal.IsoFields
import java.security.MessageDigest

/** Public aggregate only; no rater identities or private restoration metadata. */
data class CommunityRankedWork(val mangaKey: String, val average: Double, val count: Long, val score: Double)
data class WeeklyPick(val key: String, val rating: CommunityRankedWork? = null)
object WeeklyCommunityPicks {
    fun week(date: LocalDate): String = "${date.get(IsoFields.WEEK_BASED_YEAR)}-W${date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR).toString().padStart(2, '0')}"
    fun weighted(average: Double, count: Long, mean: Double, confidence: Double): Double =
        (count * average + confidence * mean) / (count + confidence)
    fun select(ranked: List<CommunityRankedWork>, candidates: List<String>, week: String): List<WeeklyPick> {
        val eligible = candidates.filter { it.matches(Regex("[a-f0-9]{64}")) }.distinct().toSet()
        val community = ranked.filter { it.mangaKey in eligible && it.count >= 2 && it.average in 1.0..5.0 && it.score.isFinite() }
            .distinctBy { it.mangaKey }.sortedWith(compareByDescending<CommunityRankedWork> { it.score }.thenByDescending { it.count }
                .thenByDescending { it.average }.thenBy { it.mangaKey }).take(5)
        val excluded = community.map { it.mangaKey }.toSet()
        val fallback = eligible.filterNot { it in excluded }.sortedBy { key ->
            MessageDigest.getInstance("SHA-256").digest("$week:$key".toByteArray()).joinToString("") { "%02x".format(it) }
        }.take(5 - community.size)
        return community.map { WeeklyPick(it.mangaKey,it) } + fallback.map { WeeklyPick(it) }
    }
}
