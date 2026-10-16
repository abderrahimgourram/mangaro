# Mangaro startup privacy and permission audit

Source review of the current worktree at HEAD `a3cddc54fe2d69e6d37ac91d2ba88c4134d45902`. Existing changes, including Home caching/performance and reader hold handling, are preserved. No app, device, emulator, test suite, or advertising request was executed.

## Startup surfaces traced

| Original surface | Origin and reason | Result |
| --- | --- | --- |
| Android launch splash | MainActivity / platform launch theme | Retained; exits with the first frame, without a new timer. |
| Mangaro video intro, minimum three seconds | MainActivity → StartupIntroSession → MangaroStartupTransition; branding | Removed from startup. No video decoding or minimum intro wait. |
| Release 21 highlights / Continue overlay | MainActivity → UpdateHighlightsState → MangaroUpdateHighlights; local upgrade announcement | Removed from startup. No acknowledgement is needed to read. |
| “خلّك على اطلاع بكل جديد” notification invitation | MainActivity.ShowNotificationPromptIfNeeded → MangaroNotificationPermissionPrompt | Removed from startup. It previously offered a button that launched Android's actual notification permission dialog. |
| “مرحبًا بك في Mangaro” / “موافق، متابعة” | Home drawer → InitialAdPrivacyChoice; app-owned Terms/Privacy acknowledgement stored as welcome_notice_v1 | Removed. The code did not link this acknowledgement to a required account transaction or genuine CMP consent. No replacement acknowledgement, automatic acceptance, or privacy-consent record is written. |
| “اختيارات الإعلانات” custom choice dialog | InitialAdPrivacyChoice → AdPrivacyDialog; local advertising restriction followed by CMP | Removed from startup. Existing dialog remains exclusively behind the user's More → Advertising Privacy action. |
| Official Choice CMP GDPR / applicable US screen | InMobiPrivacyManager → ChoiceCmp.startChoice and SDK callbacks; regulatory advertising choices | Retained. SDK owns applicability, genuine decisions, expiry/reconsent, persistence, and automatic presentation. Initialization starts after the real interface has drawn. |
| Optional app-version announcement | MandatoryUpdateController, confirmed newer website version | Automatic optional dialog disabled. Minimum-version enforcement is retained. |
| Mandatory minimum-version update dialog | MandatoryUpdateController, confirmed minimum supported version in the existing backend | Retained conditional technical gate. No change to version publication, URLs, signing, or backend. |
| Local initialization failure | MainActivity after migration/local services fail or time out | Retained recoverable error; no consent or permissions required. |
| App unlock / biometric authentication | SecureActivityDelegate / UnlockActivity | Retained only when the user has enabled app locking. |
| Mihon onboarding / permission and telemetry steps | OnboardingScreen, GuidesStep, PermissionStep | Already absent from automatic startup before this change. The manual guide currently contains GuidesStep only. No legacy Terms checkbox was found in the active startup path. PermissionStep is not automatically composed. |
| Legacy donation campaign / GitHub update screen | MainActivity private legacy routines | No startup calls remain. Donation state was already false and AppUpdateChecker was already returning NoNewUpdate; neither is described as a confirmed previously visible popup. |
| Account / cloud services | App.initializeLocalServices and existing after-first-frame observers | No automatic account agreement, login, or cloud acceptance modal was identified. Existing local session restoration, user-initiated authentication callbacks, and cloud synchronization are preserved. |

Legal links remain in More: https://mangaro-web.vercel.app/privacy and https://mangaro-web.vercel.app/terms. Both returned HTTP 200 during static document inspection. InMobi's privacy-policy link remains in the existing advertising preferences.

## CMP behavior and published configuration

Identity is unchanged: Choice 2.4.3, p-code `UYWLq5tgD_uwk`, production property `app.manhwaar.reader`; Ads SDK 11.5.0.

A read-only inspection of the exact public property endpoint used by SDK 2.4.3 returned HTTP 200:

https://choice.inmobi.com/configs/users/v2/UYWLq5tgD_uwk/app.manhwaar.reader/config?sdkVersion=p-a-2.4.3

Observed on 2026-10-13:

- `publisherCountryCode`: `MA`
- Request `geoIP.country`: `mar`
- `applicableRegulations`: `["GDPR"]`
- `lang_`: `en`
- `gdprConfig.gdprEncodingMode`: `TCF_AND_GPP`
- `optOutFromAllEnabled`: `false`

This supports the reported Worldwide targeting as the explanation for a GDPR flow outside its usual regional audience. It is a static property response, not device UI verification or a legal determination for every user. The response does not independently establish the account subscription tier or app/account monetization approval.

