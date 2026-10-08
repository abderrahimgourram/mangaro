package eu.kanade.presentation.sigils

import androidx.annotation.DrawableRes
import eu.kanade.tachiyomi.R

/** Local licensed illustration metadata, indexed by the unchanged achievement ID. */
internal data class SigilArtAsset(@DrawableRes val drawable: Int, val author: String, val sourceUrl: String)

internal object SigilArtAssets {
    private val illustrations = mapOf(
        "gates_awakened" to SigilArtAsset(R.drawable.sigil_gates_awakened, "Lorc", "https://game-icons.net/1x1/lorc/crystal-shine.html"),
        "gates_hunter" to SigilArtAsset(R.drawable.sigil_gates_hunter, "Lorc", "https://game-icons.net/1x1/lorc/crossed-swords.html"),
        "gates_opener" to SigilArtAsset(R.drawable.sigil_gates_opener, "Lorc", "https://game-icons.net/1x1/lorc/magic-portal.html"),
        "gates_leader" to SigilArtAsset(R.drawable.sigil_gates_leader, "Lorc", "https://game-icons.net/1x1/lorc/winged-shield.html"),
        "gates_guardian" to SigilArtAsset(R.drawable.sigil_gates_guardian, "Delapouite", "https://game-icons.net/1x1/delapouite/heaven-gate.html"),
        "tower_visitor" to SigilArtAsset(R.drawable.sigil_tower_visitor, "Delapouite", "https://game-icons.net/1x1/delapouite/star-key.html"),
        "tower_climber" to SigilArtAsset(R.drawable.sigil_tower_climber, "Lorc", "https://game-icons.net/1x1/lorc/guarded-tower.html"),
        "tower_survivor" to SigilArtAsset(R.drawable.sigil_tower_survivor, "Lorc", "https://game-icons.net/1x1/lorc/rune-stone.html"),
        "tower_fates" to SigilArtAsset(R.drawable.sigil_tower_fates, "Lorc", "https://game-icons.net/1x1/lorc/crystal-eye.html"),
        "tower_narrator" to SigilArtAsset(R.drawable.sigil_tower_narrator, "Lorc", "https://game-icons.net/1x1/lorc/star-satellites.html"),
        "murim_scroll" to SigilArtAsset(R.drawable.sigil_murim_scroll, "Lorc", "https://game-icons.net/1x1/lorc/scroll-unfurled.html"),
        "murim_keeper" to SigilArtAsset(R.drawable.sigil_murim_keeper, "Delapouite", "https://game-icons.net/1x1/delapouite/secret-book.html"),
        "murim_student" to SigilArtAsset(R.drawable.sigil_murim_student, "Lorc", "https://game-icons.net/1x1/lorc/triple-yin.html"),
        "murim_heir" to SigilArtAsset(R.drawable.sigil_murim_heir, "Lorc", "https://game-icons.net/1x1/lorc/rune-sword.html"),
        "murim_master" to SigilArtAsset(R.drawable.sigil_murim_master, "Lorc", "https://game-icons.net/1x1/lorc/swords-emblem.html"),
        "court_visitor" to SigilArtAsset(R.drawable.sigil_court_visitor, "Delapouite", "https://game-icons.net/1x1/delapouite/temple-gate.html"),
        "court_rose" to SigilArtAsset(R.drawable.sigil_court_rose, "Lorc", "https://game-icons.net/1x1/lorc/rose.html"),
        "court_regent" to SigilArtAsset(R.drawable.sigil_court_regent, "Delapouite", "https://game-icons.net/1x1/delapouite/imperial-crown.html"),
        "court_returner" to SigilArtAsset(R.drawable.sigil_court_returner, "Lorc", "https://game-icons.net/1x1/lorc/hourglass.html"),
        "court_historian" to SigilArtAsset(R.drawable.sigil_court_historian, "Lorc", "https://game-icons.net/1x1/lorc/wax-seal.html"),
        "archive_gem" to SigilArtAsset(R.drawable.sigil_archive_gem, "Lorc", "https://game-icons.net/1x1/lorc/floating-crystal.html"),
        "archive_relics" to SigilArtAsset(R.drawable.sigil_archive_relics, "Lorc", "https://game-icons.net/1x1/lorc/ankh.html"),
        "archive_keeper" to SigilArtAsset(R.drawable.sigil_archive_keeper, "Delapouite", "https://game-icons.net/1x1/delapouite/book-pile.html"),
        "archive_dimensions" to SigilArtAsset(R.drawable.sigil_archive_dimensions, "Lorc", "https://game-icons.net/1x1/lorc/key.html"),
        "archive_volumes" to SigilArtAsset(R.drawable.sigil_archive_volumes, "Lorc", "https://game-icons.net/1x1/lorc/book-aura.html"),
        "social_voice" to SigilArtAsset(R.drawable.sigil_social_voice, "Lorc", "https://game-icons.net/1x1/lorc/quill.html"),
        "social_pen" to SigilArtAsset(R.drawable.sigil_social_pen, "Lorc", "https://game-icons.net/1x1/lorc/quill-ink.html"),
        "social_council" to SigilArtAsset(R.drawable.sigil_social_council, "Delapouite", "https://game-icons.net/1x1/delapouite/round-table.html"),
        "social_witness" to SigilArtAsset(R.drawable.sigil_social_witness, "Delapouite", "https://game-icons.net/1x1/delapouite/star-formation.html"),
        "social_revered" to SigilArtAsset(R.drawable.sigil_social_revered, "Delapouite", "https://game-icons.net/1x1/delapouite/jewel-crown.html"),
    )

    fun forId(id: String): SigilArtAsset = illustrations.getValue(id)
}
