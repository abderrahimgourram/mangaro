# Smart Focus V1 — private acceptance evidence, 2026-10-15

Status: local Smart Focus implementation, host gates and optimized emulator acceptance passed. The interrupted Release build was recovered successfully, without another build in the resumed session. Physical-phone acceptance remains for the owner.

## Implementation

The existing repositories, source adapters, validators, signed-rule verification, WorkManager/download queues, SQLite queue, atomic chapter files, ChapterCache, Coil and readers remain the data owners. No schema, download format, source identity, source publication or production service is changed.

`FocusCoordinator` schedules source operations using coroutine context tags and resumed screen leases. `FocusSurface`, ReaderActivity/ViewModel and the existing process lifecycle update novel/manga detail/reader/background focus. Older screen disposal cannot clear a newer lease. Focus generations are monotonic within a process; the existing requested-chapter/epoch guards still own UI result correctness.

Admission happens before NovelHttp's existing four global/two host permits, manga metadata/page-list calls, image response consumption and persistent manga download requests. Pending bulk operations remain reorderable at the coordinator. Idle admission permits four operations overall/two per host; interactive demand reduces background admission to two overall/one per host and allows up to two interactive operations. Lower client/source limits remain authoritative. Sunovels web/mobile aliases share their existing host budget. Noninteractive requests age every 750 ms, up to active-work priority, without polling. Already running responses are not preempted.

Running requests lose their old interactive reservation when focus changes, while their durable owner continues. This fixes rapid A → B → C navigation being blocked by A/B's stale reservations. A 20-request transition fixture reproduced the original FIFO problem: the reader reached HTTP after all 20 bulk requests. The fixed coordinator admits it near the start while all 20 downloads still complete.

Novel metadata/index checkpoints, chapter coalescing, validated memory LRU and download queue are reused. A checksum-valid complete offline chapter precedes memory/network; incomplete illustration inventories use the existing queue recovery path. Sunovels' existing shared flight now carries its scheduling context. Ownership/content/access validation is unchanged.

Manga page lists and the existing single-worker priority image queue receive the same scheduling policy. Visible/retry entries supersede duplicate queued entries; adjacent preloading remains bounded to the existing four-page window. A queued disposable prefetch can yield to visible demand without terminating its worker. Running responses and user downloads cannot be discarded by this API. Local and downloaded pages bypass network admission.

The reader previously cancelled its opened chapter's queued user download and requeued it only on exit. That cancellation is removed. A cache-copy operation also deleted the reader's source image after copying it into download storage; it now retains the cache file for the existing bounded LRU to evict.

## Catalog and covers

Existing source URL identity deduplication already covers popular/latest/search pagination and refresh. A regression verifies identical cards disappear while One Piece and Strong World 0 remain distinct even with matching titles. Existing home grouping/zero-chapter/error reconciliation remains intact; no rows or library entries are deleted and no live per-card health checks are introduced.

Cover preview requests are created once per work/cover version rather than on padding recomposition, and disposed with the view. SSIV receives an independent software bitmap. Custom replacement validates a staged image before atomic commit, retaining a valid old image on failure. Gallery saving uses the existing MediaStore saver with a safe filename and actual extension/MIME; Share and Restore Original retain their existing paths.

## Validation boundaries

Host tests use deterministic HTTP delays/failure injection, accepted source fixtures, real SQL/file operations where supported, and explicit mocks where Android is required. A synthetic private manga source supplies a previously recorded valid JPEG to the real Android reader and download engine. It is not proof of every live manga source's parser.

Optimized acceptance uses the existing API 34 x86_64 Pixel 6 emulator with namespaced novel data, private storage/preferences and uniquely owned manga rows. Fixture tests require a Guest session. No physical phone, existing app-data reset, infrastructure deployment, Git push or source-rule publication occurs. Standalone source controls used 19 bounded public web requests; Android acceptance replays responses instead of contacting sources.

