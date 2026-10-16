# Mangaro notification, permission and error presentation implementation

Worktree HEAD remains `a3cddc54fe2d69e6d37ac91d2ba88c4134d45902`. Existing uncommitted InMobi integrations, build configuration, source repairs, downloads and library changes were preserved. The InMobi source and test files match the snapshot taken before this task. No reset, commit, push, deployment, public Release APK, emulator, ADB command or device runtime test was performed for Mangaro.

## Notification causes and corrected triggers

| Existing path | Cause | Implemented behavior |
|---|---|---|
| Home initial discovery → DiscoveryWorkDeduplicator → Android new-works alert | Popular/latest/new catalog observations shared one baseline. Previously unseen to this device was incorrectly equated with newly published. No opt-in, publication evidence or useful destination. | Removed the Home posting path and the deduplicator's Android poster. Catalogue refreshes/order changes never alert. No reliable publication timestamps/events exist in these results, so enabling the new-works preference does not fabricate alerts. |
| UpdateMangaFromRemote → WorkUpdateInbox; LibraryUpdateJob → LibraryUpdateNotifier | First library imports, backfills and incomplete results could enter presentation; Android delivery lacked persistent event claims. | Only unread, unseen chapters in an existing library work after a verified complete fetch qualify. Reordering, same-URL metadata changes, first import, incomplete results and older numeric backfills are silent. Database reconciliation and download eligibility remain unchanged. |
| Notifications.createChannels | Active library progress channel was also in the deprecated-channel deletion list. | Keep that channel and its user-selected channel settings across app startup. |
| Library/metadata workers | Foreground notification started even for an empty queue; separate queue-size warnings added non-actionable alerts. | No foreground work for an empty queue. Remove the queue-size notification behavior. Real ongoing workers retain required low-importance progress/cancel notifications, and cancel obsolete progress/warning IDs at exit. |
| ExtensionApi → ExtensionUpdateNotifier | Repeated checks reposted identical names without target-version identity. | Persist package + offered version + library-version events, serialize concurrent claims, group names in one actionable extension-update alert, cancel when no updates remain. |
| Downloader and novel queue → DownloadNotifier / shared DownloadJob | Volatile counters reset during worker and downloader starts. Counts were not event identities. Manga finishing could cancel a worker still downloading novels. | Durable completion journal, restored per-queue manga event IDs, and existing novel task creation timestamps. Same retry/restart cannot replay a success; an intentional new queue operation can notify again. One digest after the shared batch ends, never one alert per chapter. Retain progress, pause/resume/cancel behavior and shared manga/novel foreground work. |
| MainActivity / NotificationReceiver dismissal | Negative notification IDs were treated as invalid; canceling a two-item group returned before removing the child. | Recognize a supplied notification extra regardless of its sign; remove both the final child and its obsolete group summary. |
| ReplyInbox → InboxViewModel → existing NotificationCenter | Backend already has reply-ID uniqueness, recipient ownership/RLS and no-self-reply trigger. | Preserve that backend and navigation. Also defensively exclude self-replies/duplicate rows on the client. Existing generation checks reject late results from a previous account. Reply-category preference controls automatic badge refresh, without deleting remote read state. |

Other existing notifications were traced and retained where useful: explicit image saves expose Open/Share; manually requested backup creation exposes the actual saved file/Share; restoration reports its actual outcome; their foreground progress is required. Automatic backup success/failure was already silent and remains so. Cancellation of backup/restore no longer posts an error/completion alert. Backup/image error notification text is now contextual Arabic rather than raw exception text, and old success actions are cleared on failure. Incognito retains its ongoing privacy-state indicator/disable action with only-alert-once behavior. App-updater delivery is currently disabled by the existing build configuration; that configuration and publishing infrastructure were not changed.

## Retained notifications and controls

The existing Security settings now contain individual controls for new works (opt-in, default off), replies, chapter updates, download completion and extension updates. Disabling completion/extension alerts clears the old optional notification. Required foreground notifications have no off switch.

Chapter alerts use the existing group summary, with at most five work children retaining reader, mark-read, view-chapters and download actions. System delivery claims persist before posting; marking local inbox events read cancels their Android alerts. Publication checks unread state under the same lock as mark-read, so delayed cover work cannot recreate an already-read alert. Updating an existing notification uses only-alert-once behavior, with no arbitrary cooldown that would merge unrelated event identities. Account switches and chapter-category opt-out clear optional chapter alerts; delayed cover loading checks account/category again before posting.

