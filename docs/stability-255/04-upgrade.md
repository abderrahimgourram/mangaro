# Phase 4 — Historical Release upgrade acceptance

Symptom → owner-reported black screen after updates. Evidence → startup source inspection and four actual signed historical ARM64 APKs upgraded in place on an isolated Android 14/API34 emulator. Root cause → the reported phone failure did not reproduce; no specific new startup defect was established, so no speculative startup/database patch was made. Existing local initialization deadline/recovery UI, first-frame handoff, serialization compatibility and account ownership were preserved. Authentication recovery from phase 3 remains independently verified. Minimal correction → retain these protections and require actual Release upgrade evidence. Regression proof → all four scenarios below reached the working Home and preserved the synthetic data. Remaining limitation → one API level/ARM64 translation is not every physical device, historical database or real OAuth session.

## Tested pre-version candidate

Source `64d20287cb24f74400efc07219e3f5b542b2747d`, optimized signed Release 2.5.3/code46, built using `./gradlew :app:assembleRelease -PmangaroApkAbi=arm64-v8a --console=plain`. Build successful in 10m9s; existing optional Window sidecar/XML service and Compose mapping warnings remain. R8/resource shrinking stayed enabled, non-debuggable, original certificate unchanged. This diagnostic artifact is not the final 2.5.5 delivery.

| Actual historical APK | Baseline | Owner fixture | In-place update | Working Home | Data preserved |
| --- | --- | --- | --- | --- | --- |
| Mangaro-2.5.3-Search-Regression-Fix-ARM64-Manual-Test.apk | 2.5.3/code46 | Synthetic account | code46 candidate | Yes | Yes |
| Mangaro-R8-Mknov-Integration-code45.apk | 2.5.2/code45 | Synthetic account | code46 candidate | Yes | Yes |
| Mangaro-2.5.2-ARM64-Manual-Test.apk | 2.5.2/code27 | Guest | code46 candidate | Yes | Yes |
| Mangaro-R8-Logout-Fix-GitHub-Backup.apk | 2.0.4/code31 | Guest | code46 candidate | Yes | Yes |

No uninstall, data clear or fixture recreation occurred between a baseline installation and its update. Separate scenarios start independently on the disposable emulator. Database schema17 remained readable. Exact manga/chapter/history rows include favorite, chapter page9/20, bookmark and reading history. Novel library/progress/bookmarks/settings and an original synthetic offline prose payload retained hashes. Reader-theme and source-language preferences retained typed values. The two synthetic accounts retained the same local owner and encrypted session file, demonstrating successful SDK storage/AndroidKeyStore restoration without a real production account. This is offline restoration, not online token refresh or Google sign-in acceptance. Download payload preservation was checked; it is not a blanket claim that every historical image/download format was opened.

## Environment and diagnostic integrity

- Emulator serial explicitly selected after `adb devices -l`; qemu verified; API34 x86_64 with built-in ARM64 translation. No physical phone accessed.
- All application launches were offline: Wi-Fi/data disabled, no default route; no live advertisements, production user accounts or Supabase mutations.
- Synthetic encrypted session seeded using a small owned Android instrumentation package signed with the existing certificate. Target Release stayed non-debuggable. No production keys/tokens were extracted or committed.
- An initial SystemUI ANR occurred before the matrix under host compilation load; it is an emulator-framework limitation, not evidence identifying Mangaro's black-screen root cause. Subsequent matrix runs reached Home.
- A preliminary app_process fixture helper aborted; the supported instrumentation approach succeeded. An overly strict fixture comparison initially treated XML whitespace as changed preferences; comparison now checks exact typed preference values while preserving the raw evidence. Neither was an application regression.
- Startup captures after update completed in approximately 12–13 seconds including an intentional eight-second observation and UI dump overhead; these are bounded acceptance captures, not startup performance benchmarks.

The final code47 candidate must still pass an in-place Release upgrade after version/highlight changes. Physical-device acceptance with the owner's affected old installation remains outstanding. Do not claim a universally repaired black-screen root cause from this matrix.
