# Account isolation — private code 27

The original Mihon SQLite personal fields were shared across accounts. Schema 17 adds account snapshots/context/explicit guest claims in the same database. Switching archives and restores the active personal projection in one transaction; global catalog/source/chapter IDs remain unchanged. Repository writes and reactive consumers are fenced by authenticated owner and generation. Preferences, cache handles, cover overrides, download journals, pending deletion and file visibility use the same ownership boundary. Durable downloads retain their original owner through account switches.

Legacy state remains Guest until explicit import. Before switching it is backed up consistently in no_backup/account-upgrade/legacy-v17.sqlite. Import retains the original archive, preserves existing account positions, remaps category relationships and preference references, and grants existing files logically without moving or deleting them. Keep that backup private; schema downgrade is not an automatic rollback strategy.

Executed Debug emulator acceptance: 104 assertions passed (44 online A/B/A, 18 legacy-copy upgrade/import, 22 offline restart, 20 cancellation/restart). Same persistent installation and main tachiyomi.db, genuine designated Auth users A/B, recorded synthetic-source image fixtures. No physical phone or Google-login UI test. Legacy migration retained all original 100 manga, 21 chapters, 5 history, 1 category and 8 source records. Killed uncommitted WAL transaction rolled back; integrity/foreign keys passed.

Confirmed runtime defects repaired: preference reads during a transition could crash ReaderActivity.onResume; late reader history callbacks lacked original ownership; durable downloads could lose their task identity after switching; cancelled queue entries could be restored after process death. Regression tests exercise each path.

No source implementation, signed rule, workflow, production schema or historical community record was changed. Read-only checks retained all original 24 comments and 30 ratings unchanged. Real Google OAuth and two physical devices remain manual acceptance requirements. Final optimized results and APK metadata are recorded separately in the private evidence directory.

## Changed files

