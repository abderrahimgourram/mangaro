# PR #4 full Spotless inventory — 2026-10-20

**Full gate: FAIL. No exclusions, ratchet or suppression added.**

Before: 552 violating files. After reviewed PR #4 cleanup: **538**.
All 14 files fixed in this task pass the original formatter rules; no remaining
listed file differs from PR #4 base `828eacf71248cd2e828a4ea6e34ffc10f46e7be0`.

Method: union of byte-differing canonical output and nonempty deserialized
Spotless Lint collections from the original complete `spotlessCheck --continue`.
Empty serialized lint maps are not errors. Findings can be the first reported
non-autocorrectable rule, not an exhaustive list of every issue within that file.

Recovery comparison: 460 files have identical bytes and formatter settings to
immutable recovery `0c9f95b565b0ae5aca2343430128eaaf6f5eb025`; 40 changed files also
failed the identical Spotless 8.9.0 / ktlint 1.8.0 rules on copies of their recovery
contents; 38 were added after recovery (and before this PR base). The 45-file
initial baseline-copy cohort all failed; five of those current files are now fixed.
The immutable worktree itself was not changed or reformatted.

**13 protected advertising implementations remain violating and byte-identical to
recovery.** They were not formatted. The remaining 525 files are inherited
non-advertising debt; no repository-wide cleanup was attempted beyond the audited
14-file PR scope. Changing protected bytes or adopting a baseline requires the
specific policy decision described in the gate report.

