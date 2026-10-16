# Mangaro — direct InMobi integration handoff

## Implementation

- Ads SDK: `com.inmobi.monetization:inmobi-ads-kotlin:11.5.0`.
- Certified consent SDK: `com.inmobi:inmobicmp:2.4.3`. Its POM omits dependencies, so CardView, Gson, IAB GPP encoder and lifecycle LiveData are declared explicitly. Existing AndroidX/Material, Kotlin, coroutines, OkHttp and Okio versions are retained. Ads SDK provides Media3 1.4.1, Picasso and OMSDK transitively.
- SDK identity: account `2437b98e2f6b45789a828a2faad3d0eb`. Dashboard App Key `10000240753` is never used as an initialization account. All placement IDs are `Long` constants in `InMobiConfiguration.kt`.
- One application-scoped controller, one main-thread manual Ads SDK initialization, bounded loads, no automatic startup/return/exit ads, no rewarded functionality. Dashboard controls test versus live serving; there is no APK test-mode flag.
- Manga native `10000837492`: existing completed-chapter transition surface and existing eligibility policy. First three completed chapters are protected; opportunities start at chapter four, then every three completed chapters, maximum four impressions/hour. SDK impressions commit the claim, not HTTP success or view construction.
- Novel native `10000837493`: below the chapter heading and before original blocks. Heading remains the same lazy-list item; paragraph keys/indexes/anchors and offline text are unchanged. One opportunity per chapter identity, without importing the manga completion threshold. SDK `MediaView`, creative text/icon/CTA, AdChoices and `registerViewForTracking` own presentation, impressions and click handling. No synthetic creative.
- Download interstitial `10000837491`: current batch threshold of 50, maximum two impressions/30 minutes, minimum 90 seconds between fullscreen impressions. Downloads are enqueued first. Only an already-ready, nonexpired ad can show from a resumed Activity; no late callback displays an ad, and no worker initiates one. Foreground download-capable surfaces may preload. Reading-screen download actions stay fullscreen-free.
- Privacy denial/withdrawal and CMP changes discard cached ads. No old reward preference is consulted. A legacy rejected-provider preference is read only to preserve rejection; positive old-provider consent is not reused. Privacy options remain available in More.

## Required operator configuration — not performed

1. InMobi Inventory Settings: verify App Key, production package `app.manhwaar.reader` and these three DIRECT SDK placements. Keep dashboard Test Mode Global/On during your manual acceptance; turn it Off for all three after approval. This transition requires no APK change.
2. Configure download placement Ad Controls as **Video Ads Only**. SDK 11.5.0 Interstitial has no public video-only request or creative-type readiness API. A standard interstitial is not itself proof of video. The app therefore fails closed until the website operator explicitly confirms that setting for the exact account/placement.
3. Choice CMP is wired directly to SDK p-code `UYWLq5tgD_uwk` (full dashboard code `p-UYWLq5tgD_uwk`) and property package `app.manhwaar.reader`; neither Ads account ID nor App Key is substituted. The release applicationId matches that property. Debug retains `.dev`; compiling it is not production-package runtime acceptance. Maven CMP 2.4.3 exactly matches the supplied Android AAR; only Maven is installed. Official changelog confirms TCF 2.3 support since 2.4.1.
   A read-only configuration fetch for the exact property returned HTTP 200 JSON. Published metadata advertises GDPR and English; it does not prove US/GPP activation or the final consent UI. Review Arabic, applicable regional frameworks, vendor 333 disclosure, TCF 2.3 theme and audience settings in Choice before manual acceptance. The config field named `inmobiAccountId` contains the p-code; the Ads SDK still initializes with the separate supplied Ads account ID.
4. Local website `ad-config.json` has an `inmobi` object. CMP uses the supplied code in the app and has no website-config dependency. Set `videoAdsOnly` true **only after verifying the dashboard restriction** for the exact account/placement. Preserve unrelated legacy fields for older clients. Publish website changes only through a separately authorized deployment; nothing was deployed here. No TC strings or private credentials belong in public configuration.
5. Until the real CMP configuration yields valid signals, ad requests are blocked. Missing jurisdiction is not inferred from language, locale or IP. For GDPR, a CMP-generated TC string matching stored IAB data, storage permission, vendor 333 consent and TCF 2.3 disclosed-vendor evidence are required. The local BASIC preference additionally refuses personalized purpose grants; if the provider cannot demonstrate a compatible nonpersonalized request, reading/downloads remain available without ads. The app never manufactures a consent string or overwrites CMP-owned IAB preferences. Outside GDPR, jurisdiction must still be established by the CMP. CMP UI and advertising do not overlap. US requests require genuine matching GPP and applicable opt-out data from the SDK, not inferred geography. Non-applicable-region notices do not invalidate a correctly loaded CMP. Saving failures persist a fail-closed marker; a successful official UI consent callback is required to clear it. Corrupt stored consent types and SDK privacy-update errors fail closed without crashing the reader. Local DISABLED withdraws permission immediately, clears cached ads and leaves official privacy options accessible without silently re-enabling ads.
6. Confirm app/account approval and placement activation. The reported Pending Review status was provided by the user, not independently verified in the portal. The actual download page is `https://mangaro-web.vercel.app/download`. For direct APK distribution, verify InMobi's unpublished-app approval path/support requirements; never invent a Play Store URL.
7. Deploy the locally prepared privacy disclosure and app-ads.txt. It includes the supplied `inmobi.com, aac51c156ae14c498723ec632f95e06f, DIRECT, 83e75a7ae333ca9d` record. The old Start.io DIRECT authorization is removed; unrelated existing sellers and the Start.io RESELLER records in InMobi's supplied seller chain are retained. Canonical `/home/jalem/Projects/app-ads.txt` and separate `ads.txt` remain unchanged. Validate the deployed plain-text HTTPS file and developer-domain association afterward.