No full UI latency improvement, memory saving, FPS improvement, universal source reliability or physical-phone success is claimed from transport timing or successful compilation. Opaque source operations and already running HTTP requests retain their existing timeouts/rate limits. The coordinator covers the integrated source entry points; it does not replace the app's HTTP clients or schedule authentication, consent, progress persistence or ads.

## Final results

### Recovered build and artifact

`durable-acceptance-build.log` ends with `BUILD SUCCESSFUL in 7m 54s`. The final ARM64 and installed x86_64 Release APKs contain byte-identical DEX files. All recorded build-input hashes match the finished build. The only changes since the previous successful Android run were two private acceptance-harness corrections described below; production code is unchanged.

Final private APK: `/home/jalem/Downloads/Mangaro-Releases/Mangaro-R8-Smart-Focus.apk`, 35,564,710 bytes, `app.manhwaar.reader`, version 2.0.4 / code 25. SHA-256: `22d7d0f650da80e0ed416fd18082f4c8f3946d1c304bb98f0b6aaeca2b50536c`.

This is the actual Release variant with R8 obfuscation and resource shrinking. `FocusCoordinator` is obfuscated in the mapping; effective configuration contains no `-dontobfuscate`, `-dontshrink` or `-dontoptimize`. Manifest/package inspection confirms `debuggable=false`. APK ZIP integrity and apksigner v2 verification pass. Certificate SHA-256 `3e52c0d0f434c8b6e2c0edee9eff0864aa4a0368bac4e007fc0d70900aa302ba` matches the preserved code-24 Release. Code 25 is upgrade-compatible with code 24. The explicit private acceptance/source authorization flags match the existing authorized acceptance setup; published production rules and signing policy remain unchanged.

The earlier code-24 APK remains intact with SHA-256 `04b22830bd50ca918fc21e2a3106df28fe3d9d5a69bf8525d8220c93d8cc0e34`. A fresh comparison of 124 protected workflow/publisher/ads/account files against pre-task bytes found no changes. No app-data clearing, uninstall, physical-device access, push or deployment was performed.

### Tests and offline restart

| Gate | Result | Evidence type |
| --- | --- | --- |
| JUnit, 26 suites | 164 passed, 4 optional live skipped, 0 failed/errors | Deterministic fixtures, controlled HTTP, local persistence/mocks as applicable |
| Sunovels contracts | 155 direct + 43 mobile passed | Accepted recorded responses and failure injection; includes existing public/locked/ambiguous safeguards |
| Optimized Android coordination | 26 checks passed on the preceding candidate with identical production inputs | API 34 x86_64 Pixel 6 emulator, real app readers/queues with private source fixtures |
| Final APK offline/process restart | 31 checks passed | Same emulator, network disabled, force-stop/recreated process, existing acceptance data preserved |
| Final APK integrity/signature/input hashes/diff whitespace | Passed | Local artifact and source verification |

The 26-check run demonstrated actual Arabic SeaNovel rendering and decoded manga pages, Next/Previous navigation, progress/bookmarks, 20 completed novel downloads, cancellation and retry of three manga chapters, preserved reader image cache, real cover preview, MediaStore saving and invalid-cover retention. The recorded novel queue continued advancing while the manga reader was open.

The corrected final restart test reused that completed batch, not a fresh download. All 20 restored Kolnovel tasks were DONE and their actual disk files passed the existing checksum/completeness checks. A downloaded Kolnovel chapter rendered Arabic text in the existing reader. Downloaded manga rendered using the local page loader. Novel-source requests, manga HTTP requests and manga page-list requests were all zero. SeaNovel's saved Resume identity, bookmark and paragraph 1 / offset 11 survived; its online-only chapter correctly remained NOT downloaded. Manga bookmark and saved page survived too. No cache reset or production-code change was needed for this test.

### Crash investigation

