package mihon.domain.account

/** Pure presentation tokens. Role, XP rewards and canonical rank names remain independent. */
data class RankVisualStyle(val tier: Int, val primary: Long, val secondary: Long, val surface: Long, val soft: Long) {
    val isMax: Boolean get() = tier == 6
}
object RankVisuals {
    private val styles = listOf(
        RankVisualStyle(0,0xFFA7A0B8,0xFFA7A0B8,0xFF24212B,0xFF6D667A),
        RankVisualStyle(1,0xFFC38A62,0xFFC38A62,0xFF2B211D,0xFF7D5540),
        RankVisualStyle(2,0xFF63B89C,0xFF63B89C,0xFF182824,0xFF376E60),
        RankVisualStyle(3,0xFF6F9FE8,0xFF6F9FE8,0xFF182333,0xFF405F91),
        RankVisualStyle(4,0xFFA67BE8,0xFFA67BE8,0xFF241C34,0xFF654B90),
        RankVisualStyle(5,0xFFD6B56D,0xFFD6B56D,0xFF2B2518,0xFF8B743E),
        RankVisualStyle(6,0xFFE4C77A,0xFFAE86F3,0xFF2B2419,0xFF8B743E),
    )
    fun resolve(level: Int) = styles[ProfileIdentity.tier(level)]
}