## User manual acceptance

No device/emulator, unit-suite, instrumentation, app runtime or ad request was executed for this migration.

1. Use a compatible normally signed update; preserve installed app data. Complete the genuine CMP choices.
2. Read manga/manhwa forward to an eligible completed-chapter boundary. Verify labelled native media, CTA/AdChoices and unobstructed navigation. Back navigation/recomposition must not duplicate the opportunity.
3. Open a novel chapter: title, labelled native creative if available, then unchanged paragraphs. Check scrolling, reading position and offline text.
4. On a download-capable details screen, allow the bounded preload, then request an eligible batch. Queue/start must happen immediately; a video appears only if already ready and explicitly authorized as video-only.
5. Close/back out of the ad: queue, notifications and progress must continue. With no fill/offline/declined consent, reading and downloading must still work.
6. Check restart, background/resume, privacy withdrawal and normal library/search/reader functionality. No reward UI, 30-minute session, splash or return ad may appear.
7. Classify test and live delivery using actual SDK callbacks plus dashboard Test Mode; application logs intentionally say AD_LOADED/AD_DISPLAYED, not LIVE solely because a build is Release. Never click live ads to force monetization.

Debug compilation and static verification are recorded in the accompanying local task report. The earlier minified build predates the final supplied CMP configuration and is not runtime acceptance of these final changes. Runtime and paid delivery remain **NOT VERIFIED** until your manual acceptance; no APK has been published.

Official references: [Ads SDK](https://support.inmobi.com/monetize/sdk-documentation/android-guidelines/overview-android-guidelines), [Native 2.0](https://support.inmobi.com/monetize/sdk-documentation/android-guidelines/native-ads-for-android), [Interstitial](https://support.inmobi.com/monetize/sdk-documentation/android-guidelines/interstitial-ads-for-android), [Choice CMP](https://support.inmobi.com/choice/getting-started-cmp/implementing-cmp-via-code/mobile-app/android-app-implementation-sdk), [Testing](https://support.inmobi.com/monetize/sdk-documentation/android-guidelines/android-testing-and-troubleshooting).

## Final CMP completion: allowed static validation

- `:app:assembleDebug --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2g`: BUILD SUCCESSFUL in 26 seconds, 268 tasks (7 executed, 261 up-to-date). No test/device tasks were executed.
- Debug merged manifest: package `app.manhwaar.reader.dev`, INTERNET/ACCESS_NETWORK_STATE present, CMPActivity plus InMobi render/browser activities; no Start.io/Google Ads components, location or Bluetooth permissions.
- `git diff --check`: passed. HEAD and staged index preserved. SHA-256 comparison against `/tmp/mangaro-inmobi-cmp-final-initial.json` shows changes only to the following seven paths; all other recorded worktree files remain identical:
  - `app/src/main/java/eu/kanade/tachiyomi/data/ads/AdPrivacyUi.kt`
  - `app/src/main/java/eu/kanade/tachiyomi/data/ads/InMobiAds.kt`
  - `app/src/main/java/eu/kanade/tachiyomi/data/ads/InMobiConfiguration.kt`
  - `app/src/main/java/eu/kanade/tachiyomi/data/ads/InMobiConsent.kt`
  - `app/src/main/java/eu/kanade/tachiyomi/data/ads/InMobiPrivacyManager.kt`
  - `app/src/main/java/eu/kanade/tachiyomi/data/ads/InMobiVideoControls.kt`
  - `docs/INMOBI_HANDOFF.md`
- The sole remaining `startio_privacy_v1` string is a read-only migration safeguard preserving prior rejection. It is not a provider, authorization, SDK initialization or advertising request.
- No commits, pushes, APK installation/publication, website deployment or runtime acceptance occurred.