[InMobi's current Premium documentation](https://support.inmobi.com/monetize/integrating-with-inmobi/inmobi-cmp-premium/) states that Essentials supports only Worldwide targeting; Premium supports region selection. The supported operator action is to obtain Premium eligibility, configure the applicable regions/frameworks in the portal, then publish the intended property. Upgrading alone does not change targeting. The app does not filter consent by locale/IP, spoof geography, edit downloaded configuration, disable automatic CMP presentation, or manufacture consent.

The operator must also review/enable the supported official rejection controls for the app property/theme, including the published false opt-out-all setting. Whether rejection is currently available through a secondary options screen has not been tested. The official UI was not replaced or modified in code; genuine equal-access acceptance/rejection remains a dashboard/manual-validation requirement. Review the supported app language and vendor 333 disclosure as well. No dashboard settings were changed or published here.

[Official Android SDK guidance](https://support.inmobi.com/choice/getting-started-cmp/implementing-cmp-via-code/mobile-app/android-app-implementation-sdk) assigns automatic presentation and stored-decision handling to startChoice, and reserves forceDisplayUI for an explicit privacy-management action. Source review of the cached 2.4.3 SDK confirms that its normal loaded callback is followed by its own automatic presentation decision; no internal/private SDK APIs are invoked by Mangaro.

- Automatic startup/resume uses startChoice once per application-scoped manager; it never force-opens a screen.
- Existing stored decisions remain SDK-owned. Property/region changes or invalidated decisions may legitimately cause SDK reconsent.
- Existing explicit DISABLED/BASIC/PERSONALIZED app restrictions remain stored and respected.
- UNSET is not rewritten to acceptance. Only genuine applicable CMP vendor/purpose/GPP evidence can allow it to request ads. With no demonstrated lawful mode outside the configured frameworks, requests remain blocked.
- The settings entry More → “خصوصية الإعلانات” remains. Users can reject ads immediately or open official options to change/withdraw vendor consent. Showing options does not silently re-enable a DISABLED app preference.
- Already visible CMP UI is guarded against a second app-issued display. Only a real successful official save callback clears a persisted save-failure block; button clicks and SDK loading do not count as consent.
- Unavailable CMP, missing/corrupt data, rejected required vendor/storage signals, and incompatible BASIC signals fail closed for ads. They do not gate reading or downloading. A failed SDK initialization may require a new app process to retry; repeated automatic initialization was not added.

## Advertisement eligibility and operational limitation

Placement IDs remain unchanged:

- Manhwa Native: 10000837492
- Novel Native: 10000837493
- Download Video Interstitial: 10000837491

The existing publisher-control resource, https://mangaro-web.vercel.app/ad-config.json, now also supplies approval attestation. All SDK initialization/load paths require fresh, identity-matching approval in addition to genuine privacy evidence. Native placement composition waits for eligibility, while the reading content stays independent. Download jobs always proceed without a ready, permitted video.

The existing `inmobi` object must preserve its existing `schemaVersion: 1` and exact `accountId`, and additionally provide:

- `packageId: "app.manhwaar.reader"`
- `accountApproved: true` only after actual dashboard approval
- `appApproved: true` only after actual dashboard approval

The unchanged video-only gate additionally requires the exact download placement ID and truthful `videoAdsOnly: true` confirmation. Approval and video-only are separate checks. Publisher attestation is not proof of dashboard state by itself, a signing credential, or a Test Mode override; the publisher must verify the statements before providing them. No approval is inferred from identifiers, CMP acceptance, or SDK availability.

The public config returned HTTP 200 but did not contain these InMobi configuration/approval fields during static inspection. Consequently ads remain blocked until the existing operator configuration truthfully confirms approval. The earlier handoff reports Pending Review, not independently verified approval. This task did not alter website files, serve test ads, or publish configuration. Missing/invalid control data never causes a startup popup. Control reads use the existing bounded IO request/cache; they are initiated only after valid privacy evidence and never awaited by Home.

## Contextual Android permissions

No startup permission request or notification invitation remains.

- Android 13+ notification permission is requested by a user enabling applicable system-alert categories in existing Security → Notifications settings, or selecting the Android notification permission row. In-app reply counts do not request an OS notification grant.
- A separate record tracks an actual system request. Dismissing the old invitation is not treated as proof of a denied permission. Permanent denials use Android notification settings only upon a subsequent explicit user action.
- Android's permission UI remains system-owned. No fake granted state or custom security-dialog replacement exists.
- Existing Android 8/9 image-export storage permission stays operation-scoped; modern MediaStore behavior and user-selected file operations remain intact.
- Extension installation, Shizuku, battery exemptions, biometric locking, and file pickers retain their existing explicit-operation flows. They are not requested on app launch.
- Required foreground services/notifications, channels, optional category preferences, download actions, and persistence are preserved. No manifest permissions were removed in this task.
- Telemetry is disabled in this app build configuration (`TELEMETRY_INCLUDED=false`); its legacy onboarding is not a startup prompt.

## Validation and scope

- `git diff --check`: passed.
- `./gradlew :app:assembleDebug`: passed after restoring an import still used by the package-intent path.
- No unit/UI/instrumentation/performance tests, ADB/device/emulator sessions, application installation/launch, ad requests, Release builds, commits, pushes, deployments, or configuration publication.
- Database schemas, Supabase/account/cloud behavior, readers, Home discovery/cache optimizations, long-press fixes, ad placements/caps, video safety, and existing notification implementation remain intact.

Changed source files: MainActivity.kt; MangaroHomeDrawer.kt; AdPrivacyUi.kt; InMobiPrivacyManager.kt; InMobiAds.kt; InMobiConsent.kt; InMobiVideoControls.kt; NativeBoundaryAd.kt; Permissions.kt; SettingsSecurityScreen.kt; MandatoryUpdateController.kt. Created this audit document.

## Manual checklist

1. Fresh launch: no app Terms acknowledgement, custom ad-choice dialog, notification invitation, intro timer, release tour, or optional update modal. Home should appear after necessary local initialization.
2. Official CMP: with the properly published regional property, check applicable-region acceptance and rejection, stored-decision restart behavior, and legitimate reconsent. Check outside-region behavior separately. Worldwide targeting can still produce worldwide prompts until changed in the portal.
3. Reject/unavailable CMP: browsing, manga/novel readers, library and downloads remain usable; no unauthorized ad requests.
4. More → Advertising Privacy: change official vendor choices, withdraw, and confirm app-level DISABLED survives restart and manual management.
5. Android 13+: no launch permission prompt; explicitly enable an alert category, grant/deny, and inspect permanent-denial settings navigation.
6. Check cached Home cards, reader short taps/zoom/scrolling, inert long holds, downloads and necessary foreground notifications.
7. After actual publisher approval and separate configuration publication, verify the three existing placements and video-only restriction manually. These checks were not performed here.
