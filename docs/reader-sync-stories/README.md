# Reader, novel statistics/sync, Connected Stories repair

Status: **PARTIALLY VERIFIED**. Client fixes and bounded offline UI acceptance are tested. Production Supabase activation/historical counts are not claimed repaired: the current MCP connection denies read-only project inspection. No database/schema/RLS mutation was performed.

## Reader

The existing reader is a continuous `LazyColumn`, not a physical-page pager. Its old counter used `firstVisibleItemIndex` divided by the number of content blocks. A final viewport could contain blocks60–62 and still display60/62. The new projection reports the last visible block, and percentage uses measured position within content.100% requires the final block bottom visible, matching the chapter layout and no remaining forward scroll. Unmeasured/stale layouts cannot complete. No paragraph splitting/extraction/chapter identity changed.

Content bottom padding now reserves the measured toolbar height, retaining the previous112dp minimum. Font-scale/reflow no longer relies solely on the constant reserve. Existing advertisement interaction lines and conditions are unchanged.

Deterministic lengths1,2,10,58,60,62,100,240 preserve content/order and reach the real final segment. Opt-in `readerEnd=true` instrumentation renders the actual production reader with authored offline data at62/font21,100/font32,240/font16, checks final text/100%, reopens62 and checks original chapter identity. These are content segments, not universal screen-page counts.

## Statistics and cloud

An optional nullable `NovelReadingPosition.completed` records measured end: null means unavailable legacy evidence, false means new not-completed observation, true means observed end. Reopening/backtracking/old cloud payloads cannot erase a locally observed completion. Position offsets, anchors and source-qualified identities remain unchanged. Malformed old position snapshots cannot abort a valid subsequent save.

The owner-bound repository aggregates distinct source/work/chapter identities on IO. It never derives counts from chapter numbers, page counts, downloads, launches, XP, guessed cloud absence or another edition. A/B/generation guards apply at collection and rendering. Existing authenticated historical coverage remains unverified: known local positive lower bounds display `+`; empty/missing/legacy-unknown data cannot masquerade as a confirmed global zero. Public profile/privacy behavior is unchanged.

Schema1 cloud payloads intentionally omit the new completion field because the checked-in server validator rejects extra keys. Mknov was missing from the client host allowlist; it is now accepted locally. The checked-in server source constraint also omits Mknov, but its actual live state could not be read. Full metadata activation and completion synchronization require live read-only review and separately approved server/client capability changes. No hidden activation or production write occurred.

The real `syncNow` path no longer stamps all-data success or returns Completed while novel errors remain. A loopback test exercises the actual Supabase SDK: missing capability -> Failed without success timestamp; a later successful capability/pull -> Completed and cleared error. Account ownership, conflict rules, worker scheduling and XP hooks are retained.

## Connected Stories

Two reviewed story relationships remain unchanged. One real available original Solo Leveling pair was checked through actual TeamX and SeaNovel details adapters with two bounded public metadata requests. The production resolver returns the correct target both ways. The offline emulator additionally uses recorded public identities in the real manga/novel details screens, taps both cards and verifies back-stack/library independence. No claim that every source edition or every story has a relationship.

Cold metadata acquisition is described in `docs/connected-stories/README.md`. It never merges formats, replaces chapters or changes the library automatically. Ragnarok/ARISE/Jasmine and unreviewed lookalikes remain excluded.

## Verification

Focused JVM suites, existing source/download/search/profile/account ownership regressions, Kotlin compilation and `git diff --check` pass within the recorded scope. The broader selected run:241 cases,239passed,2explicit live opt-in skips. The separate original Solo Leveling live adapter case passed. Final follow-up17cases passed after decoder/coverage/cancellation hardening. Some suites overlap; do not add run totals.

Opt-in disposable-emulator command uses the existing audit runner:

```
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PnovelReaderScopeAudit=true -PmangaroApkAbi=x86_64 --console=plain
adb -s <verified-disposable-emulator> shell am instrument -w -e readerEnd true app.manhwaar.reader.dev.test/eu.kanade.tachiyomi.novels.NovelReaderScopeInstrumentation
```

The test helper is excluded from Release source sets. No physical phone, real account, production user data, advertisement clicks/impressions, public source push, website deployment or release publication is authorized/performed by this repair.