The existing two-tab notification center remains. It has Arabic RTL styling, timestamps and unread/read distinction, clearer chapter/reply labels, original content navigation and the shared recoverable error state. Work notices acquire an optional owner field; existing unowned notices remain preserved as device-guest notices rather than being assigned to an unknown account. Guest/account transitions do not delete another account's read records. The journal stays bounded at 200 displayed events; dedup identities are not discarded after an arbitrary 4,000-event cutoff.

Replies are an in-app inbox, refreshed through existing foreground entry points and manual pagination. There is no FCM/push receiver or new background polling service, and no promise of reply push while the app is closed. Library updates continue through existing WorkManager schedules and Android restrictions. Android may stop/defer workers; foreground visibility remains required. Android 13+ permission prompts record handling when a user requests or dismisses them, including onboarding, so Home does not immediately ask again.

## Permissions

Removed actual manifest grants: `ACCESS_WIFI_STATE` and protected `READ_APP_SPECIFIC_LOCALES`. Own-language handling does not require reading other apps' locales. Wi-Fi constraints now use ConnectivityManager/NetworkCapabilities, not enabled-state/BSSID reads. Removed `requestLegacyExternalStorage` and `preserveLegacyExternalStorage` application flags.

No broad read/all-files, location, Bluetooth, phone-state or Wi-Fi-change grant appears in either newly merged Debug or Release manifest. These absent permissions were audited, not falsely reported as removed grants. InMobi documents Wi-Fi/location targeting permissions as optional: [Android integration guidance](https://support.inmobi.com/monetize/android-guidelines/overview-android-guidelines).

One necessary legacy permission is restored with a hard API cap: `WRITE_EXTERNAL_STORAGE`, `maxSdkVersion=28`, for public reader-image/cover exports on Android 8/9. Both Save actions use a reusable Arabic Mangaro explanation followed by Android's own system-controlled dialog, only on explicit Save. Android 10+ already uses MediaStore and never requests this grant. Download locations and other user-selected file operations retain their existing SAF/URI-grant behavior.

Both variants have the same inventory except their generated package-specific signature permission:

| Final permission | Technical reason / scope |
|---|---|
| INTERNET | Source/novel requests, authentication, community, sync, ads and CMP. |
| ACCESS_NETWORK_STATE | WorkManager/network constraints and required InMobi network checks. |
| WRITE_EXTERNAL_STORAGE (≤28 only) | Explicit public image/cover Save on Android 8/9; never granted/requested on 10+. |
| FOREGROUND_SERVICE | Required active downloads, long library/metadata work and extension installer. |
| FOREGROUND_SERVICE_DATA_SYNC | WorkManager foreground service dataSync type. |
| WAKE_LOCK | WorkManager keeps legitimate jobs executing; no new permanent wake lock. |
| REQUEST_IGNORE_BATTERY_OPTIMIZATIONS | Existing optional user-requested download/background reliability setting. |
| REQUEST_INSTALL_PACKAGES | User-managed external source APK installation. |
| REQUEST_DELETE_PACKAGES | Existing source extension removal. |
| UPDATE_PACKAGES_WITHOUT_USER_ACTION | Existing PackageInstaller update sessions explicitly use USER_ACTION_NOT_REQUIRED on supported Android versions; Android retains its checks. |
| QUERY_ALL_PACKAGES | Dynamic extension discovery via installed package metadata; extensions do not share a single queryable activity intent. Also retains installed WebView provider discovery. |
| POST_NOTIFICATIONS | Optional Android 13+ alerts; essential foreground work is preserved if denied. |
| USE_BIOMETRIC | Existing app lock/authentication. |
| USE_FINGERPRINT | Android 8 fingerprint fallback from the existing biometric dependency. |
| com.google.android.gms.permission.AD_ID | Existing InMobi advertising-ID integration, under the existing consent/CMP handling. |
| RECEIVE_BOOT_COMPLETED | WorkManager reschedules durable jobs after reboot. |
| app.manhwaar.reader[.dev].DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION | AndroidX-generated signature permission protecting dynamic receivers. |
| moe.shizuku.manager.permission.API_V23 | Existing optional Shizuku extension installer; no new privilege path. |

Merged manifests: `app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml` and `app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml`.

## Error presentation and lifecycle

Reusable `MangaroErrorState` and `MangaroSnackbarHost` use the existing near-black/deep-purple/gold design tokens, rounded shapes, Arabic typography, RTL and restrained content-size animation. These are wired into real source-browse failures, manga/Home/source snackbars, account errors, cloud-sync errors, download-manager errors, novel failure screens and notification-center retry errors.

Both existing native reader image-error layouts now use the same palette and Arabic Retry/browser actions without replacing pager gestures. Initial chapter failure stays in the reader with a real Retry action instead of closing into a Toast. Adjacent chapter, image-export and WebView sharing failures use the branded native Snackbar. Tracking login failures no longer Toast raw exception details. Foreground contexts use Snackbar; a headless context cannot host one and uses safe contextual Toast feedback. Chapter loading and cover operations rethrow lifecycle cancellation. Source paging remembers the displayed error/content-progress identity so automatic retries do not repeatedly show the same Snackbar.

The exception formatter maps transport/HTTP/timeout failures to safe Arabic text. Arbitrary raw class names, source routes and backend error messages are no longer shown through it. Shared string presentation preserves app-authored Arabic explanations but rejects obvious URLs, SQL/backend details and credential patterns. Existing diagnostic logging/recovery actions remain. Existing account conflict/merge decisions and Android/CMP system dialogs were preserved, rather than turning minor errors into critical popups.

No backend migration or SQL/schema change was made. Local JSON gains optional backwards-compatible notice owner and manga queue event-ID fields. Novel completion reads its existing `created` column; no novel database migration is required.

## Validation and limitations

Final validation: Debug Kotlin/Java compilation and resource processing succeeded; 24 focused JUnit tests passed with zero failures/errors across notification relevance, catalogue observation, completion persistence, work inbox ownership/claims, account-switch/read-state handling, real-SDK reply decoding against a local fixture, and safe error formatting. Both Debug and Release main manifests regenerated successfully. `git diff --check` passed. `:app:lintDebug` completed; its report retains the four untouched-source errors below, with no error-severity findings in task-modified files.

Validation command: `./gradlew :app:compileDebugKotlin :app:processDebugMainManifest :app:processReleaseMainManifest :app:testDebugUnitTest --tests '*NotificationRelevanceTest' --tests '*DiscoveryWorkDeduplicatorTest' --tests '*DownloadNotifierTest' --tests '*WorkUpdateInboxTest' --tests '*InboxStateTest' --tests '*ReplyInboxAdapterTest' --tests '*MangaroErrorMessageTest' :app:lintDebug`.

Logs: `/tmp/mangaro-notification-audit/final-validation.log`; final missing-source UI compilation: `/tmp/mangaro-notification-audit/final-compile.log`. Unit report: `app/build/reports/tests/testDebugUnitTest/index.html`. Lint report: `app/build/reports/lint-results-debug.html`. Runtime appearance, Android channel behavior, notification-permission handling, old-Android export grants and mixed manga/novel lifecycle behavior still require the user's manual phone tests. Static/unit validation is not represented as a device test.

Known existing lint findings are in untouched calendar/grid code: three `NonObservableLocale` errors (Calendar.kt/CalendarHeader.kt) and one `UnusedBoxWithConstraintsScope` error (DiscoveryCategoryGridScreen.kt). The project's existing `abortOnError=false` setting is unchanged; a successful lint task does not mean those findings disappeared.

## Manual Android checklist

1. Fresh startup, repeated startup, catalog refresh and library scan with no new chapters: no new-work/chapter/completion alert. Empty library does not start a foreground update notification.
2. Enable chapter alerts; add a favorite with its initial list; refresh metadata/reorder chapters; simulate a partial fetch and an older numeric backfill. All stay silent. A verified higher, unread chapter creates one grouped event; retry/restart does not duplicate it.
3. Open/mark chapter alerts, including the last child of a group. Check stale summary removal and existing reader/mark-read/download actions. Mark inbox rows read; timestamps and unread badge remain consistent after recreation.
4. Reply to your own comment, receive another user's reply, inspect a spoiler reply and paginate. Check no self notice/duplicate, spoiler-safe context and correct thread destination. Switch accounts while a request or cover load is pending; prior-account replies/work alerts must not appear.
5. Download a manga batch, a novel batch and a mixed batch. Check one final digest, independent progress, pause/resume/cancel, loss of network, worker retry/restart and no stale progress after completion. An intentional later re-download is a new completion event; a retry of the same queue event is not.
6. Toggle each optional category in Security settings; essential active-download foreground visibility remains. New-works opt-in does not produce alerts from rank/catalog observations. Replies remain foreground/manual inbox delivery.
7. Android 13+: deny notification permission, finish onboarding, restart and rotate the Activity; no unsolicited repeat prompt. Existing settings/user gesture can request permission again. Denied optional notifications must not block downloads.
8. Android 8/9: Save an image and cover; cancel/deny/retry/grant the Arabic explanation/system dialog and verify the actual export. Android 10+: Save without a storage permission request. Confirm SAF downloads/imports still use selected locations.
9. Force offline, DNS failure, timeout, HTTP error, failed image/chapter and failed account/sync operations. Verify natural Arabic error text and functional Retry, with no raw routes/tokens/stack traces. Leave the screen during loading; cancellation must not create an error popup.
10. Regression-check manga/manhwa and novel reading/progress, library/history, community/profile/cloud sync, XP/levels/badges, native InMobi placements and Choice consent.

## Task file inventory

Compared against the pre-task worktree snapshot, including its uncommitted files:

- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/eu/kanade/presentation/account/AccountScreen.kt`
- `app/src/main/java/eu/kanade/presentation/account/CloudSyncControls.kt`
- `app/src/main/java/eu/kanade/presentation/browse/BrowseSourceScreen.kt`
- `app/src/main/java/eu/kanade/presentation/components/MangaroErrorState.kt`
- `app/src/main/java/eu/kanade/presentation/downloads/MangaroDownloadsScreen.kt`
- `app/src/main/java/eu/kanade/presentation/home/MangaroNotificationPermissionPrompt.kt`
- `app/src/main/java/eu/kanade/presentation/inbox/InboxViewModel.kt`
- `app/src/main/java/eu/kanade/presentation/inbox/NotificationCenterScreen.kt`
- `app/src/main/java/eu/kanade/presentation/manga/MangaScreen.kt`
- `app/src/main/java/eu/kanade/presentation/more/onboarding/PermissionStep.kt`
- `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsSecurityScreen.kt`
- `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsTrackingScreen.kt`
- `app/src/main/java/eu/kanade/presentation/novels/NovelScreens.kt`
- `app/src/main/java/eu/kanade/presentation/util/ContentErrorMessage.kt`
- `app/src/main/java/eu/kanade/presentation/util/ExceptionFormatter.kt`
- `app/src/main/java/eu/kanade/presentation/util/Permissions.kt`
- `app/src/main/java/eu/kanade/tachiyomi/App.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/BackupNotifier.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/create/BackupCreateJob.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/restore/BackupRestoreJob.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadCompletionJournal.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadJob.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadNotifier.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadStore.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/download/Downloader.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/download/model/Download.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/inbox/ReplyInbox.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/inbox/WorkUpdateInbox.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/library/LibraryUpdateJob.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/library/LibraryUpdateNotifier.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/library/MetadataUpdateJob.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/notification/DiscoveryWorkDeduplicator.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/notification/NotificationPreferences.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/notification/NotificationReceiver.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/notification/NotificationRelevance.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/notification/Notifications.kt`
- `app/src/main/java/eu/kanade/tachiyomi/di/AppModule.kt`
- `app/src/main/java/eu/kanade/tachiyomi/extension/api/ExtensionApi.kt`
- `app/src/main/java/eu/kanade/tachiyomi/extension/api/ExtensionUpdateNotifier.kt`
- `app/src/main/java/eu/kanade/tachiyomi/novels/NovelDownloadQueue.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/browse/BrowseSourceScreen.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeTab.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeViewModel.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/main/MainActivity.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaCoverViewModel.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreen.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/SaveImageNotifier.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/ChapterLoader.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerPageHolder.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerTransitionHolder.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonPageHolder.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonTransitionHolder.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/webview/WebViewActivity.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/webview/WebViewViewModel.kt`
- `app/src/main/java/eu/kanade/tachiyomi/util/system/NetworkExtensions.kt`
- `app/src/main/java/eu/kanade/tachiyomi/util/system/NetworkStateTracker.kt`
- `app/src/main/java/mihon/domain/source/interactor/UpdateMangaFromRemote.kt`
- `app/src/main/res/drawable/mangaro_error_surface.xml`
- `app/src/main/res/layout/reader_error.xml`
- `app/src/test/java/eu/kanade/presentation/util/MangaroErrorMessageTest.kt`
- `app/src/test/java/eu/kanade/tachiyomi/data/download/DownloadNotifierTest.kt`
- `app/src/test/java/eu/kanade/tachiyomi/data/inbox/InboxStateTest.kt`
- `app/src/test/java/eu/kanade/tachiyomi/data/inbox/WorkUpdateInboxTest.kt`
- `app/src/test/java/eu/kanade/tachiyomi/data/notification/DiscoveryWorkDeduplicatorTest.kt`
- `app/src/test/java/eu/kanade/tachiyomi/data/notification/NotificationRelevanceTest.kt`
- `docs/NOTIFICATIONS_PERMISSIONS_ERRORS.md`
