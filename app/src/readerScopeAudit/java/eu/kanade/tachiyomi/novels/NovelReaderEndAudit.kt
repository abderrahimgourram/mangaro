package eu.kanade.tachiyomi.novels

import android.app.Instrumentation
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.platform.ComposeView
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import coil3.asImage
import eu.kanade.presentation.novels.NovelReaderScreen
import eu.kanade.presentation.theme.TachiyomiTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.domain.account.AccountAuth
import mihon.domain.account.AccountFoundation
import mihon.domain.account.AccountRole
import mihon.domain.account.AccountSession
import mihon.domain.account.DisabledAccountCloudSync
import mihon.domain.account.GuestAccountAuth
import mihon.domain.account.MangaroProfile
import mihon.domain.account.MangaroRanks
import mihon.domain.account.ProfileStatistics
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addFactory
import uy.kohesive.injekt.api.get

/** Opt-in instrumentation only. Authored offline prose on a disposable emulator, no account or ad requests. */
internal fun Instrumentation.runNovelReaderEndAudit(
    connectedOnly: Boolean = false,
    profileOnly: Boolean = false,
): Bundle = runBlocking {
    val activity =
        startActivitySync(
            android.content.Intent(targetContext, eu.kanade.tachiyomi.ui.main.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    val account = Injekt.get<AccountFoundation>()
    withTimeout(30_000) { account.auth.observeSession().first { it !is AccountSession.Loading } }
    withTimeout(10_000) { tachiyomi.core.common.util.lang.LocalAccountScope.changes.first { it.ready } }
    Injekt.addFactory<AccountFoundation> { AccountFoundation(GuestAccountAuth(), DisabledAccountCloudSync()) }
    val repository = NovelRepository.get(targetContext)
    repository.awaitLocal()
    val decor = activity.window.decorView as android.view.ViewGroup
    fun nodes(root: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): Boolean {
        if (root == null) return false
        if (predicate(root)) return true
        return (0 until root.childCount).any { nodes(root.getChild(it), predicate) }
    }
    fun contains(text: String): Boolean {
        // An occasional cold-boot system ANR is an emulator issue; never click app/ad actions.
        nodes(uiAutomation.rootInActiveWindow) { node ->
            node.packageName?.toString() == "android" && node.text?.toString() == "Wait" &&
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        return nodes(uiAutomation.rootInActiveWindow) { it.text?.toString()?.contains(text) == true }
    }
    contains("MANGARO_AUDIT_START") // Dismiss only a cold-boot Android system Wait dialog, if present.
    var view: ComposeView? = null
    val navigator = java.util.concurrent.atomic.AtomicReference<cafe.adriel.voyager.navigator.Navigator>()
    fun showScreen(screen: eu.kanade.presentation.util.Screen) {
        runOnMainSync {
            view?.let {
                it.disposeComposition()
                decor.removeView(it)
            }
            view = ComposeView(activity).apply {
                setContent {
                    TachiyomiTheme {
                        Navigator(screen) { nav ->
                            androidx.compose.runtime.SideEffect { navigator.set(nav) }
                            CurrentScreen()
                        }
                    }
                }
            }
            decor.addView(view, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
    }
    fun show(novel: Novel, chapter: NovelChapter) {
        runOnMainSync {
            view?.let {
                it.disposeComposition()
                decor.removeView(it)
            }
            view =
                ComposeView(activity).apply {
                    setContent { TachiyomiTheme { Navigator(NovelReaderScreen(novel, chapter)) { CurrentScreen() } } }
                }
            decor.addView(view, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
    }
    val results = mutableListOf<String>()
    var phase = "start"
    fun record(text: String) {
        results += text
        println(text)
    }
    try {
        for ((count, font) in if (connectedOnly ||
            profileOnly
        ) {
            emptyList()
        } else {
            listOf(62 to 21f, 100 to 32f, 240 to 16f)
        }) {
            val novel =
                Novel(
                    "novel.seanovel",
                    "https://seanovel.org/novels/reader-end-audit-$count",
                    "اختبار نهاية الفصل $count",
                )
            val chapter = NovelChapter(novel.url + "/chapters/1", "اختبار $count", 1)
            val paragraphs = (1..count).map { i ->
                "فقرة $i. سار المسافر إلى المدينة وتأمل في الطريق ثم كتب رسالة إلى صديقه وتابع الرحلة بهدوء. ".repeat(
                    3,
                ) +
                    if (i == count) " MANGARO_FINAL_$count" else ""
            }
            repository.disk.write(novel.id, chapter.id, NovelText(paragraphs))
            repository.saveSettings(NovelReaderSettings(fontSize = font))
            repository.settings.first { it.fontSize == font }
            phase = "initial-content-$count"
            show(novel, chapter)
            withTimeout(30_000) { while (!contains("فقرة 1."))delay(100) }
            phase = "scroll-to-end-$count"
            var scrolls = 0
            withTimeout(60_000) {
                while (!contains("100%")) {
                    check(scrolls++ < 300) { "Bounded scroll budget exhausted for $count" }
                    val moved = nodes(uiAutomation.rootInActiveWindow) { node ->
                        node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    }
                    delay(if (moved) 100 else 300)
                }
            }
            check(contains("MANGARO_FINAL_$count")) { "Final text not accessible at 100% for $count" }
            check(!contains(" / $count")) { "Internal page fraction leaked for $count" }
            withTimeout(5_000) { while (repository.readingPosition(novel, chapter)?.completed != true)delay(50) }
            val saved = repository.readingPosition(novel, chapter)!!
            check(saved.paragraph in 1..count)
            record(
                "PASS rendered=$count font=$font finalText=true progress=100 savedCompleted=true scrollActions=$scrolls",
            )
            if (count == 62) {
                phase = "reopen-62"
                runOnMainSync {
                    view?.let {
                        it.disposeComposition()
                        decor.removeView(it)
                    }
                    view = null
                }
                show(novel, chapter)
                withTimeout(15_000) { while (!contains("MANGARO_FINAL_62") || !contains("100%"))delay(100) }
                val restored = repository.readingPosition(novel, chapter)!!
                check(restored.chapter.id == saved.chapter.id && restored.completed == true)
                record("PASS reopen=62 finalText=true progress=100 originalChapterIdentity=true")
            }
            val screenshot = uiAutomation.takeScreenshot()
            screenshot?.let { bitmap ->
                java.io.File(targetContext.cacheDir, "reader-end-$count.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
        if (!connectedOnly && !profileOnly) {
            phase = "local-statistics"
            val stats = repository.observeReadStatistics(
                tachiyomi.core.common.util.lang.LocalAccountScope.GUEST,
            ).first {
                it?.completed ==
                    3L
            }
            check(stats?.count == 3L) // Three chapters, NOT 62+100+240 internal segments.
            record("PASS actualRepositoryReadStatistics=3 chapters offline=true")
        }

        // Optional real cover bytes are pushed to this disposable app cache by the operator.
        // Cache the exact source URL; the production cover components and destination remain unchanged.
        fun cacheCover(name: String, url: String, novel: Boolean) {
            val file = java.io.File(targetContext.cacheDir, "polish-covers/$name.image")
            if (!file.exists()) return
            val bitmap = android.graphics.BitmapFactory.decodeFile(file.path) ?: error("Invalid recorded cover")
            val image = bitmap.asImage()
            if (novel) {
                repository.covers.memoryCache?.set(
                    coil3.memory.MemoryCache.Key("$url|motion=false"),
                    coil3.memory.MemoryCache.Value(image),
                )
            } else {
                coil3.SingletonImageLoader.get(targetContext)
                    .memoryCache
                    ?.set(
                        coil3.memory.MemoryCache.Key("$url;0"),
                        coil3.memory.MemoryCache.Value(image),
                    )
            }
        }
        if (!profileOnly) {
            cacheCover("solo-novel", "https://seanovel.org/api/novel/solo-leveling/cover?type=webp", true)
            cacheCover("solo-comic", "https://olympustaff.com/images/manga/b05147568d52f22c663c084172fc0dac.png", false)
            // Real public metadata captured 2026-10-20. Offline UI test, not a live reading claim.
            val relatedNovel = Novel(
                "novel.seanovel",
                "https://seanovel.org/novels/solo-leveling",
                "رفع المستوى منفرداً",
                "https://seanovel.org/api/novel/solo-leveling/cover?type=webp",
            )
            val mangaRepository = Injekt.get<tachiyomi.domain.manga.repository.MangaRepository>()
            val comic = mangaRepository.insertNetworkManga(
                listOf(
                    tachiyomi.domain.manga.model.Manga.create().copy(
                        source = 4110737012647435874L,
                        url = "/series/SL",
                        title = "Solo Leveling",
                        initialized = true,
                        thumbnailUrl = "https://olympustaff.com/images/manga/b05147568d52f22c663c084172fc0dac.png",
                    ),
                ),
            ).single()
            check(!comic.favorite)
            repository.ingest(listOf(relatedNovel))
            val libraryBefore = repository.library.value
            val original = eu.kanade.tachiyomi.ui.manga.MangaScreen(comic.id)
            phase = "connected-comic-details"
            showScreen(original)
            suspend fun awaitHeading(text: String) {
                withTimeout(30_000) {
                    while (!contains(text)) {
                        nodes(uiAutomation.rootInActiveWindow) {
                            it.isScrollable &&
                                it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        }
                        delay(200)
                    }
                }
            }
            fun tapTitle(text: String): Boolean = nodes(uiAutomation.rootInActiveWindow) { node ->
                if (node.text?.toString()?.trim() != text.trim()) {
                    false
                } else {
                    var clickable: AccessibilityNodeInfo? = node
                    while (clickable != null && !clickable.isClickable)clickable = clickable.parent
                    clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                }
            }
            awaitHeading("إصدار رواية")
            uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(targetContext.cacheDir, "connected-novel-card.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            check(tapTitle(relatedNovel.title))
            withTimeout(10_000) {
                while (navigator.get()?.lastItem !is eu.kanade.presentation.novels.NovelDetailsScreen)delay(100)
            }
            record("PASS comicDetailsToNovelDetails actualUI=true relatedHeading=true")
            phase = "connected-novel-details"
            awaitHeading("إصدار مانهوا")
            uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(targetContext.cacheDir, "connected-manhwa-card.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            check(tapTitle(comic.title))
            withTimeout(10_000) {
                while (navigator.get()?.lastItem !is eu.kanade.tachiyomi.ui.manga.MangaScreen)delay(100)
            }
            runOnMainSync {
                check(navigator.get().pop())
                check(navigator.get().pop())
                check(navigator.get().lastItem.key == original.key)
            }
            check(repository.library.value == libraryBefore)
            check(!mangaRepository.getMangaById(comic.id).favorite)
            record("PASS novelDetailsToComicDetails actualUI=true backStack=true noLibraryOrProgressMutation=true")
            // A new publisher-reviewed pair traverses the same production detail screens.
            val extraPairs = listOf(
                Triple(
                    "orv",
                    Novel(
                        "novel.seanovel",
                        "https://seanovel.org/novels/the-all-encompassing-reader",
                        "القارئ المحيط بكل شيء ",
                        "https://seanovel.org/api/novel/the-all-encompassing-reader/cover?type=webp",
                        author = "سينغ شونغ",
                    ),
                    tachiyomi.domain.manga.model.Manga.create().copy(
                        source = 215553151312092548L,
                        url = "/manhwa/omniscient-readers-viewpoint#6a8fb9a47d3207cb2faf20a0",
                        title = "وجهة نظر القارئ كلّي المعرفة",
                        initialized = true,
                        author = "sing N song (싱숑)",
                        artist = "Sleepy-C (슬리피-C)",
                        thumbnailUrl =
                        "https://mangatime.org/uploads/series/6a8fb9a47d3207cb2faf20a0-1ce9cd693210fcac.webp",
                    ),
                ),
                Triple(
                    "overgeared",
                    Novel(
                        "novel.seanovel",
                        "https://seanovel.org/novels/overgeared",
                        "مدجج بالعتاد",
                        "https://seanovel.org/api/novel/overgeared/cover?type=webp",
                        author = "Park Saenal",
                    ),
                    tachiyomi.domain.manga.model.Manga.create().copy(
                        source = 215553151312092548L,
                        url = "/manhwa/overgeared#6a8f5babf1a5e87af68c564b",
                        title = "أوفرغيرد",
                        initialized = true,
                        thumbnailUrl =
                        "https://mangatime.org/uploads/series/6a8f5babf1a5e87af68c564b-3a5102281fc30d20.webp",
                    ),
                ),
                Triple(
                    "mount-hua",
                    Novel(
                        "novel.seanovel",
                        "https://seanovel.org/novels/return-of-the-blossoming-blade",
                        "عودة طائفة جبل هوا",
                        "https://seanovel.org/api/novel/return-of-the-blossoming-blade/cover?type=webp",
                        author = "بيجا",
                    ),
                    tachiyomi.domain.manga.model.Manga.create().copy(
                        source = 215553151312092548L,
                        url = "/manhwa/return-of-the-mount-hua-sect#6a49e00234c268b77054833e",
                        title = "عودة طائفة جبل هوا",
                        initialized = true,
                        thumbnailUrl =
                        "https://mangatime.org/uploads/series/6a49e00234c268b77054833e-6907fadc0a427774.webp",
                    ),
                ),
            )
            for ((storyId, extraNovel, comicMetadata) in extraPairs) {
                val extraComic = mangaRepository.insertNetworkManga(listOf(comicMetadata)).single()
                repository.ingest(listOf(extraNovel))
                cacheCover("$storyId-novel", extraNovel.cover!!, true)
                cacheCover("$storyId-comic", extraComic.thumbnailUrl!!, false)
                if (storyId == "mount-hua") {
                    // Seed the known bad old key as well as the reviewed publisher key: stale cache must not win.
                    val corrected = tachiyomi.domain.manga.model.ReviewedCoverArtwork.MOUNT_HUA_OFFICIAL
                    cacheCover("mount-hua-official", corrected, false)
                    val imageLoader = coil3.SingletonImageLoader.get(targetContext)
                    val result = imageLoader.execute(
                        coil3.request.ImageRequest.Builder(targetContext)
                            .data(extraComic).networkCachePolicy(coil3.request.CachePolicy.DISABLED).build(),
                    )
                    check(result is coil3.request.SuccessResult && result.memoryCacheKey?.key == "$corrected;0")
                    record(
                        "PASS MountHua publisherArtwork=true staleWrongCacheBypassed=true storedSourceUrlUnchanged=true",
                    )
                }
                phase = "$storyId-comic-details"
                showScreen(eu.kanade.tachiyomi.ui.manga.MangaScreen(extraComic.id))
                awaitHeading("إصدار رواية")
                delay(400)
                uiAutomation.takeScreenshot()?.let { bitmap ->
                    java.io.File(targetContext.cacheDir, "$storyId-novel-card.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
                withTimeout(10_000) { while (!tapTitle(extraNovel.title))delay(150) }
                withTimeout(10_000) {
                    while (navigator.get()?.lastItem !is eu.kanade.presentation.novels.NovelDetailsScreen)delay(100)
                }
                awaitHeading("إصدار مانهوا")
                delay(400)
                uiAutomation.takeScreenshot()?.let { bitmap ->
                    java.io.File(targetContext.cacheDir, "$storyId-manhwa-card.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
                withTimeout(10_000) { while (!tapTitle(extraComic.title))delay(150) }
                withTimeout(10_000) {
                    while (navigator.get()?.lastItem !is eu.kanade.tachiyomi.ui.manga.MangaScreen)delay(100)
                }
                check(!mangaRepository.getMangaById(extraComic.id).favorite)
                record("PASS connectedStory=$storyId bothDirections=true actualDetails=true separateSourceIds=true")
            }
        }
        if (!connectedOnly) {
            phase = "profile"
            val profile =
                MangaroProfile(
                    "guest", null, "قارئ مانجارو · Mangaro", "mangaro_reader_1234567890", null, 110, 3,
                    bio = "مساحة للقراءة والحكايات", role = AccountRole.DEVELOPER,
                )
            val session = kotlinx.coroutines.flow.MutableStateFlow<AccountSession>(
                AccountSession.Authenticated(profile),
            )
            val fake = object : AccountAuth by GuestAccountAuth() {
                override fun observeSession(): kotlinx.coroutines.flow.StateFlow<AccountSession> = session
                override suspend fun getCurrentProfile(): MangaroProfile = profile
                override suspend fun profileStatistics() = ProfileStatistics(3, 2)
            }
            Injekt.addFactory<AccountFoundation> { AccountFoundation(fake, DisabledAccountCloudSync()) }
            showScreen(eu.kanade.presentation.account.AccountScreen())
            val rank = MangaroRanks.titleFor(30)
            withTimeout(15_000) { while (!contains(rank))delay(100) }
            var ranks = 0
            nodes(uiAutomation.rootInActiveWindow) { node ->
                if (node.text?.toString() == rank) ranks++
                false
            }
            check(ranks == 1) { "Duplicate rank: $ranks" }
            check(profile.xp == 110L && profile.level == 3)
            uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(targetContext.cacheDir, "profile-header.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            // Scroll the actual owner profile to its existing statistics and featured seals.
            withTimeout(10_000) {
                while (!contains("أختامك المميزة")) {
                    nodes(uiAutomation.rootInActiveWindow) {
                        it.isScrollable &&
                            it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    }
                    delay(200)
                }
            }
            check(contains("أختامك المميزة"))
            uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(targetContext.cacheDir, "profile-reading-seals.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            record(
                "PASS actualAccountScreen oneRank=true developerLevel30=true actualXp=110 originalLevel=3 featuredSealsPresent=true",
            )
        }
        Bundle().apply {
            putString("stream", results.joinToString("\n") + "\n")
            putInt("passed", results.size)
        }
    } catch (failure: Throwable) {
        uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(targetContext.cacheDir, "reader-audit-failure.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        val labels = mutableListOf<String>()
        nodes(uiAutomation.rootInActiveWindow) { node ->
            node.text?.toString()?.let {
                if (labels.size <
                    30
                ) {
                    labels += it.take(120)
                }
            }
            false
        }
        throw IllegalStateException("phase=$phase results=$results visible=$labels", failure)
    } finally {
        runOnMainSync {
            view?.let {
                it.disposeComposition()
                decor.removeView(it)
            }
        }
        Injekt.addFactory<AccountFoundation> { account }
    }
}