The real Mangaro `am_crash` at Android log time `10-15 14:48:34.748`, PID 4587, was `IllegalStateException: OFFLINE_UNEXPECTED_REQUEST` on `OkHttp Dispatcher`. Its origin was the private test interceptor. The old test incorrectly opened the online-only SeaNovel chapter after restart, causing a legitimate cache-miss request; the interceptor's unchecked assertion escaped OkHttp's IOException handling and killed the app process. This was an actual process crash caused by test instrumentation, not evidence of a production chapter-parser failure.

The private harness now opens a checksum-valid downloaded Kolnovel chapter and separately asserts SeaNovel progress persistence without expecting its undownloaded body offline. Unexpected offline requests throw IOException so they produce a controlled failed test, while request counters still expose any attempted source access. The final 31-check run passed with zero requests and empty new crash/ANR event buffers. Production reader, cache and source behavior were not weakened.

Earlier `com.android.systemui` Keyguard/SystemUI ANRs were a separate emulator problem during loaded cold starts; captured screenshots show Mangaro story text behind that system dialog. The final emulator boot was performed after Gradle stopped and did not reproduce the ANR. This is distinct from the explained Mangaro test-interceptor crash.

### Measurements and limits

Controlled optimized-Android transport contention, three paired trials with the same two 400-ms bulk requests, gave median visible-request admission/response latency **355.55 ms with coordination disabled versus 3.01 ms enabled**. Both bulk requests completed in each trial, with the same three requests per trial. This isolates scheduler contention; it does not establish an internet or full-screen speedup. A separate 20-request transition regression moved the reader from request 21 to request 4 while all downloads completed.

After-only Android observations were 4.475 s to first decoded manga page and 2.403 s to visible SeaNovel text after content validation, with manga downloads active. The restarted local manga page rendered in 1.911 s. End-of-run Java heap samples were 13.7 MB and 14.6 MB; these are not peak/PSS measurements. There is no paired UI/FPS or memory-improvement claim.

The coordination run used 22 recorded novel-source responses, 24 synthetic manga image responses and 18 synthetic benchmark responses; no live source HTTP. The final offline run used zero. The earlier standalone public-source controls used 19 bounded web requests; none were repeated in this resumed session. Broad source behavior is covered by existing fixtures and signed PARTIAL revision-4 verification, not a claim that every live work was individually tested. Already-running source requests remain non-preemptive. Real-phone behavior, live-source variation and system background restrictions still require owner acceptance.

Full task-only changed-file inventory, diff, R8 mapping, artifact metadata and selected test/crash logs are retained in `/home/jalem/Downloads/Mangaro-Releases/Mangaro-R8-Smart-Focus-evidence/`. This inventory is relative to the pre-task dirty worktree, not a misleading diff against HEAD.

## Manual phone acceptance

1. ثبّت التحديث فوق نسخة Release الحالية دون حذف التطبيق أو بياناته.
2. افتح القس المجنون ثم فصلًا أثناء استمرار الفهرسة؛ تحقق من الهوية والتالي/السابق والموضع المحفوظ.
3. ابدأ تنزيل 20 فصل رواية، واقرأ مانهوا. ثم ابدأ تنزيل مانجا واقرأ رواية؛ يجب استمرار تقدم التنزيلين دون خلط المحتوى.
4. انتقل سريعًا بين روايتين ومانهوا وفصول مختلفة؛ لا يظهر محتوى الفصل السابق مكان الحالي.
5. ألغِ دفعة غير مكتملة وأعد تشغيلها؛ تبقى الملفات المكتملة ولا تتكرر الفصول.
6. فعّل وضع الطيران وأغلق التطبيق كليًا ثم افتحه؛ تحقق من الروايات والصور المحفوظة والموضع والعلامات.
7. جرّب معاينة الغلاف وحفظه في المعرض ومشاركته واستعادة الغلاف الأصلي، ثم Sunovels وSeaNovel وBahr وFada وKolnovel ووظائف حسابك المعتادة. أرسل اسم المصدر والفصل ونص الخطأ إن فشل اختبار.