- `app/build.gradle.kts`
- `app/src/main/java/eu/kanade/domain/manga/interactor/SetExcludedScanlators.kt`
- `app/src/main/java/eu/kanade/domain/track/service/DelayedTrackingUpdateJob.kt`
- `app/src/main/java/eu/kanade/domain/track/store/DelayedTrackingStore.kt`
- `app/src/main/java/eu/kanade/presentation/account/CloudSyncControls.kt`
- `app/src/main/java/eu/kanade/presentation/novels/NovelReaderScreen.kt`
- `app/src/main/java/eu/kanade/tachiyomi/App.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/account/LocalAccountActivities.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/account/SupabaseAccountAuth.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/account/sync/CloudLocalGateway.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/account/sync/CloudSyncStore.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/account/sync/SupabaseCloudSync.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/create/BackupCreateJob.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/create/creators/PreferenceBackupCreator.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/restore/BackupRestoreJob.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/restore/restorers/MangaRestorer.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/restore/restorers/PreferenceRestorer.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/cache/ChapterCache.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/cache/CoverCache.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/coil/MangaCoverFetcher.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/coil/MangaCoverKeyer.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadCache.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadManager.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadNotifier.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadPendingDeleter.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadProvider.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadStore.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/download/Downloader.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/download/model/Download.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/library/LibraryUpdateJob.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/library/LibraryUpdateNotifier.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/library/MetadataUpdateJob.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/sigils/SigilRepository.kt`
- `app/src/main/java/eu/kanade/tachiyomi/di/AppModule.kt`
- `app/src/main/java/eu/kanade/tachiyomi/di/PreferenceModule.kt`
- `app/src/main/java/eu/kanade/tachiyomi/novels/NovelRepository.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/category/CategoryViewModel.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/download/DownloadQueueViewModel.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/download/MangaroDownloadsViewModel.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/history/HistoryViewModel.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeViewModel.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/library/LibrarySettingsViewModel.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryViewModel.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/library/MangaroLibraryShelves.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/main/MainActivity.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaCoverViewModel.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaViewModel.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/HttpPageLoader.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/stats/StatsViewModel.kt`
- `app/src/sunovelsAcceptance/AndroidManifest.xml`
- `app/src/sunovelsAcceptance/java/eu/kanade/tachiyomi/novels/AccountIsolationInstrumentation.kt`
- `app/src/sunovelsAcceptance/java/eu/kanade/tachiyomi/novels/CloudSyncAcceptanceInstrumentation.kt`
- `app/src/test/java/eu/kanade/presentation/browse/MangaroCatalogComponentTest.kt`
- `app/src/test/java/eu/kanade/tachiyomi/data/download/AccountDownloadOwnershipTest.kt`
- `app/src/test/java/eu/kanade/tachiyomi/data/download/DownloadStoreDurabilityTest.kt`
- `app/src/test/java/eu/kanade/tachiyomi/data/integration/ProductionHardeningTest.kt`
- `app/src/test/java/mihon/domain/source/interactor/SourceRefreshConcurrencyTest.kt`
- `core/common/src/main/kotlin/tachiyomi/core/common/preference/AccountPreferenceStore.kt`
- `core/common/src/main/kotlin/tachiyomi/core/common/preference/LocalPersonalPreferenceKeys.kt`
- `core/common/src/main/kotlin/tachiyomi/core/common/util/lang/CoroutinesExtensions.kt`
- `core/common/src/main/kotlin/tachiyomi/core/common/util/lang/LocalAccountScope.kt`
- `core/common/src/test/java/tachiyomi/core/common/preference/LocalAccountIsolationTest.kt`
- `data/src/main/java/tachiyomi/data/LocalAccountWorkspace.kt`
- `data/src/main/java/tachiyomi/data/QueryExtension.kt`
- `data/src/main/java/tachiyomi/data/category/CategoryRepositoryImpl.kt`
- `data/src/main/java/tachiyomi/data/chapter/ChapterRepositoryImpl.kt`
- `data/src/main/java/tachiyomi/data/history/HistoryRepositoryImpl.kt`
- `data/src/main/java/tachiyomi/data/manga/MangaRepositoryImpl.kt`
- `data/src/main/java/tachiyomi/data/track/TrackRepositoryImpl.kt`
- `data/src/main/sqldelight/tachiyomi/data/chapters.sq`
- `data/src/main/sqldelight/tachiyomi/data/local_account.sq`
- `data/src/main/sqldelight/tachiyomi/data/mangas.sq`
- `data/src/main/sqldelight/tachiyomi/data/mangas_categories.sq`
- `data/src/main/sqldelight/tachiyomi/migrations/16.sqm`
- `domain/src/main/java/mihon/domain/account/AccountFoundation.kt`
- `domain/src/main/java/mihon/domain/account/CloudSyncFoundation.kt`
- `domain/src/main/java/tachiyomi/domain/chapter/model/Chapter.kt`
- `domain/src/main/java/tachiyomi/domain/chapter/model/ChapterUpdate.kt`
- `domain/src/main/java/tachiyomi/domain/manga/model/Manga.kt`
- `domain/src/main/java/tachiyomi/domain/manga/model/MangaCover.kt`
- `domain/src/main/java/tachiyomi/domain/manga/model/MangaUpdate.kt`
- `domain/src/main/java/tachiyomi/domain/storage/service/StorageManager.kt`
- `presentation-widget/src/main/java/tachiyomi/presentation/widget/WidgetManager.kt`
- `tools/account-isolation/verify-legacy-upgrade.py`

## Delivery

User explicitly stopped remaining runtime tests to perform manual phone acceptance. Full host: 722 total, 716 passed, 6 skipped, zero failures. Debug emulator: 104 passed. New optimized Release runtime acceptance remains PENDING; do not claim it passed. APK: /home/jalem/Downloads/Mangaro-Releases/Mangaro-R8-Account-Isolation-Test.apk; code27; SHA-256 dedee9254d7405847a97dc2a17891e27ae1940c0b63ac76dd37c8c51395fb2c9.
