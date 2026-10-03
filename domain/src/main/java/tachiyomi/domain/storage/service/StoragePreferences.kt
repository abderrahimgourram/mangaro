package tachiyomi.domain.storage.service

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.storage.FolderProvider

class StoragePreferences(
    folderProvider: FolderProvider,
    preferenceStore: PreferenceStore,
) {

    // Retain the pre-app-scoped location for read/delete compatibility, not new writes.
    val legacyDownloadsBaseDirectory: Preference<String> = preferenceStore.getString(
        Preference.appStateKey("legacy_downloads_base_dir"),
        "",
    )

    val baseStorageDirectory: Preference<String> = preferenceStore.getString(
        Preference.appStateKey("storage_dir"),
        folderProvider.path(),
    )
}