| File | Protected ads | Recovery evidence | Current finding |
| --- | --- | --- | --- |
| `app/src/androidTest/java/eu/kanade/tachiyomi/novels/NovelOfflineRecoveryInstrumentation.kt` | no | identical bytes | standard:no-wildcard-imports L5 |
| `app/src/androidTest/java/eu/kanade/tachiyomi/novels/NovelPartialRuleInstrumentation.kt` | no | identical bytes | standard:no-wildcard-imports L9 |
| `app/src/androidTest/java/eu/kanade/tachiyomi/novels/NovelPerformanceInstrumentation.kt` | no | identical bytes | standard:no-wildcard-imports L12 |
| `app/src/androidTest/java/eu/kanade/tachiyomi/novels/NovelReliabilityInstrumentation.kt` | no | identical bytes | standard:max-line-length L1 |
| `app/src/androidTest/java/eu/kanade/tachiyomi/source/audit/ProductionFeedAudit.kt` | no | identical bytes | standard:no-wildcard-imports L8 |
| `app/src/androidTest/java/eu/kanade/tachiyomi/source/audit/PublisherEngineAudit.kt` | no | identical bytes | standard:no-wildcard-imports L8 |
| `app/src/androidTest/java/eu/kanade/tachiyomi/source/audit/PublisherNativeAudit.kt` | no | identical bytes | standard:max-line-length L1 |
| `app/src/androidTest/java/eu/kanade/tachiyomi/source/audit/RuleAuditFixture.kt` | no | identical bytes | standard:no-wildcard-imports L5 |
| `app/src/androidTest/java/eu/kanade/tachiyomi/source/audit/SeriesRepairAcceptance.kt` | no | identical bytes | standard:max-line-length L1 |
| `app/src/androidTest/java/eu/kanade/tachiyomi/source/audit/SourceAuditInstrumentation.kt` | no | identical bytes | standard:max-line-length L1 |
| `app/src/androidTest/java/eu/kanade/tachiyomi/source/audit/SourceRepairProbe.kt` | no | identical bytes | canonical output differs |
| `app/src/androidTest/java/eu/kanade/tachiyomi/source/internal/teamx/TeamXLiveParityTest.kt` | no | identical bytes | standard:no-empty-file L1 |
| `app/src/androidTest/java/eu/kanade/tachiyomi/ui/browse/source/globalsearch/SearchDedupInstrumentation.kt` | no | identical bytes | standard:max-line-length L1 |
| `app/src/main/java/eu/kanade/domain/chapter/interactor/MigrateChapterIdentity.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/domain/chapter/interactor/RecoverStaleChapter.kt` | no | identical bytes | standard:max-line-length L22 |
| `app/src/main/java/eu/kanade/domain/chapter/interactor/ResolveChapterRedirects.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/domain/chapter/interactor/SyncChaptersWithSource.kt` | no | identical bytes | standard:mixed-condition-operators L127 |
| `app/src/main/java/eu/kanade/domain/chapter/model/ChapterFilter.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/domain/manga/interactor/RecoverMangaCover.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/domain/manga/interactor/SetExcludedScanlators.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/domain/manga/interactor/UpdateManga.kt` | no | identical bytes | standard:max-line-length L17 |
| `app/src/main/java/eu/kanade/domain/track/service/DelayedTrackingUpdateJob.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/domain/track/store/DelayedTrackingStore.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/domain/ui/UiPreferences.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/account/AccountScreen.kt` | no | recovery copy fails | standard:max-line-length L1 |
| `app/src/main/java/eu/kanade/presentation/account/CloudSyncControls.kt` | no | recovery copy fails | standard:no-wildcard-imports L4 |
| `app/src/main/java/eu/kanade/presentation/account/LibraryShowcase.kt` | no | identical bytes | standard:no-wildcard-imports L6 |
| `app/src/main/java/eu/kanade/presentation/account/ProfileDisplayName.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/account/ProfileHeader.kt` | no | recovery copy fails | standard:no-wildcard-imports L4 |
| `app/src/main/java/eu/kanade/presentation/account/ProfileIdentity.kt` | no | identical bytes | standard:no-wildcard-imports L6 |
| `app/src/main/java/eu/kanade/presentation/account/ProfileProgressCard.kt` | no | added after recovery | standard:no-wildcard-imports L4 |
| `app/src/main/java/eu/kanade/presentation/account/ProfileShowcaseControls.kt` | no | identical bytes | standard:max-line-length L1 |
| `app/src/main/java/eu/kanade/presentation/account/RankArtwork.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/account/UsernameHandle.kt` | no | recovery copy fails | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/browse/BrowseSourceScreen.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/browse/components/BrowseSourceComfortableGrid.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/browse/components/BrowseSourceCompactGrid.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/browse/components/BrowseSourceList.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/browse/components/BrowseSourceToolbar.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/community/CommunityUi.kt` | no | identical bytes | standard:no-wildcard-imports L13 |
| `app/src/main/java/eu/kanade/presentation/community/PublicCommunityProfile.kt` | no | identical bytes | standard:no-wildcard-imports L4 |
| `app/src/main/java/eu/kanade/presentation/components/MangaroErrorState.kt` | no | identical bytes | standard:no-wildcard-imports L4 |
| `app/src/main/java/eu/kanade/presentation/components/MangaroSearchField.kt` | no | identical bytes | standard:no-wildcard-imports L4 |
| `app/src/main/java/eu/kanade/presentation/components/MangaroWorkCard.kt` | no | identical bytes | standard:no-wildcard-imports L5 |
| `app/src/main/java/eu/kanade/presentation/downloads/MangaroDownloadsScreen.kt` | no | identical bytes | standard:no-wildcard-imports L7 |
| `app/src/main/java/eu/kanade/presentation/history/HistoryScreen.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/history/components/HistoryItem.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/home/MangaroBottomNavigation.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/home/MangaroContinueReading.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/home/MangaroDiscoveryShelves.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/home/MangaroHomeDrawer.kt` | no | identical bytes | standard:max-line-length L1 |
| `app/src/main/java/eu/kanade/presentation/home/MangaroHomeHeader.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/home/MangaroMangaCard.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/home/MangaroNotificationPermissionPrompt.kt` | no | identical bytes | standard:max-line-length L127 |
| `app/src/main/java/eu/kanade/presentation/home/MangaroSearchScreen.kt` | no | identical bytes | standard:max-line-length L1 |
| `app/src/main/java/eu/kanade/presentation/home/MangaroSourceChip.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/home/MangaroSourceChooserSheet.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/home/MangaroStartupTransition.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/home/MangaroUpdateHighlights.kt` | no | recovery copy fails | canonical output differs; standard:no-wildcard-imports L6 |
| `app/src/main/java/eu/kanade/presentation/home/MangaroWeeklyPicks.kt` | no | identical bytes | standard:max-line-length L1 |
| `app/src/main/java/eu/kanade/presentation/inbox/InboxViewModel.kt` | no | identical bytes | standard:no-wildcard-imports L5 |
| `app/src/main/java/eu/kanade/presentation/inbox/NotificationCenterScreen.kt` | no | identical bytes | standard:max-line-length L1 |
| `app/src/main/java/eu/kanade/presentation/library/LibraryUpdateRefresh.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/library/MangaroLibraryScreen.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/library/MangaroLibraryShelfSheet.kt` | no | identical bytes | standard:max-line-length L145 |
| `app/src/main/java/eu/kanade/presentation/manga/MangaScreen.kt` | no | recovery copy fails | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/manga/components/ChapterHeader.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/manga/components/MangaChapterListItem.kt` | no | identical bytes | standard:max-line-length L177 |
| `app/src/main/java/eu/kanade/presentation/manga/components/MangaCoverDialog.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/manga/components/MangaInfoHeader.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/manga/components/MangaroMangaDetails.kt` | no | identical bytes | standard:max-line-length L316 |
| `app/src/main/java/eu/kanade/presentation/more/MoreScreen.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/more/onboarding/PermissionStep.kt` | no | identical bytes | standard:max-line-length L105 |
| `app/src/main/java/eu/kanade/presentation/more/settings/Preference.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsAdvancedScreen.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsReaderScreen.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsSecurityScreen.kt` | no | identical bytes | standard:max-line-length L49 |
| `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsTrackingScreen.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/more/settings/screen/about/AboutScreen.kt` | no | identical bytes | standard:max-line-length L85 |
| `app/src/main/java/eu/kanade/presentation/more/settings/screen/about/OpenSourceLicensesScreen.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/more/settings/screen/data/StorageInfo.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/more/settings/widget/EditTextPreferenceWidget.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/more/settings/widget/TrackingPreferenceWidget.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/more/stats/StatsScreenContent.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/novels/MangaroNovelIntegration.kt` | no | identical bytes | standard:max-line-length L1 |
| `app/src/main/java/eu/kanade/presentation/novels/NovelAdPlacement.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/novels/NovelCommunity.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/novels/NovelDownloadComponents.kt` | no | identical bytes | standard:no-wildcard-imports L5 |
| `app/src/main/java/eu/kanade/presentation/novels/NovelIllustration.kt` | no | identical bytes | standard:no-wildcard-imports L3 |
| `app/src/main/java/eu/kanade/presentation/novels/NovelLibraryShelfSheet.kt` | no | identical bytes | standard:no-wildcard-imports L3 |
| `app/src/main/java/eu/kanade/presentation/novels/NovelReaderProgress.kt` | no | added after recovery | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/novels/NovelReaderScreen.kt` | no | recovery copy fails | standard:no-wildcard-imports L9 |
| `app/src/main/java/eu/kanade/presentation/novels/NovelScreens.kt` | no | recovery copy fails | standard:max-line-length L1 |
| `app/src/main/java/eu/kanade/presentation/novels/SunovelsReaderEntry.kt` | no | identical bytes | standard:no-wildcard-imports L6 |
| `app/src/main/java/eu/kanade/presentation/reader/ChapterTransition.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/reader/MangaroChapterTransition.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/reader/mangaro/MangaroChapterSelectorSheet.kt` | no | identical bytes | standard:max-line-length L157 |
| `app/src/main/java/eu/kanade/presentation/reader/mangaro/MangaroReaderBottomPanel.kt` | no | identical bytes | standard:max-line-length L1 |
| `app/src/main/java/eu/kanade/presentation/reader/settings/ReadingModePage.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/sigils/SigilArtAssets.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/sigils/SigilArtCredits.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/sigils/SigilArtwork.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/sigils/SigilCollectionScreen.kt` | no | recovery copy fails | standard:no-wildcard-imports L3 |
| `app/src/main/java/eu/kanade/presentation/stories/ConnectedStoriesSection.kt` | no | added after recovery | standard:no-wildcard-imports L7 |
| `app/src/main/java/eu/kanade/presentation/stories/ConnectedStoryCard.kt` | no | added after recovery | standard:no-wildcard-imports L4 |
| `app/src/main/java/eu/kanade/presentation/theme/MangaroDesignSystem.kt` | no | identical bytes | standard:property-naming L43 |
| `app/src/main/java/eu/kanade/presentation/theme/TachiyomiTheme.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/track/TrackInfoDialogHome.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/updates/UpdatesScreen.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/updates/UpdatesUiItem.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/util/ContentErrorMessage.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/util/Permissions.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/presentation/util/TimeUtils.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/App.kt` | no | identical bytes | standard:max-line-length L123 |
| `app/src/main/java/eu/kanade/tachiyomi/data/account/AccountAvatarImage.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/data/account/AccountSessionStorage.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/data/account/LocalAccountActivities.kt` | no | identical bytes | standard:mixed-condition-operators L65 |
| `app/src/main/java/eu/kanade/tachiyomi/data/account/ProfileReadingStatisticsRepository.kt` | no | added after recovery | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/data/account/ProfileShowcaseRepository.kt` | no | identical bytes | standard:no-wildcard-imports L21 |
| `app/src/main/java/eu/kanade/tachiyomi/data/account/PublicFavoriteResolver.kt` | no | identical bytes | standard:no-wildcard-imports L4 |
| `app/src/main/java/eu/kanade/tachiyomi/data/account/SupabaseAccountAuth.kt` | no | recovery copy fails | standard:mixed-condition-operators L1 |
| `app/src/main/java/eu/kanade/tachiyomi/data/account/sync/CloudLocalGateway.kt` | no | recovery copy fails | standard:max-line-length L1 |
| `app/src/main/java/eu/kanade/tachiyomi/data/account/sync/CloudSyncStore.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/data/account/sync/SupabaseCloudSync.kt` | no | recovery copy fails | standard:max-line-length L1 |
| `app/src/main/java/eu/kanade/tachiyomi/data/ads/AdBlockerEvidence.kt` | YES | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/data/ads/AdBlockerNotice.kt` | YES | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/data/ads/AdPageReadiness.kt` | YES | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/data/ads/AdPrivacyUi.kt` | YES | identical bytes | standard:max-line-length L25 |
| `app/src/main/java/eu/kanade/tachiyomi/data/ads/DownloadAdGate.kt` | YES | identical bytes | standard:max-line-length L1 |
| `app/src/main/java/eu/kanade/tachiyomi/data/ads/OnceAction.kt` | YES | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/data/ads/ReaderAdPageActivity.kt` | YES | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/data/ads/ReaderAdPageHost.kt` | YES | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/data/ads/ReaderAdPages.kt` | YES | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/data/ads/SmartLinkController.kt` | YES | identical bytes | standard:no-wildcard-imports L11 |
| `app/src/main/java/eu/kanade/tachiyomi/data/ads/SmartLinkPolicy.kt` | YES | identical bytes | standard:max-line-length L250 |
| `app/src/main/java/eu/kanade/tachiyomi/data/ads/SmartLinkPrivacy.kt` | YES | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/data/ads/SmartLinkReaderResolution.kt` | YES | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/data/backup/create/BackupCreateJob.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/data/backup/create/creators/MangaBackupCreator.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/data/backup/create/creators/PreferenceBackupCreator.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/data/backup/restore/BackupRestoreJob.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/data/backup/restore/restorers/MangaRestorer.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/data/backup/restore/restorers/PreferenceRestorer.kt` | no | identical bytes | standard:max-line-length L63 |
| `app/src/main/java/eu/kanade/tachiyomi/data/cache/CacheMaintenance.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/data/cache/ChapterCache.kt` | no | identical bytes | standard:max-line-length L34 |
| `app/src/main/java/eu/kanade/tachiyomi/data/cache/CoverCache.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/data/cache/FirstUsableFrameGate.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/data/cache/StorageMaintenanceJob.kt` | no | identical bytes | standard:no-wildcard-imports L6 |
| `app/src/main/java/eu/kanade/tachiyomi/data/community/SupabaseCommunityRepository.kt` | no | identical bytes | standard:no-wildcard-imports L24 |
| `app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadCache.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadCompletionJournal.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadImageRefresh.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadJob.kt` | no | identical bytes | standard:no-wildcard-imports L27 |
| `app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadManager.kt` | no | identical bytes | standard:max-line-length L60 |
| `app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadNotifier.kt` | no | identical bytes | standard:max-line-length L167 |
| `app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadPendingDeleter.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadQueuePolicy.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadStore.kt` | no | identical bytes | standard:mixed-condition-operators L96 |
| `app/src/main/java/eu/kanade/tachiyomi/data/download/Downloader.kt` | no | identical bytes | standard:no-wildcard-imports L11 |
| `app/src/main/java/eu/kanade/tachiyomi/data/download/model/Download.kt` | no | identical bytes | standard:max-line-length L28 |
| `app/src/main/java/eu/kanade/tachiyomi/data/inbox/ReplyInbox.kt` | no | identical bytes | standard:no-wildcard-imports L12 |
| `app/src/main/java/eu/kanade/tachiyomi/data/inbox/WorkUpdateInbox.kt` | no | identical bytes | standard:max-line-length L23 |
| `app/src/main/java/eu/kanade/tachiyomi/data/library/LibraryUpdateJob.kt` | no | identical bytes | standard:max-line-length L287 |
| `app/src/main/java/eu/kanade/tachiyomi/data/library/LibraryUpdateNotifier.kt` | no | identical bytes | standard:max-line-length L157 |
| `app/src/main/java/eu/kanade/tachiyomi/data/library/MetadataUpdateJob.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/data/library/SourceHealthJob.kt` | no | identical bytes | standard:no-wildcard-imports L4 |
| `app/src/main/java/eu/kanade/tachiyomi/data/library/SourceHealthPersistence.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/data/notification/DiscoveryWorkDeduplicator.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/data/notification/NotificationRelevance.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/data/notification/Notifications.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/data/sigils/SigilRepository.kt` | no | identical bytes | standard:no-wildcard-imports L10 |
| `app/src/main/java/eu/kanade/tachiyomi/data/sigils/SigilStore.kt` | no | identical bytes | standard:no-wildcard-imports L9 |
| `app/src/main/java/eu/kanade/tachiyomi/data/stories/ConnectedNovelEditions.kt` | no | added after recovery | standard:no-wildcard-imports L3 |
| `app/src/main/java/eu/kanade/tachiyomi/data/stories/ConnectedStoriesRepository.kt` | no | added after recovery | standard:no-wildcard-imports L3 |
| `app/src/main/java/eu/kanade/tachiyomi/data/stories/ConnectedStoryMetadata.kt` | no | added after recovery | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/data/stories/ConnectedStoryResolver.kt` | no | added after recovery | standard:max-line-length L66 |
| `app/src/main/java/eu/kanade/tachiyomi/data/track/kitsu/Kitsu.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/data/updater/MandatoryUpdateController.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/data/updater/MangaroVersionConfig.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/data/updater/MangaroVersionRepository.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/di/AppModule.kt` | no | identical bytes | standard:max-line-length L200 |
| `app/src/main/java/eu/kanade/tachiyomi/extension/api/ExtensionApi.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/extension/api/ExtensionUpdateNotifier.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/focus/FocusCoordinator.kt` | no | identical bytes | standard:mixed-condition-operators L62 |
| `app/src/main/java/eu/kanade/tachiyomi/focus/FocusSurface.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/novels/CeneleChapterContent.kt` | no | recovery copy fails | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/novels/MknovSource.kt` | no | recovery copy fails | standard:no-wildcard-imports L7 |
| `app/src/main/java/eu/kanade/tachiyomi/novels/NovelCatalogCards.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/novels/NovelChapterGroups.kt` | no | identical bytes | standard:max-line-length L3 |
| `app/src/main/java/eu/kanade/tachiyomi/novels/NovelChapterIndexer.kt` | no | identical bytes | standard:max-line-length L1 |
| `app/src/main/java/eu/kanade/tachiyomi/novels/NovelCloudState.kt` | no | recovery copy fails | standard:no-wildcard-imports L4 |
| `app/src/main/java/eu/kanade/tachiyomi/novels/NovelContent.kt` | no | recovery copy fails | standard:max-line-length L19 |
| `app/src/main/java/eu/kanade/tachiyomi/novels/NovelContentQuality.kt` | no | added after recovery | standard:max-line-length L1 |
| `app/src/main/java/eu/kanade/tachiyomi/novels/NovelContentTypeClassifier.kt` | no | identical bytes | standard:max-line-length L103 |
| `app/src/main/java/eu/kanade/tachiyomi/novels/NovelDownloadDisk.kt` | no | recovery copy fails | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/novels/NovelDownloadQueue.kt` | no | recovery copy fails | standard:no-wildcard-imports L7 |
| `app/src/main/java/eu/kanade/tachiyomi/novels/NovelDownloadWorker.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/novels/NovelGuestMerge.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/novels/NovelHttp.kt` | no | identical bytes | standard:no-wildcard-imports L3 |
| `app/src/main/java/eu/kanade/tachiyomi/novels/NovelIdentity.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/novels/NovelIndexRecovery.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/novels/NovelMetadataStore.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/novels/NovelModels.kt` | no | recovery copy fails | standard:max-line-length L30 |
| `app/src/main/java/eu/kanade/tachiyomi/novels/NovelQueueSql.kt` | no | identical bytes | standard:property-naming L11 |
| `app/src/main/java/eu/kanade/tachiyomi/novels/NovelReadStatistics.kt` | no | added after recovery | standard:max-line-length L14 |
| `app/src/main/java/eu/kanade/tachiyomi/novels/NovelRepository.kt` | no | recovery copy fails | standard:max-line-length L1 |
| `app/src/main/java/eu/kanade/tachiyomi/novels/NovelRepositoryContentValidation.kt` | no | recovery copy fails | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/novels/NovelRuleManifest.kt` | no | identical bytes | standard:no-wildcard-imports L3 |
| `app/src/main/java/eu/kanade/tachiyomi/novels/NovelRuleSnapshot.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/novels/NovelRuleUpdateJob.kt` | no | identical bytes | standard:no-wildcard-imports L4 |
| `app/src/main/java/eu/kanade/tachiyomi/novels/NovelRules.kt` | no | identical bytes | standard:no-wildcard-imports L5 |
| `app/src/main/java/eu/kanade/tachiyomi/novels/NovelSourceAvailability.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/novels/NovelSources.kt` | no | identical bytes | standard:no-wildcard-imports L6 |
| `app/src/main/java/eu/kanade/tachiyomi/novels/SeaNovelReaderDiagnostics.kt` | no | identical bytes | standard:max-line-length L31 |
| `app/src/main/java/eu/kanade/tachiyomi/novels/SeaNovelSource.kt` | no | recovery copy fails | standard:no-wildcard-imports L7 |
| `app/src/main/java/eu/kanade/tachiyomi/novels/SunovelsAccess.kt` | no | identical bytes | standard:mixed-condition-operators L17 |
| `app/src/main/java/eu/kanade/tachiyomi/novels/SunovelsBootstrap.kt` | no | identical bytes | standard:no-wildcard-imports L4 |
| `app/src/main/java/eu/kanade/tachiyomi/novels/SunovelsDiagnostics.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/novels/SunovelsGateway.kt` | no | identical bytes | standard:max-line-length L1 |
| `app/src/main/java/eu/kanade/tachiyomi/novels/SunovelsIndexSnapshots.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/novels/SunovelsLocalSearch.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/novels/SunovelsMobileApi.kt` | no | identical bytes | standard:no-wildcard-imports L3 |
| `app/src/main/java/eu/kanade/tachiyomi/novels/SunovelsRequestPolicy.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/novels/SunovelsResolution.kt` | no | identical bytes | standard:no-wildcard-imports L3 |
| `app/src/main/java/eu/kanade/tachiyomi/novels/SunovelsSource.kt` | no | identical bytes | standard:max-line-length L1 |
| `app/src/main/java/eu/kanade/tachiyomi/search/AtlasCandidateIndex.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/search/AtlasDeclaredNames.kt` | no | identical bytes | standard:no-wildcard-imports L3 |
| `app/src/main/java/eu/kanade/tachiyomi/search/AtlasWorkIdentityResolver.kt` | no | identical bytes | standard:max-line-length L1 |
| `app/src/main/java/eu/kanade/tachiyomi/search/MangaSearchEvidence.kt` | no | identical bytes | standard:max-line-length L1 |
| `app/src/main/java/eu/kanade/tachiyomi/search/NovelSearchProjection.kt` | no | added after recovery | standard:no-wildcard-imports L3 |
| `app/src/main/java/eu/kanade/tachiyomi/search/NovelSearchSelection.kt` | no | recovery copy fails | standard:no-wildcard-imports L3 |
| `app/src/main/java/eu/kanade/tachiyomi/search/SearchResultPresentationFilter.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/search/SearchSessionCoordinator.kt` | no | added after recovery | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/search/SearchSnapshotCache.kt` | no | added after recovery | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/search/SearchWorkSelection.kt` | no | identical bytes | standard:mixed-condition-operators L91 |
| `app/src/main/java/eu/kanade/tachiyomi/search/WorkIdentityDiagnostics.kt` | no | identical bytes | standard:max-line-length L1 |
| `app/src/main/java/eu/kanade/tachiyomi/search/WorkIdentityResolverV2.kt` | no | identical bytes | standard:max-line-length L13 |
| `app/src/main/java/eu/kanade/tachiyomi/search/WorkPresentationProjection.kt` | no | added after recovery | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/source/AndroidSourceManager.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/source/MangaDarPageResolver.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/source/internal/azora/Azora.kt` | no | identical bytes | standard:max-line-length L105 |
| `app/src/main/java/eu/kanade/tachiyomi/source/internal/hijala/Hijala.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/source/internal/mangadar/MangaDar.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/source/internal/mangalek/MangaLek.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/source/internal/mangastarz/MangaStarz.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/source/internal/mangastarz/MangaStarzHtmlFetcher.kt` | no | identical bytes | standard:max-line-length L39 |
| `app/src/main/java/eu/kanade/tachiyomi/source/internal/mangaswat/MangaSwat.kt` | no | identical bytes | standard:max-line-length L142 |
| `app/src/main/java/eu/kanade/tachiyomi/source/internal/mangatime/MangaTime.kt` | no | identical bytes | standard:max-line-length L1 |
| `app/src/main/java/eu/kanade/tachiyomi/source/internal/teamx/TeamX.kt` | no | identical bytes | standard:max-line-length L212 |
| `app/src/main/java/eu/kanade/tachiyomi/source/internal/util/ChapterPagination.kt` | no | identical bytes | standard:mixed-condition-operators L38 |
| `app/src/main/java/eu/kanade/tachiyomi/source/internal/util/HtmlMangaIntegrity.kt` | no | identical bytes | standard:mixed-condition-operators L38 |
| `app/src/main/java/eu/kanade/tachiyomi/source/internal/util/SourceValidationUtil.kt` | no | identical bytes | standard:max-line-length L48 |
| `app/src/main/java/eu/kanade/tachiyomi/source/repair/RepairableSource.kt` | no | identical bytes | standard:no-wildcard-imports L3 |
| `app/src/main/java/eu/kanade/tachiyomi/source/repair/RuleInterpreter.kt` | no | identical bytes | standard:no-wildcard-imports L4 |
| `app/src/main/java/eu/kanade/tachiyomi/source/repair/RuleMaintenanceJob.kt` | no | identical bytes | standard:no-wildcard-imports L4 |
| `app/src/main/java/eu/kanade/tachiyomi/source/repair/RuleRepairEngine.kt` | no | identical bytes | standard:no-wildcard-imports L4 |
| `app/src/main/java/eu/kanade/tachiyomi/source/repair/RuleStore.kt` | no | identical bytes | standard:mixed-condition-operators L78 |
| `app/src/main/java/eu/kanade/tachiyomi/source/repair/SourceRules.kt` | no | identical bytes | standard:max-line-length L1 |
| `app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/globalsearch/GlobalSearchScreen.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/globalsearch/GlobalSearchViewModel.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/globalsearch/RecentSearches.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/globalsearch/SearchRelevance.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/globalsearch/SearchViewModel.kt` | no | recovery copy fails | standard:max-line-length L265 |
| `app/src/main/java/eu/kanade/tachiyomi/ui/category/CategoryViewModel.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/ui/download/DownloadQueueScreen.kt` | no | identical bytes | standard:max-line-length L168 |
| `app/src/main/java/eu/kanade/tachiyomi/ui/download/DownloadQueueViewModel.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/ui/download/MangaroDownloadsViewModel.kt` | no | identical bytes | standard:max-line-length L80 |
| `app/src/main/java/eu/kanade/tachiyomi/ui/genre/GenreBrowseViewModel.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/ui/genre/GenreMatcher.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/ui/history/HistoryTab.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/ui/history/HistoryViewModel.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/ui/home/CatalogDuplicateDiagnostics.kt` | no | identical bytes | standard:no-wildcard-imports L3 |
| `app/src/main/java/eu/kanade/tachiyomi/ui/home/CatalogEditionPreferences.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/ui/home/CatalogPerformance.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/ui/home/CatalogProjectionMemo.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/ui/home/CatalogWorkEvidence.kt` | no | identical bytes | standard:no-wildcard-imports L7 |
| `app/src/main/java/eu/kanade/tachiyomi/ui/home/DiscoveryCategoryGridScreen.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/ui/home/DiscoveryCategoryGridViewModel.kt` | no | identical bytes | standard:max-line-length L117 |
| `app/src/main/java/eu/kanade/tachiyomi/ui/home/DownloadsTab.kt` | no | identical bytes | standard:no-wildcard-imports L3 |
| `app/src/main/java/eu/kanade/tachiyomi/ui/home/ExpandedLatestScreen.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/ui/home/GroupDiscoveryItems.kt` | no | identical bytes | standard:max-line-length L51 |
| `app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeDiscoveryCache.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeScreen.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeTab.kt` | no | identical bytes | standard:max-line-length L196 |
| `app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeViewModel.kt` | no | identical bytes | standard:max-line-length L115 |
| `app/src/main/java/eu/kanade/tachiyomi/ui/home/PreferredMangaVariants.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/ui/home/ProgressiveCatalogBatch.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/ui/home/SearchTab.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/ui/home/WeeklyPicksViewModel.kt` | no | identical bytes | standard:no-wildcard-imports L9 |
| `app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryItem.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/ui/library/LibrarySettingsViewModel.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryTab.kt` | no | identical bytes | standard:no-wildcard-imports L7 |
| `app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryViewModel.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/ui/library/MangaroLibraryShelves.kt` | no | identical bytes | standard:max-line-length L42 |
| `app/src/main/java/eu/kanade/tachiyomi/ui/main/MainActivity.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaCoverViewModel.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreen.kt` | no | identical bytes | standard:no-wildcard-imports L44 |
| `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaViewModel.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt` | no | identical bytes | standard:max-line-length L301 |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt` | no | identical bytes | standard:no-wildcard-imports L27 |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/ChapterLoader.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/HttpPageLoader.kt` | no | identical bytes | standard:no-wildcard-imports L5 |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/ReaderLoadRecovery.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/model/ReaderChapter.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/model/ReaderPositionOwnership.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/GestureDetectorWithLongTap.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderPageImageView.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerPageHolder.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerTransitionHolder.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonPageHolder.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonTransitionHolder.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonViewer.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/ui/stats/StatsViewModel.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/ui/updates/UpdatesTab.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/ui/updates/UpdatesViewModel.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/ui/webview/WebViewActivity.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/ui/webview/WebViewViewModel.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/eu/kanade/tachiyomi/util/lang/DateExtensions.kt` | no | identical bytes | canonical output differs |
| `app/src/main/java/mihon/domain/source/interactor/UpdateMangaFromRemote.kt` | no | identical bytes | standard:max-line-length L1 |
| `app/src/main/java/mihon/feature/upcoming/components/calendar/CalendarHeader.kt` | no | identical bytes | canonical output differs |
| `app/src/readerScopeAudit/java/eu/kanade/tachiyomi/novels/NovelReaderScopeInstrumentation.kt` | no | recovery copy fails | standard:no-wildcard-imports L17 |
| `app/src/sunovelsAcceptance/java/eu/kanade/tachiyomi/novels/AccountIsolationInstrumentation.kt` | no | identical bytes | standard:no-wildcard-imports L12 |
| `app/src/sunovelsAcceptance/java/eu/kanade/tachiyomi/novels/CloudSyncAcceptanceInstrumentation.kt` | no | identical bytes | standard:no-wildcard-imports L9 |
| `app/src/sunovelsAcceptance/java/eu/kanade/tachiyomi/novels/FocusAcceptanceAudit.kt` | no | identical bytes | standard:no-wildcard-imports L11 |
| `app/src/sunovelsAcceptance/java/eu/kanade/tachiyomi/novels/NovelRecoveryInstrumentation.kt` | no | identical bytes | standard:no-wildcard-imports L10 |
| `app/src/sunovelsAcceptance/java/eu/kanade/tachiyomi/novels/SunovelsAcceptanceInstrumentation.kt` | no | identical bytes | standard:no-wildcard-imports L12 |
| `app/src/sunovelsAudit/java/eu/kanade/tachiyomi/novels/SunovelsAuditActivity.kt` | no | identical bytes | canonical output differs |
| `app/src/sunovelsAudit/java/eu/kanade/tachiyomi/novels/SunovelsRuntimeInstrumentation.kt` | no | identical bytes | standard:no-wildcard-imports L6 |
| `app/src/test/java/eu/kanade/domain/chapter/interactor/MigrateChapterIdentityTest.kt` | no | identical bytes | standard:no-wildcard-imports L5 |
| `app/src/test/java/eu/kanade/domain/chapter/interactor/RecoverStaleChapterTest.kt` | no | identical bytes | standard:no-wildcard-imports L12 |
| `app/src/test/java/eu/kanade/domain/chapter/interactor/ResolveChapterRedirectsTest.kt` | no | identical bytes | standard:no-wildcard-imports L7 |
| `app/src/test/java/eu/kanade/domain/chapter/interactor/SyncChapterIntegrityTest.kt` | no | identical bytes | standard:no-wildcard-imports L11 |
| `app/src/test/java/eu/kanade/domain/manga/interactor/LibraryMembershipTest.kt` | no | identical bytes | standard:no-wildcard-imports L5 |
| `app/src/test/java/eu/kanade/domain/manga/interactor/RecoverMangaCoverTest.kt` | no | identical bytes | standard:no-wildcard-imports L7 |
| `app/src/test/java/eu/kanade/domain/manga/model/MangaIntegrityTest.kt` | no | identical bytes | standard:max-line-length L12 |
| `app/src/test/java/eu/kanade/presentation/library/LibraryUpdateRefreshTest.kt` | no | identical bytes | canonical output differs |
| `app/src/test/java/eu/kanade/presentation/util/MangaroErrorMessageTest.kt` | no | identical bytes | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/data/account/AccountLogoutTest.kt` | no | recovery copy fails | standard:no-wildcard-imports L13 |
| `app/src/test/java/eu/kanade/tachiyomi/data/account/ProfileNovelStatisticsIntegrationTest.kt` | no | added after recovery | standard:no-wildcard-imports L5 |
| `app/src/test/java/eu/kanade/tachiyomi/data/account/ProfileReadingProductionTest.kt` | no | added after recovery | standard:max-line-length L19 |
| `app/src/test/java/eu/kanade/tachiyomi/data/account/ProfileReadingSqlTest.kt` | no | added after recovery | standard:max-line-length L54 |
| `app/src/test/java/eu/kanade/tachiyomi/data/account/ProfileReadingStatisticsTest.kt` | no | added after recovery | standard:no-wildcard-imports L4 |
| `app/src/test/java/eu/kanade/tachiyomi/data/account/ProfileShowcaseAdapterTest.kt` | no | identical bytes | standard:no-wildcard-imports L11 |
| `app/src/test/java/eu/kanade/tachiyomi/data/account/PublicProfileIdentityAdapterTest.kt` | no | recovery copy fails | standard:no-wildcard-imports L11 |
| `app/src/test/java/eu/kanade/tachiyomi/data/account/sync/CloudLocalGatewayTest.kt` | no | recovery copy fails | standard:no-wildcard-imports L5 |
| `app/src/test/java/eu/kanade/tachiyomi/data/account/sync/CloudSyncReconciliationTest.kt` | no | identical bytes | standard:no-wildcard-imports L5 |
| `app/src/test/java/eu/kanade/tachiyomi/data/account/sync/CloudSyncSchedulingTest.kt` | no | added after recovery | standard:no-wildcard-imports L5 |
| `app/src/test/java/eu/kanade/tachiyomi/data/ads/AdBlockerEvidenceTest.kt` | no | identical bytes | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/data/ads/AdPageReadinessTest.kt` | no | identical bytes | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/data/ads/NativeBoundaryIdentityTest.kt` | no | identical bytes | standard:no-wildcard-imports L6 |
| `app/src/test/java/eu/kanade/tachiyomi/data/ads/SmartLinkFullscreenGateTest.kt` | no | identical bytes | standard:no-wildcard-imports L4 |
| `app/src/test/java/eu/kanade/tachiyomi/data/ads/SmartLinkGateRecoveryTest.kt` | no | identical bytes | standard:no-wildcard-imports L3 |
| `app/src/test/java/eu/kanade/tachiyomi/data/ads/SmartLinkPrivacyTest.kt` | no | identical bytes | standard:no-wildcard-imports L7 |
| `app/src/test/java/eu/kanade/tachiyomi/data/ads/SmartLinkReaderEndTest.kt` | no | identical bytes | standard:max-line-length L1 |
| `app/src/test/java/eu/kanade/tachiyomi/data/cache/CacheMaintenanceTest.kt` | no | identical bytes | standard:no-wildcard-imports L16 |
| `app/src/test/java/eu/kanade/tachiyomi/data/cache/StartupIntroGateTest.kt` | no | identical bytes | standard:no-wildcard-imports L3 |
| `app/src/test/java/eu/kanade/tachiyomi/data/cache/StorageMaintenanceSchedulingTest.kt` | no | identical bytes | standard:no-wildcard-imports L12 |
| `app/src/test/java/eu/kanade/tachiyomi/data/community/CatalogMappingAdapterTest.kt` | no | identical bytes | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/data/download/AccountDownloadOwnershipTest.kt` | no | identical bytes | standard:no-wildcard-imports L5 |
| `app/src/test/java/eu/kanade/tachiyomi/data/download/DownloadNotifierTest.kt` | no | identical bytes | standard:no-wildcard-imports L5 |
| `app/src/test/java/eu/kanade/tachiyomi/data/download/DownloadRecoveryTest.kt` | no | identical bytes | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/data/download/DownloadStoreDurabilityTest.kt` | no | identical bytes | standard:no-wildcard-imports L7 |
| `app/src/test/java/eu/kanade/tachiyomi/data/inbox/InboxStateTest.kt` | no | identical bytes | standard:no-wildcard-imports L5 |
| `app/src/test/java/eu/kanade/tachiyomi/data/inbox/ReplyInboxAdapterTest.kt` | no | identical bytes | standard:no-wildcard-imports L10 |
| `app/src/test/java/eu/kanade/tachiyomi/data/inbox/WorkUpdateInboxTest.kt` | no | identical bytes | standard:no-wildcard-imports L6 |
| `app/src/test/java/eu/kanade/tachiyomi/data/library/SourceRefreshPolicyTest.kt` | no | identical bytes | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/data/notification/DiscoveryWorkDeduplicatorTest.kt` | no | identical bytes | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/data/notification/NotificationRelevanceTest.kt` | no | identical bytes | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/data/stories/ConnectedNovelEditionsTest.kt` | no | added after recovery | standard:no-wildcard-imports L3 |
| `app/src/test/java/eu/kanade/tachiyomi/data/stories/ConnectedStoriesCanonicalProductionTest.kt` | no | added after recovery | standard:no-wildcard-imports L3 |
| `app/src/test/java/eu/kanade/tachiyomi/data/stories/ConnectedStoriesPresentationContractTest.kt` | no | added after recovery | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/data/stories/ConnectedStoriesProductionTest.kt` | no | added after recovery | standard:no-wildcard-imports L3 |
| `app/src/test/java/eu/kanade/tachiyomi/data/stories/ConnectedStoryMetadataTest.kt` | no | added after recovery | standard:no-wildcard-imports L4 |
| `app/src/test/java/eu/kanade/tachiyomi/data/stories/ConnectedStoryNavigationTest.kt` | no | added after recovery | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/data/stories/StoryFixtures.kt` | no | added after recovery | standard:no-wildcard-imports L3 |
| `app/src/test/java/eu/kanade/tachiyomi/data/updater/MangaroVersionConfigTest.kt` | no | identical bytes | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/data/updater/UpdateHighlightsStateTest.kt` | no | recovery copy fails | standard:no-wildcard-imports L5 |
| `app/src/test/java/eu/kanade/tachiyomi/focus/CatalogPriorityTest.kt` | no | identical bytes | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/focus/FocusCoordinatorTest.kt` | no | identical bytes | standard:no-wildcard-imports L3 |
| `app/src/test/java/eu/kanade/tachiyomi/focus/FocusTransportBaselineTest.kt` | no | identical bytes | standard:no-wildcard-imports L4 |
| `app/src/test/java/eu/kanade/tachiyomi/network/interceptor/CloudflareInterceptorTest.kt` | no | identical bytes | standard:max-line-length L48 |
| `app/src/test/java/eu/kanade/tachiyomi/novels/CeneleChapterContentTest.kt` | no | identical bytes | standard:max-line-length L15 |
| `app/src/test/java/eu/kanade/tachiyomi/novels/CeneleIllustrationChapterTest.kt` | no | added after recovery | standard:max-line-length L15 |
| `app/src/test/java/eu/kanade/tachiyomi/novels/FadaaPublicChapterProbeTest.kt` | no | recovery copy fails | standard:max-line-length L23 |
| `app/src/test/java/eu/kanade/tachiyomi/novels/Legacy203SourceFixtures.kt` | no | identical bytes | standard:no-wildcard-imports L2 |
| `app/src/test/java/eu/kanade/tachiyomi/novels/MknovLiveAcceptanceTest.kt` | no | identical bytes | standard:max-line-length L33 |
| `app/src/test/java/eu/kanade/tachiyomi/novels/MknovSourceTest.kt` | no | recovery copy fails | standard:no-wildcard-imports L4 |
| `app/src/test/java/eu/kanade/tachiyomi/novels/NovelCatalogCardsTest.kt` | no | identical bytes | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/novels/NovelCloudMetadataTest.kt` | no | identical bytes | standard:no-wildcard-imports L4 |
| `app/src/test/java/eu/kanade/tachiyomi/novels/NovelContentQualityTest.kt` | no | added after recovery | standard:no-wildcard-imports L5 |
| `app/src/test/java/eu/kanade/tachiyomi/novels/NovelContentTypeClassifierTest.kt` | no | identical bytes | standard:max-line-length L1 |
| `app/src/test/java/eu/kanade/tachiyomi/novels/NovelContentTypePresentationTest.kt` | no | recovery copy fails | standard:no-wildcard-imports L5 |
| `app/src/test/java/eu/kanade/tachiyomi/novels/NovelDownloadRemovalTest.kt` | no | identical bytes | standard:no-wildcard-imports L3 |
| `app/src/test/java/eu/kanade/tachiyomi/novels/NovelDownloadSourceRegressionTest.kt` | no | identical bytes | standard:max-line-length L93 |
| `app/src/test/java/eu/kanade/tachiyomi/novels/NovelGuestMergeTest.kt` | no | identical bytes | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/novels/NovelIndexPerformanceTest.kt` | no | identical bytes | standard:no-wildcard-imports L4 |
| `app/src/test/java/eu/kanade/tachiyomi/novels/NovelIndexRecoveryTest.kt` | no | identical bytes | standard:no-wildcard-imports L5 |
| `app/src/test/java/eu/kanade/tachiyomi/novels/NovelIndexRetryRecoveryTest.kt` | no | identical bytes | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/novels/NovelPartialRuleTest.kt` | no | identical bytes | standard:no-wildcard-imports L4 |
| `app/src/test/java/eu/kanade/tachiyomi/novels/NovelQueueSqlTest.kt` | no | identical bytes | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/novels/NovelReadStatisticsTest.kt` | no | added after recovery | standard:no-wildcard-imports L4 |
| `app/src/test/java/eu/kanade/tachiyomi/novels/NovelReaderEndReachabilityTest.kt` | no | added after recovery | standard:no-wildcard-imports L3 |
| `app/src/test/java/eu/kanade/tachiyomi/novels/NovelReaderScopeTest.kt` | no | identical bytes | standard:no-wildcard-imports L4 |
| `app/src/test/java/eu/kanade/tachiyomi/novels/NovelRetirementTest.kt` | no | identical bytes | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/novels/NovelRuleUpdateTest.kt` | no | identical bytes | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/novels/NovelShortChapterLiveTest.kt` | no | added after recovery | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/novels/NovelSourceReliabilityLiveTest.kt` | no | added after recovery | standard:max-line-length L1 |
| `app/src/test/java/eu/kanade/tachiyomi/novels/NovelSourceTest.kt` | no | identical bytes | standard:max-line-length L1 |
| `app/src/test/java/eu/kanade/tachiyomi/novels/NovelUnifiedTest.kt` | no | identical bytes | standard:max-line-length L1 |
| `app/src/test/java/eu/kanade/tachiyomi/novels/SeaNovelReliabilityTest.kt` | no | added after recovery | standard:max-line-length L12 |
| `app/src/test/java/eu/kanade/tachiyomi/novels/SunovelsLiveAcceptanceTest.kt` | no | identical bytes | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/novels/SunovelsNumberingRegressionTest.kt` | no | identical bytes | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/novels/SunovelsSourceWideReliabilityTest.kt` | no | identical bytes | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/search/AtlasBenchmarkTest.kt` | no | identical bytes | standard:no-wildcard-imports L3 |
| `app/src/test/java/eu/kanade/tachiyomi/search/AtlasCoreSafetyTest.kt` | no | identical bytes | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/search/AtlasNameAcquisitionTest.kt` | no | identical bytes | standard:no-wildcard-imports L11 |
| `app/src/test/java/eu/kanade/tachiyomi/search/AtlasProductionSearchTest.kt` | no | identical bytes | standard:max-line-length L1 |
| `app/src/test/java/eu/kanade/tachiyomi/search/AtlasPublicSearchCorpusTest.kt` | no | identical bytes | standard:no-wildcard-imports L3 |
| `app/src/test/java/eu/kanade/tachiyomi/search/AtlasRecordedEvaluationTest.kt` | no | identical bytes | standard:no-wildcard-imports L3 |
| `app/src/test/java/eu/kanade/tachiyomi/search/AtlasResolutionTest.kt` | no | identical bytes | standard:no-wildcard-imports L4 |
| `app/src/test/java/eu/kanade/tachiyomi/search/HistoricalSearchIdentity.kt` | no | recovery copy fails | standard:max-line-length L1 |
| `app/src/test/java/eu/kanade/tachiyomi/search/MangaIdentityEnrichmentV2Test.kt` | no | identical bytes | standard:no-wildcard-imports L7 |
| `app/src/test/java/eu/kanade/tachiyomi/search/MangaSearchCatalogRecoveryTest.kt` | no | recovery copy fails | standard:no-wildcard-imports L13 |
| `app/src/test/java/eu/kanade/tachiyomi/search/MangaTimeSearchEvidenceTest.kt` | no | recovery copy fails | standard:no-wildcard-imports L6 |
| `app/src/test/java/eu/kanade/tachiyomi/search/NovelSearchProjectionTest.kt` | no | added after recovery | standard:no-wildcard-imports L3 |
| `app/src/test/java/eu/kanade/tachiyomi/search/NovelSearchSelectionTest.kt` | no | identical bytes | standard:no-wildcard-imports L3 |
| `app/src/test/java/eu/kanade/tachiyomi/search/PreAtlasIdentityResolver.kt` | no | identical bytes | standard:max-line-length L64 |
| `app/src/test/java/eu/kanade/tachiyomi/search/SearchDeduplicationRegressionTest.kt` | no | recovery copy fails | standard:no-wildcard-imports L5 |
| `app/src/test/java/eu/kanade/tachiyomi/search/SearchEnrichmentLifecycleTest.kt` | no | recovery copy fails | standard:no-wildcard-imports L5 |
| `app/src/test/java/eu/kanade/tachiyomi/search/SearchFoundationBenchmarkTest.kt` | no | added after recovery | standard:no-wildcard-imports L4 |
| `app/src/test/java/eu/kanade/tachiyomi/search/SearchFoundationProductionTest.kt` | no | added after recovery | standard:no-wildcard-imports L6 |
| `app/src/test/java/eu/kanade/tachiyomi/search/SearchFoundationTest.kt` | no | added after recovery | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/search/SearchPresentationBenchmarkTest.kt` | no | recovery copy fails | standard:mixed-condition-operators L57 |
| `app/src/test/java/eu/kanade/tachiyomi/search/SearchResultPresentationFilterTest.kt` | no | recovery copy fails | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/search/SearchWorkSelectionTest.kt` | no | identical bytes | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/search/VerifiedCatalogIdentityTest.kt` | no | identical bytes | standard:no-wildcard-imports L7 |
| `app/src/test/java/eu/kanade/tachiyomi/search/WorkIdentityPublicDetailProbeTest.kt` | no | identical bytes | standard:max-line-length L1 |
| `app/src/test/java/eu/kanade/tachiyomi/search/WorkIdentityPublicSearchProbeTest.kt` | no | identical bytes | standard:no-wildcard-imports L3 |
| `app/src/test/java/eu/kanade/tachiyomi/search/WorkIdentityRecordedCorpusTest.kt` | no | identical bytes | standard:no-wildcard-imports L3 |
| `app/src/test/java/eu/kanade/tachiyomi/search/WorkIdentityResolverV2Test.kt` | no | identical bytes | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/search/WorkIdentityV2BenchmarkTest.kt` | no | identical bytes | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/source/AndroidSourceManagerRegistryTest.kt` | no | identical bytes | standard:max-line-length L22 |
| `app/src/test/java/eu/kanade/tachiyomi/source/MangaDarPageResolverTest.kt` | no | identical bytes | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/source/TeamXFallbackAvailabilityTest.kt` | no | identical bytes | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/source/internal/ReliabilityAndCollapseProtectionTest.kt` | no | identical bytes | standard:max-line-length L80 |
| `app/src/test/java/eu/kanade/tachiyomi/source/internal/SourceRepresentationTest.kt` | no | identical bytes | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/source/internal/azora/AzoraParserTest.kt` | no | identical bytes | standard:max-line-length L228 |
| `app/src/test/java/eu/kanade/tachiyomi/source/internal/hijala/HijalaParserTest.kt` | no | identical bytes | standard:max-line-length L75 |
| `app/src/test/java/eu/kanade/tachiyomi/source/internal/mangadar/MangaDarParserTest.kt` | no | identical bytes | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/source/internal/mangalek/MangaLekParserTest.kt` | no | identical bytes | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/source/internal/mangastarz/MangaStarzParserTest.kt` | no | identical bytes | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/source/internal/mangaswat/MangaSwatTest.kt` | no | identical bytes | standard:max-line-length L102 |
| `app/src/test/java/eu/kanade/tachiyomi/source/internal/mangatime/MangaTimeParserTest.kt` | no | identical bytes | standard:max-line-length L1 |
| `app/src/test/java/eu/kanade/tachiyomi/source/internal/teamx/TeamXLiveIntegrationTest.kt` | no | identical bytes | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/source/internal/teamx/TeamXParserTest.kt` | no | identical bytes | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/source/internal/util/ThreeSourceIntegrityTest.kt` | no | identical bytes | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/source/repair/RuleRepairEngineTest.kt` | no | identical bytes | standard:max-line-length L1 |
| `app/src/test/java/eu/kanade/tachiyomi/ui/browse/source/globalsearch/SearchExperienceTest.kt` | no | recovery copy fails | standard:no-wildcard-imports L10 |
| `app/src/test/java/eu/kanade/tachiyomi/ui/download/MangaroDownloadsViewModelTest.kt` | no | identical bytes | standard:no-wildcard-imports L8 |
| `app/src/test/java/eu/kanade/tachiyomi/ui/history/ReadingHistoryTest.kt` | no | identical bytes | standard:max-line-length L11 |
| `app/src/test/java/eu/kanade/tachiyomi/ui/home/CanonicalWorkPreferenceTest.kt` | no | identical bytes | standard:no-wildcard-imports L4 |
| `app/src/test/java/eu/kanade/tachiyomi/ui/home/CatalogPerformanceRecoveryTest.kt` | no | identical bytes | standard:no-wildcard-imports L5 |
| `app/src/test/java/eu/kanade/tachiyomi/ui/home/DiscoveryCategoryPaginationTest.kt` | no | identical bytes | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/ui/home/GroupDiscoveryItemsTest.kt` | no | identical bytes | standard:max-line-length L25 |
| `app/src/test/java/eu/kanade/tachiyomi/ui/home/HomeCatalogSelectionTest.kt` | no | identical bytes | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/ui/home/HomeCompletedDiscoveryTest.kt` | no | identical bytes | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/ui/home/HomeDiscoveryAggregationTest.kt` | no | identical bytes | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/ui/home/HomeEarlyLoadingTest.kt` | no | identical bytes | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/ui/home/HomeNewDiscoveryTest.kt` | no | identical bytes | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/ui/home/ReviewedCatalogRegistryTest.kt` | no | identical bytes | standard:no-wildcard-imports L3 |
| `app/src/test/java/eu/kanade/tachiyomi/ui/home/SmartCatalogV2Test.kt` | no | identical bytes | standard:no-wildcard-imports L5 |
| `app/src/test/java/eu/kanade/tachiyomi/ui/home/WeeklyPicksViewModelTest.kt` | no | identical bytes | standard:no-wildcard-imports L6 |
| `app/src/test/java/eu/kanade/tachiyomi/ui/library/LibraryPresentationTest.kt` | no | identical bytes | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/ui/manga/MangaChapterStateTest.kt` | no | identical bytes | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/ui/reader/loader/PageImageUrlTest.kt` | no | identical bytes | canonical output differs |
| `app/src/test/java/eu/kanade/tachiyomi/ui/reader/loader/ReaderReliabilityTest.kt` | no | identical bytes | standard:no-wildcard-imports L5 |
| `app/src/test/java/eu/kanade/tachiyomi/ui/reader/model/ReaderChapterSelectionTest.kt` | no | identical bytes | canonical output differs |
| `app/src/test/java/mihon/domain/source/interactor/ChapterMetadataCacheTest.kt` | no | identical bytes | standard:no-wildcard-imports L4 |
| `app/src/test/java/mihon/domain/source/interactor/ChapterRefreshRecoveryTest.kt` | no | identical bytes | standard:no-wildcard-imports L6 |
| `app/src/test/java/mihon/domain/source/interactor/SourceRefreshConcurrencyTest.kt` | no | identical bytes | standard:no-wildcard-imports L6 |
| `app/src/test/java/tachiyomi/data/chapter/SourceChapterTransactionTest.kt` | no | identical bytes | standard:no-wildcard-imports L6 |
| `app/src/test/java/tachiyomi/data/source/SourcePagingReliabilityTest.kt` | no | identical bytes | canonical output differs |
| `app/src/test/java/tachiyomi/domain/manga/service/WorkLinkerTest.kt` | no | identical bytes | standard:no-wildcard-imports L4 |
| `core/common/src/main/kotlin/tachiyomi/core/common/preference/AccountPreferenceStore.kt` | no | identical bytes | standard:no-wildcard-imports L4 |
| `core/common/src/main/kotlin/tachiyomi/core/common/preference/LocalPersonalPreferenceKeys.kt` | no | identical bytes | canonical output differs |
| `core/common/src/main/kotlin/tachiyomi/core/common/util/lang/CoroutinesExtensions.kt` | no | identical bytes | canonical output differs |
| `core/common/src/main/kotlin/tachiyomi/core/common/util/lang/LocalAccountScope.kt` | no | identical bytes | standard:mixed-condition-operators L26 |
| `core/common/src/main/kotlin/tachiyomi/core/common/util/lang/WesternDigits.kt` | no | identical bytes | canonical output differs |
| `core/common/src/test/java/tachiyomi/core/common/preference/LocalAccountIsolationTest.kt` | no | identical bytes | standard:no-wildcard-imports L6 |
| `data/src/main/java/tachiyomi/data/LocalAccountWorkspace.kt` | no | identical bytes | standard:max-line-length L27 |
| `data/src/main/java/tachiyomi/data/QueryExtension.kt` | no | identical bytes | canonical output differs |
| `data/src/main/java/tachiyomi/data/category/CategoryRepositoryImpl.kt` | no | identical bytes | canonical output differs |
| `data/src/main/java/tachiyomi/data/chapter/ChapterRepositoryImpl.kt` | no | identical bytes | standard:max-line-length L128 |
| `data/src/main/java/tachiyomi/data/history/HistoryRepositoryImpl.kt` | no | identical bytes | canonical output differs |
| `data/src/main/java/tachiyomi/data/manga/MangaRepositoryImpl.kt` | no | identical bytes | canonical output differs |
| `data/src/main/java/tachiyomi/data/source/SourcePagingSource.kt` | no | identical bytes | standard:max-line-length L42 |
| `data/src/main/java/tachiyomi/data/track/TrackRepositoryImpl.kt` | no | identical bytes | canonical output differs |
| `domain/src/main/java/mihon/domain/account/AccountFoundation.kt` | no | identical bytes | standard:max-line-length L265 |
| `domain/src/main/java/mihon/domain/account/CloudSyncFoundation.kt` | no | identical bytes | standard:max-line-length L8 |
| `domain/src/main/java/mihon/domain/account/RankMilestoneTracker.kt` | no | identical bytes | canonical output differs |
| `domain/src/main/java/mihon/domain/account/RankVisualStyle.kt` | no | identical bytes | canonical output differs |
| `domain/src/main/java/mihon/domain/account/ShowcaseDraft.kt` | no | identical bytes | canonical output differs |
| `domain/src/main/java/mihon/domain/community/CommunityFoundation.kt` | no | identical bytes | standard:max-line-length L192 |
| `domain/src/main/java/mihon/domain/community/WeeklyCommunityPicks.kt` | no | identical bytes | canonical output differs |
| `domain/src/main/java/mihon/domain/sigils/RealmSigils.kt` | no | identical bytes | canonical output differs |
| `domain/src/main/java/mihon/domain/source/discovery/interactor/GetSourceDiscovery.kt` | no | identical bytes | standard:max-line-length L1 |
| `domain/src/main/java/mihon/domain/source/health/SourceHealthMonitor.kt` | no | identical bytes | canonical output differs |
| `domain/src/main/java/tachiyomi/domain/chapter/model/Chapter.kt` | no | identical bytes | standard:max-line-length L23 |
| `domain/src/main/java/tachiyomi/domain/chapter/model/ChapterUpdate.kt` | no | identical bytes | standard:max-line-length L21 |
| `domain/src/main/java/tachiyomi/domain/chapter/repository/ChapterRepository.kt` | no | identical bytes | canonical output differs |
| `domain/src/main/java/tachiyomi/domain/chapter/service/ChapterIdentity.kt` | no | identical bytes | canonical output differs |
| `domain/src/main/java/tachiyomi/domain/chapter/service/ChapterListIntegrity.kt` | no | identical bytes | standard:max-line-length L93 |
| `domain/src/main/java/tachiyomi/domain/library/service/LibraryPreferences.kt` | no | identical bytes | canonical output differs |
| `domain/src/main/java/tachiyomi/domain/manga/model/CoverUrl.kt` | no | identical bytes | standard:max-line-length L14 |
| `domain/src/main/java/tachiyomi/domain/manga/model/DiscoveryEligibility.kt` | no | identical bytes | canonical output differs |
| `domain/src/main/java/tachiyomi/domain/manga/model/Manga.kt` | no | identical bytes | standard:max-line-length L46 |
| `domain/src/main/java/tachiyomi/domain/manga/model/MangaCover.kt` | no | identical bytes | standard:max-line-length L14 |
| `domain/src/main/java/tachiyomi/domain/manga/model/MangaUpdate.kt` | no | identical bytes | standard:max-line-length L30 |
| `domain/src/main/java/tachiyomi/domain/manga/service/ReviewedCatalogMappings.kt` | no | identical bytes | canonical output differs |
| `domain/src/main/java/tachiyomi/domain/manga/service/ReviewedCatalogRegistry.kt` | no | identical bytes | standard:mixed-condition-operators L35 |
| `domain/src/main/java/tachiyomi/domain/manga/service/VerifiedCatalogIdentity.kt` | no | identical bytes | canonical output differs |
| `domain/src/main/java/tachiyomi/domain/manga/service/WorkLinker.kt` | no | identical bytes | standard:mixed-condition-operators L69 |
| `domain/src/main/java/tachiyomi/domain/storage/service/StorageManager.kt` | no | identical bytes | standard:max-line-length L72 |
| `domain/src/test/java/mihon/domain/account/AccountHardeningTest.kt` | no | identical bytes | canonical output differs |
| `domain/src/test/java/mihon/domain/account/AutomaticCloudBindingTest.kt` | no | identical bytes | canonical output differs |
| `domain/src/test/java/mihon/domain/account/CloudSyncPolicyTest.kt` | no | identical bytes | canonical output differs |
| `domain/src/test/java/mihon/domain/account/ProfileIdentityTest.kt` | no | identical bytes | canonical output differs |
| `domain/src/test/java/mihon/domain/account/RankMilestoneTrackerTest.kt` | no | identical bytes | canonical output differs |
| `domain/src/test/java/mihon/domain/account/RankVisualStyleTest.kt` | no | identical bytes | canonical output differs |
| `domain/src/test/java/mihon/domain/account/ShowcaseDraftTest.kt` | no | identical bytes | canonical output differs |
| `domain/src/test/java/mihon/domain/community/CommunitySpoilerTest.kt` | no | identical bytes | canonical output differs |
| `domain/src/test/java/mihon/domain/community/CommunityViewerIsolationTest.kt` | no | identical bytes | canonical output differs |
| `domain/src/test/java/mihon/domain/community/WeeklyCommunityPicksTest.kt` | no | identical bytes | canonical output differs |
| `domain/src/test/java/mihon/domain/sigils/RealmSigilsTest.kt` | no | identical bytes | canonical output differs |
| `domain/src/test/java/mihon/domain/source/discovery/SourceDiscoveryFoundationTest.kt` | no | identical bytes | canonical output differs |
| `domain/src/test/java/mihon/domain/source/health/SourceHealthMonitorTest.kt` | no | identical bytes | standard:max-line-length L15 |
| `domain/src/test/java/tachiyomi/domain/chapter/service/ChapterIdentityTest.kt` | no | identical bytes | canonical output differs |
| `domain/src/test/java/tachiyomi/domain/chapter/service/MissingChaptersTest.kt` | no | identical bytes | canonical output differs |
