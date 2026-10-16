package eu.kanade.tachiyomi.novels

/** Explicit guest import is additive. Conflicting account data wins; guest originals stay on disk. */
internal object NovelGuestMerge {
    fun item(existing: NovelLibraryItem?, guest: NovelLibraryItem, baseline: NovelLibraryItem?): NovelLibraryItem {
        if (existing == null) return guest
        require(existing.novel.id == guest.novel.id && (baseline == null || baseline.novel.id == guest.novel.id))
        if (baseline == null) return existing.copy(
            saved = existing.saved || guest.saved, favorite = existing.favorite || guest.favorite,
            position = existing.position ?: guest.position, bookmarks = existing.bookmarks + guest.bookmarks,
        )
        fun <T> field(account: T, device: T, before: T): T = if (account == before) device else account
        return existing.copy(
            saved = field(existing.saved, guest.saved, baseline.saved),
            favorite = field(existing.favorite, guest.favorite, baseline.favorite),
            readingStatus = field(existing.readingStatus, guest.readingStatus, baseline.readingStatus),
            position = field(existing.position, guest.position, baseline.position),
            bookmarks = (existing.bookmarks + (guest.bookmarks - baseline.bookmarks)) - (baseline.bookmarks - guest.bookmarks),
        )
    }

    fun canImportPosition(accountText: String?, observedGuestDigest: String?): Boolean =
        accountText == null || novelDigest(accountText) == observedGuestDigest
}
