package eu.kanade.tachiyomi.source

/** Explicit source entitlement denial, never a retryable transport failure. */
class ChapterAccessRestrictedException : IllegalStateException("هذا الفصل مقفل لدى المصدر.")
