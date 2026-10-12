# Minified novel acceptance (local emulator only)

`./gradlew :app:assemblePreview -PnovelMinifiedScopeAudit=true --max-workers=2`
compiles the shared runner inside the preview APK. The Android manifest makes it an
instrumentation entry point, so R8 optimizes caller and consumer together. No production
keep rule or release source set changes. Preview retains normal R8/resource shrinking
and uses the debug signing configuration; this is not production-release verification.

Install the x86 preview APK on the dedicated emulator, retaining its data. Place exact
public revision-4 manifest/envelope bytes in its private cache under
`mangaro-novel-staging-38185054168/{manifest.json,envelope.json}` using the emulator's
local test access. Only the disposable wrong-key DER is in audit assets; production
`novels/rules-public.der` and bundled rules must retain their original hashes.

Run `adb -s emulator-5554 shell am instrument -w -e signedStage 38185054168
app.manhwaar.reader.debug/eu.kanade.tachiyomi.novels.NovelReaderScopeInstrumentation`.
The seven checks cover pinned partial merge/Sunovels fallback, tampering/downgrade,
cache interruption/restart/concurrent reads, lifecycle cancellation/retry, genuine
Cenele text/completion and toolbar back/reopen with saved paragraph/offset. Authenticated
completion uses an explicitly isolated account fixture with no server XP writes.
Reuse the installed APK for repeated runtime checks. A passing debug runner cannot
substitute for this minified process. Default Android test builds use the same shared
source in the test APK, without adding audit instrumentation to the application.
