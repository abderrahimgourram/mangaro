# AdMob foundation and Reader-safe policy

Starting HEAD: `e1ccd3274b79f3867a0cc68ef5f09c9baa04fabd`.

## SDK and configuration

Google Mobile Ads **Next-Gen 1.5.0** (`ads-mobile-sdk`) and **UMP 4.0.0**.
The existing minSdk 26 / compileSdk 37 / Kotlin 2.4.10 meet Next-Gen's requirements.
Application code uses Next-Gen APIs exclusively; no Legacy SDK is added.

The manifest and initialization use App ID `ca-app-pub-6220636202579444~2080855468`.
`AdIds.forBuild(BuildConfig.BUILD_TYPE)` selects production units only for `release`.
Debug, preview, benchmark and FOSS use official Google test units, including non-debuggable test variants.

| Format | Debug/test | Release |
| --- | --- | --- |
| Native | `ca-app-pub-3940256099942544/2247696110` | `ca-app-pub-6220636202579444/1208515081` |
| Interstitial | `ca-app-pub-3940256099942544/1033173712` | `ca-app-pub-6220636202579444/2333894491` |
| Rewarded | `ca-app-pub-3940256099942544/5224354917` | `ca-app-pub-6220636202579444/5696666840` |

Official references: [Next-Gen quick start](https://developers.google.com/admob/android/next-gen/quick-start),
[UMP privacy](https://developers.google.com/admob/android/next-gen/privacy),
[native assets](https://developers.google.com/admob/android/next-gen/native/advanced).

## Consent and lifecycle

After MainActivity's local startup UI becomes usable, request fresh UMP information once per process launch
(retrying an interrupted consent flow after Activity destruction),
then show any required consent form. Consent is neither copied into preferences nor inferred from a custom toggle.
Every request rechecks UMP `canRequestAds()`, SDK readiness, connectivity and ad-free policy.
Initialization happens once, on a background dispatcher; local app startup never waits for ads.
UMP may allow prior consent after a failed update; its authoritative status controls the outcome.
No SDK initialization or requests occur while consent is unresolved/disallowed.

Settings → **الخصوصية والإعلانات** exposes UMP Privacy Options when UMP requires them.
Requests pause while the user revisits privacy, and cached ads are discarded.
Existing target-audience classification is unchanged; no child-directed/under-age flags were guessed.
Ad-free rewards do not suppress privacy UI.

The application owns the manager and holds only weak full-screen Activity references.
Native ads belong to their boundary surface once claimed; leaving/rebinding/hiding destroys them.
Late native responses for ended sessions are destroyed. Full-screen generations reject obsolete callbacks.
Owner destruction, dismissal, synchronous SDK exceptions and failed shows release the download continuation once.
No automatic retry loop or SDK rolling preloader is used: one pending request per placement, a 35-second load
watchdog, and at least 60 seconds between subsequent load attempts. Future deliberate entry points may retry.
Offline requests are skipped; no recurring error snackbars are emitted.

## Placements

- **Chapter-boundary native:** only the Next/end transition, between the existing finished and next text.
  Transition views may be prefetched, so completion and actual view visibility both gate the placement.
  Selecting a ready final page after an earlier page records a completion independently of read/unread and XP.
  The first three distinct completions in a Reader session are ad-free; eligible boundaries are 4, 7, 10, 13, …,
  with a maximum of four per rolling hour. Revisited boundaries cannot bypass the three-completion separation. Native objects may preload at the third completed boundary.
  Reserved displays conservatively count toward the cap even if interrupted. Native unavailable means no ad,
  no loading placeholder and no wait for chapter navigation. The SDK owns asset clicks and automatic AdChoices;
  an explicit Arabic **إعلان** label and reserved AdChoices space distinguish the ad from manga content.
- **Download interstitial:** Manga Details and Updates' explicit download batches first confirm operations
  exceeding 50 requested chapters. If a ready ad and policy allow, show one ad; then automatically enqueue the
  already-confirmed operation. At most two interstitials per rolling 30 minutes. A two-minute separation after
  dismissal prevents back-to-back full-screen ads, including rewarded ads. Missing/expired/offline/failed ads
  immediately continue the batch. Reader download-ahead, automatic downloads and small batches never show ads.
  The existing unused Library bulk methods have no active UI entry point and remain unchanged.
- **Rewarded ad-free:** the explicit Settings CTA alone opens rewarded ads. Google's earned callback grants
  30 minutes without native/interstitial ads; opening/dismissing does not. The grant is deduplicated and stored
  privately on this device. A monotonic in-process clock prevents clock changes extending the reward; restored
  expiration is bounded to 30 minutes. No XP, account privileges or transferable reward is granted. No SSV or
  backend work is introduced; the rewarded callback remains centralized for a future SSV implementation.

Frequency timestamps and reward expiration persist in private local preferences. Session chapter identity
is in memory and discarded on Reader ViewModel disposal. No ad-unit IDs or SDK APIs live in page loaders/renderers.
Ad creative length is controlled by Google; the two-minute policy gap and load watchdog do not set ad duration.

No App Open, banner, rewarded-interstitial, analytics, Firebase or backend integration is added.
Auth, progression, Community, spoilers, notifications, Cloud semantics, manga source identity, source engine,
page rendering, GitHub Actions, publisher and Showcase are unchanged.

## Verification

Focused JVM policy and mocked SDK callback tests cover configuration, consent, initialization deduplication,
offline/failure continuation, lifecycle disposal, native session/placement gating, caps, earned-only rewards,
expiration, load cooldowns and stale callbacks. They make no ad-network calls.
Android rendering, AdChoices and UMP forms require the manual test checklist below.
No ADB, emulator or APK installation is used.

## Manual checklist — debug APK / Google test ads only

1. Launch and complete UMP consent where required.
2. Confirm all local features work if consent prevents requests.
3. Read through early chapters: first three completions have no native ad.
4. At an eligible chapter end, verify a Native **test** ad between finished/next.
5. Verify **إعلان**, readable assets, and visible/actionable AdChoices.
6. Confirm no ad appears inside pages/panels or during page turns.
7. Continue reading and verify three-chapter separation / four-per-hour cap.
8. Download up to 50 chapters: no interstitial.
9. Confirm a larger batch: at most one Interstitial **test** ad.
10. Dismiss: the confirmed batch starts without a second confirmation.
11. Unavailable/failed ad: the same operation starts normally.
12. Open Settings → الخصوصية والإعلانات; revisit UMP Privacy Options when required.
13. Explicitly tap the rewarded CTA: only that action opens a Rewarded **test** ad.
14. Earn the reward: native/interstitial ads are suppressed for 30 minutes.
15. Close before earning: no reward is granted.
16. Test offline/reconnect; no blocked actions or retry/error spam.
17. Rotate, background and return while loading/showing; no stale ad view or duplicate batch.
18. Verify navigation and downloads remain usable after dismissal/failure, including rapid taps.

Never click live production ads during verification.

## Final verification and changed files

28 focused tests passed (16 policy / 12 mocked SDK callback tests), including assertions that the actual
request objects passed to the mocked SDK carry official Google test unit IDs. The generated debug
BuildConfig resolves `BUILD_TYPE = "debug"`; the packaged manifest has the required App ID.
`./gradlew :app:assembleDebug` passed. Output: `app/build/outputs/apk/debug/app-universal-debug.apk`.
Runtime UMP rendering, Native AdChoices and full-screen creative display remain manual checks.

Changed files (all relative to this project):

- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/eu/kanade/tachiyomi/data/ads/AdPolicy.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/ads/AdManager.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/ads/NativeBoundaryAd.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/ads/DownloadAdGate.kt`
- `app/src/main/java/eu/kanade/presentation/more/settings/screen/AdsSettingsScreen.kt`
- `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsMainScreen.kt`
- `app/src/main/java/eu/kanade/presentation/reader/ChapterTransition.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/main/MainActivity.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreen.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaViewModel.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderTransitionView.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerTransitionHolder.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonTransitionHolder.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/updates/UpdatesTab.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/updates/UpdatesViewModel.kt`
- `app/src/test/java/eu/kanade/tachiyomi/data/ads/AdPolicyTest.kt`
- `app/src/test/java/eu/kanade/tachiyomi/data/ads/AdManagerTest.kt`
- `docs/admob-foundation.md`
