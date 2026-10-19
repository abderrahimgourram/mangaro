package eu.kanade.tachiyomi.data.account

import eu.kanade.presentation.account.RankArtAssets
import eu.kanade.presentation.account.formatProfileCount
import io.kotest.matchers.shouldBe
import mihon.domain.account.*
import mihon.domain.sigils.*
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale

class ProfileRefinementPreservationTest {
    private fun root(): File = listOf(File("."), File("..")).first { File(it,"app/src/main").isDirectory }
    private fun source(path: String) = File(root(),"app/src/main/java/$path").readText()

    @Test fun `all original seven illustrated ranks remain local and distinct`() {
        RankArtAssets.all.size shouldBe 7
        RankArtAssets.all.map {it.drawable}.distinct().size shouldBe 7
        listOf("gem_pendant","compass","crystal_ball","broadsword","dragon_head","winged_sword","crown").forEach {
            File(root(),"app/src/main/res/drawable-nodpi/rank_$it.webp").isFile shouldBe true
        }
    }
    @Test fun `original thirty seals and exactly three selected slots remain valid`() {
        RealmSigils.all.size shouldBe 30
        val selected = listOf("gates_awakened",null,"archive_gem")
        val unlocks = listOf(SigilUnlock("gates_awakened"), SigilUnlock("archive_gem"))
        SigilProgress.slotsValid(selected,unlocks) shouldBe true
        selected shouldBe listOf("gates_awakened",null,"archive_gem")
        SigilProgress.slotsValid(listOf("gates_guardian",null,null),unlocks) shouldBe false
        SigilProgress.slotsValid(selected,listOf(SigilUnlock("gates_awakened",revoked=true),SigilUnlock("archive_gem"))) shouldBe false
    }
    @Test fun `progress uses current-level earned XP and developer override never changes XP`() {
        val reader = MangaroProfile("fixture",null,"اسم طويل","mixed_name",null,110,3)
        MangaroLevelProgress.threshold(3) shouldBe 88
        MangaroLevelProgress.earned(reader) shouldBe 22
        MangaroLevelProgress.required(3) shouldBe 56
        ProfileIdentity.displayedLevel(reader.level,AccountRole.DEVELOPER) shouldBe 30
        reader.xp shouldBe 110
        MangaroLevelProgress.required(30) shouldBe 0
    }
    @Test fun `locale-aware large numbers and unknown counts are not fabricated`() {
        formatProfileCount(9232,Locale.US) shouldBe "9,232"
        formatProfileCount(0,Locale.US) shouldBe "0"
        formatProfileCount(null,Locale.US) shouldBe "—"
        val arabic = formatProfileCount(9232,Locale.forLanguageTag("ar"))
        arabic.filter(Char::isDigit).length shouldBe 4
        formatProfileCount(Long.MAX_VALUE,Locale.US) shouldBe "9,223,372,036,854,775,807"
    }
    @Test fun `production owner screen consumes scoped projection and keeps existing editing and cloud controls`() {
        // Static integration guard, not a claim of Compose/device acceptance.
        val screen = source("eu/kanade/presentation/account/AccountScreen.kt")
        listOf("rememberProfileReadingStatistics(profile.userId)","ProfileReadingStatistics(reading)",
            "ProfileProgressCard(profile)","OwnProfileSigils()","ProfileShowcaseControls(profile, account)",
            "account.auth.updateProfile(update)","CloudSyncControls(profile.userId, account.cloudSync)",
            "key(current.profile.userId)").forEach { screen.contains(it) shouldBe true }
    }
    @Test fun `public profile never uses private reading projection or local history`() {
        val public = source("eu/kanade/presentation/community/PublicCommunityProfile.kt")
        public.contains("ProfileReadingStatistics") shouldBe false
        public.contains("GetHistory") shouldBe false
        public.contains("profile.chaptersRead?.let") shouldBe true // Existing authorized optional aggregate only.
        val view = source("eu/kanade/presentation/account/ProfileReadingStatistics.kt")
        view.contains("snapshot.visibleFor(owner, workspace)") shouldBe true
        view.contains("collectAsStateWithLifecycle") shouldBe true
    }
}
