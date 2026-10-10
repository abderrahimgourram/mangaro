package eu.kanade.presentation.novels

import eu.kanade.presentation.community.CommunityContext
import eu.kanade.tachiyomi.novels.Novel
import eu.kanade.tachiyomi.novels.NovelChapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mihon.domain.account.AccountAccess
import mihon.domain.account.AccountFeature
import mihon.domain.account.AccountFoundation
import mihon.domain.account.AccountOperation
import mihon.domain.community.CommunityChapterKey
import mihon.domain.community.CommunityMangaKey
import mihon.domain.community.CommunityTarget
import mihon.domain.community.CommunityTargetType
import mihon.domain.sigils.SigilEvents
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap

private val novelProgressionClaims = ConcurrentHashMap.newKeySet<String>()

/** Submits novel chapter completion to the unified server-authoritative XP and progression system. */
internal fun submitCompletedNovelChapter(novel: Novel, chapter: NovelChapter) {
    val account = Injekt.get<AccountFoundation>()
    val access = account.featureGate.access(AccountFeature.XP) as? AccountAccess.Allowed ?: return
    val work = CommunityMangaKey.fromNovelEdition(novel.sourceId, novel.id)
    val chapterKey = CommunityChapterKey.fromSource(work, chapter.url)
    val key = chapterKey.value
    if (account.cloudSync.restoredCompletion(access.userId, key)) return
    val attempt = "${access.userId}:$key"
    if (!novelProgressionClaims.add(attempt)) return
    Injekt.get<CoroutineScope>().launch(Dispatchers.IO) {
        try {
            if (account.auth.claimChapterCompletion(work.value, key) == AccountOperation.Completed) {
                SigilEvents.emit(SigilEvents.Kind.READ, id = 0, owner = access.userId)
            } else {
                novelProgressionClaims.remove(attempt)
            }
        } catch (cancelled: CancellationException) {
            novelProgressionClaims.remove(attempt)
            throw cancelled
        } catch (_: Exception) {
            novelProgressionClaims.remove(attempt)
        }
    }
}

/** Existing comments/replies/permissions, scoped to the actual edition and chapter URL. */
internal fun novelCommunityContext(novel: Novel, chapter: NovelChapter): CommunityContext {
    val work = CommunityMangaKey.fromNovelEdition(novel.sourceId, novel.id)
    return CommunityContext(CommunityTarget(
        CommunityTargetType.CHAPTER,
        work, CommunityChapterKey.fromSource(work, chapter.url),
    ), chapter.title)
}

/** Edition ratings/comments use the existing opaque work contract, distinct from chapter threads. */
internal fun novelWorkCommunityContext(novel: Novel): CommunityContext = CommunityContext(
    CommunityTarget(CommunityTargetType.MANGA, CommunityMangaKey.fromNovelEdition(novel.sourceId, novel.id)),
    novel.title, commentsTitle = "تعليقات الرواية", ratingsEnabled = true,
)
