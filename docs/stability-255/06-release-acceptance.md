# Final code47 Release acceptance

Symptom → release must upgrade existing installations without losing data and display current release notes. Evidence → actual signed 2.5.5/code47 ARM64 Release upgraded historical code46,45,27 and31 installations in place; a separate actual public v2.5.2/code27 APK was downloaded and verified against the previous production SHA256 before its upgrade. Root cause → no new startup defect reproduced; the stale code27-only release-note gate was the demonstrated UI defect and was corrected in source commit `413f45dc8bf1b860d8bb9a20e0c1168094af0e2e`. Minimal correction → current-version release notes plus independently tested account/sync fixes; no speculative startup/database/advertising changes. Regression proof → all five historical baseline upgrades reached Home, displayed code47 notes once, and retained exact synthetic database rows, novel files/offline payload and typed preferences. The two account cases retained encrypted session ownership; restart after acknowledgement did not replay notes. Universal then upgraded the ARM64 installation, reached Home and retained the same fixture state. Remaining limitations → one API34 x86_64 emulator with ARM64 translation, synthetic nonpersonal records, no real OAuth/provider/cloud/device acceptance. The owner's exact phone black-screen root cause remains unestablished.

The public v2.5.2 baseline is the genuine existing Release asset `Mangaro-ARM64.apk`, SHA256 `884f89b761cbb0417f448747485a092c8d6e2c9b4c732deae1b2f0bd032e00ae`, not a reconstructed historical build. All baseline-to-target updates used install-r without uninstall/clear/reset between the baseline and update. Independent emulator scenarios begin separately.

A preliminary final assertion was an automation defect: Python XML leaf Element truthiness skipped tapping the release-note button even though the expected fullscreen notes were visible. The helper now tests `is not None`. The same installed update/data was resumed, the actual button pressed, Home verified and persisted-state equality retained; assertions were not weakened and no APK/source rebuild was required. Raw failed and subsequent successful evidence remain local.

## Build and artifact verification

Command: `./gradlew :app:assembleRelease --console=plain`; existing ABI/signer/R8 configuration, source commit `413f45dc8bf1b860d8bb9a20e0c1168094af0e2e`. Five outputs verified: package `app.manhwaar.reader`, versionName2.5.5, code47, non-debuggable, R8/resource shrinking enabled, valid APK ZIP/CRC, expected native ABIs and original certificate SHA256 `3e52c0d0f434c8b6e2c0edee9eff0864aa4a0368bac4e007fc0d70900aa302ba`. Existing optional-library/Compose mapping build warnings were retained; no security/shrinking flags weakened. ARM32/x86 did not receive runtime acceptance on an unsupported emulator ABI.

| File | Bytes | SHA256 |
| --- | ---: | --- |
| Mangaro-ARM64.apk | 32811787 | `593fafd190b29658c7a3e1435d2a8c6f5228018c84f278c7582b6d65e9080eee` |
| Mangaro-ARM32.apk | 29697805 | `7cfda37b0f9c8ccefed4d1728c0ccab178e06e0924945eba9d0843c34761813a` |
| Mangaro-x86.apk | 34150069 | `100f966567796e77c762c8ac19504d2803966927126c2e31016ad022dba96141` |
| Mangaro-x86_64.apk | 35206606 | `3e5c10010290631e9de2917aa36ca829426fa32020ce1e742c6423c66ac1c5f8` |
| Mangaro-Universal.apk | 72809490 | `e025c48d915f38c030991b86ff74c3b3607ae2579b5f71204abc2ee248404ce8` |

## Tests and preservation

Latest selected deterministic host outcomes: 256 distinct passed,3 optional skipped,0 failed. Individual accepted runs: search142/142; source67/70 with3 optional skips; cloud/account40/40; domain9/9; live public Cenele2/2; release notes/startup11/11. Some cases overlap across commands; the distinct count avoids double-counting. Earlier deliberate failing-before regressions and fixture/tool failures are separately documented; the whole project suite is not claimed green. Dependency Review remains entitlement-blocked, not disabled.

No advertising source, flags, counters, buttons, consent or navigation changed. No reader/download/source identities, models, library schema, production Supabase, novel-rule signing/publishing, website advertising behavior or credentials changed. The original immutable recovery remains `0c9f95b565b0ae5aca2343430128eaaf6f5eb025`. Public Git source/main are not pushed or merged.

## Authorized publication preparation

Owner subsequently authorized the five public APK assets, version2.5.5 website and mandatory-update policy. A production Vercel build was staged without assigning domains. Its release metadata, unchanged advertising configuration/static files and homepage match the reviewed local staging output. The initial homepage check mistakenly compared the intentional /index.html redirect body to HTML; checking the canonical / route proves the expected content. No protection settings were disabled.

Local website had unrelated pending changes including an InMobi setting differing from production. Publication staging retains the actual live configuration, and rendered SmartLink matches existing production. The owner's pending website changes remain intact. Only release.json/app-version.json are intentionally updated in that worktree. Stable website domains and forced-update policy remain old until all public APK assets are uploaded, server digests verified, Release published and actual links checked.

Final phase disposition: ready for owner manual acceptance within the tested scope. No claim of universal duplicate resolution, every-source chapter availability, real cloud two-device acceptance, or a proven universal phone black-screen fix. Public publication remains pending at this commit.
