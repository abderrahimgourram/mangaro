package eu.kanade.tachiyomi.data.ads

/** Direct SDK identity. APP_KEY identifies the dashboard app, never SDK initialization. */
object InMobiConfiguration {
    const val ACCOUNT_ID = "2437b98e2f6b45789a828a2faad3d0eb"
    const val APP_KEY = "10000240753"
    const val CMP_P_CODE = "UYWLq5tgD_uwk"
    const val CMP_PACKAGE_ID = "app.manhwaar.reader"
    const val DOWNLOAD_VIDEO = 10000837491L
    const val MANGA_BOUNDARY = 10000837492L
    const val NOVEL_START = 10000837493L
}

internal enum class NativePlacement(val id: Long) {
    MANGA_BOUNDARY(InMobiConfiguration.MANGA_BOUNDARY),
    NOVEL_START(InMobiConfiguration.NOVEL_START),
}
