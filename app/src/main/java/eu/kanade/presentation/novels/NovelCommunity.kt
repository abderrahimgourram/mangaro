package eu.kanade.presentation.novels

import eu.kanade.presentation.community.CommunityContext
import eu.kanade.tachiyomi.novels.Novel
import eu.kanade.tachiyomi.novels.NovelChapter
import mihon.domain.community.CommunityChapterKey
import mihon.domain.community.CommunityMangaKey
import mihon.domain.community.CommunityTarget
import mihon.domain.community.CommunityTargetType

/** Existing comments/replies/permissions, scoped to the actual edition and chapter URL. */
internal fun novelCommunityContext(novel: Novel, chapter: NovelChapter): CommunityContext {
    val work = CommunityMangaKey.fromNovelEdition(novel.sourceId, novel.id)
    return CommunityContext(CommunityTarget(
        CommunityTargetType.CHAPTER,
        work, CommunityChapterKey.fromSource(work, chapter.url),
    ), chapter.title)
}

/** General edition comments are distinct from chapter threads. No manga discovery ratings. */
internal fun novelWorkCommunityContext(novel: Novel): CommunityContext = CommunityContext(
    CommunityTarget(CommunityTargetType.MANGA, CommunityMangaKey.fromNovelEdition(novel.sourceId, novel.id)),
    novel.title, commentsTitle = "تعليقات الرواية", ratingsEnabled = false,
)
