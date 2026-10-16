package eu.kanade.tachiyomi.novels

/** Discovery policy only. Signed allowlists, stored edition IDs and legacy implementations remain intact. */
internal object NovelSourceAvailability {
    fun active(sourceId: String) = sourceId in NovelRules.domains &&
        (sourceId != SunovelsSource.SOURCE_ID || (SunovelsSource.directEnabled || SunovelsGatewayConfig.enabled))

    fun discoverable(work: UnifiedNovelWork): UnifiedNovelWork? {
        val editions = work.editions.filter { active(it.sourceId) }
        if (editions.isEmpty()) return null
        return work.copy(editions = editions, primaryEditionId =
            work.primaryEditionId.takeIf { id -> editions.any { it.id == id } } ?: editions.first().id)
    }
}
