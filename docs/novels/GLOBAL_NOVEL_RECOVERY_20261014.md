# Mangaro novel regression recovery — 2026-10-14

Current HEAD: a3cddc54fe2d69e6d37ac91d2ba88c4134d45902, feature/adsterra-production. Existing dirty worktree preserved. Private version 24; ordinary production build defaults and published source activation remain unchanged.

## Confirmed regression and historical comparison

The preserved original ARM64 2.0.3 APK is release_staging/Mangaro-ARM64.apk (versionCode21, SHA256 ded4768cc66e53ff4169cf697f94ac184e976168f7cb456d3838ee9677d29082). Git2f401d710 is an available 2.0.3 source baseline; the original binary's exact commit cannot be recovered. No downgrade or installation of that APK over existing emulator/user data was attempted. Exact old adapter bodies are replayed against identical recorded responses using the current bounded transport, not presented as old-binary runtime testing.

SeaNovel is the Arabic Bahr Novels source (`novel.seanovel`); it is not a separate fifth source. Actual HTTP200 `https://seanovel.org/api/novel/shadow-slave` returns 3190 source rows, starting at0 and including1841.1. Current pre-fix SeaNovelSource used previous=0, positive Int IDs and a positive-integer-only reader ownership path. The first real row0 therefore threw Duplicate or unordered Sea chapter ID before the indexer received any rows. Even changing that comparison alone would leave1841.1 rejected. The saved pre-fix JUnit XML proves the failure; independent Devil's Sword2335 and Shadow Hack990 responses pass. The2.0.3 adapter used JsonPrimitive.content and retained both identities. The repair preserves exact public ID strings, uses BigDecimal solely for monotonic validation, rejects duplicates/negative/exponent/noncanonical IDs, and updates the source-only parser cache key to v3. Reader canonical, lock, document completeness and meaningful-content checks remain intact. No SQL migration or user data deletion.

Shared differences: old2.0.3 detail UI called completeIndex directly; the bounded collector change cce4536d7 (October11, still carrying2.0.3 version metadata) introduced20s deadlines around multiple individually bounded requests. The current foreground helper also returned after8x8pages or120s, leaving normal continuation behind a manual button. This is a verified interaction, not proof that every phone's400/600 display came from the same cause. Non-Sunovels discovery now continues in8-page batches with250ms pauses, under existing source HTTP25s, host concurrency2,2000-page/100000-row/cursor ceilings and lifecycle cancellation. Sunovels keeps its separately accepted two-page policy. Failed HTTP retains its existing single bounded retry/backoff, not unbounded retries.

A second shared cache defect withheld newly verified partial refresh rows for non-Sunovels sources: checkpoints were written but only the old rows were projected until complete. Verified incoming rows are now merged into the visible snapshot while the independent checkpoint remains the collector input. Interrupted/parser-invalid projections with nextPage=null or a cursor already in fetchedPages are restarted from the real source while retaining all last-good rows. Bad/repeated next cursors are rejected before checkpoint persistence. Optional failureState/failureCode metadata distinguishes unavailable indexes from VERIFIED_EMPTY and cannot renew a successful-cache TTL. Loading remains existing reactive busy state; no second database or download engine.

## Source observations

- Bahr/Sea: Shadow Slave3190 (0 and1841.1), Birth of Devil's Sword2335, Shadow Hack990, all full public API indexes. No chapter pagination exists in these observed API contracts; no fake continuation endpoint was added. Real reader HTML for0,1841.1 and Shadow Hack1 recorded and parsed.
- Kolnovel: real full Reverend Insanity page yields2372 actual links (including appendices; latest main chapter2334). Advertised2343 does not justify invented rows. Source still serves one full HTML index; the adapter never had a400/600 parser cap. Recorded bodies at199–201,399–401,599–601,2000 corroborate later accessibility; prior1–3 remain covered. Second work: The Empty Box and Zeroth Maria. Exact old/current adapters produce identical URL identities. A restored legacy400-row cache is tested separately from clean source traversal.
- Fada/Cenele: fresh legitimate Renegade Immortal index comprises13 volumes,27 chapter pages,2092 unique links;29 public requests include HTML+volume metadata. No chapter bodies were fetched to enumerate it. Actual responses replay through the real parser/indexer after serialized140-row checkpoint restoration. Existing blank-image-candidate, interrupted-document, queue retry/atomic-storage corrections remain unchanged.
- Sunovels:155 direct contracts,43 mobile contracts; actual previously accepted80paragraph/9921character mobile fixture. No repeated reverse engineering. Reader/download authorization and explicit lock/ambiguity remain enforced.

## Cache and user data safety

Library/progress/bookmarks/chapter IDs/download formats/signature verification unchanged. No reset, clean, uninstall, pm clear, physical-device access, database migration, push, workflow edits, deployment or D1 operation. Every emulator check uses private namespaced stores; original emulator stores remain intact. Prior code23 APKs and mapping archived before build. Original private phone backup preserved.

## Signed rules and scope

Two fresh GitHub read-only requests returned HTTP200; manifest schema2/engine3/PARTIAL revision4 and rules4 bytes exactly match the previously pinned-key-verified files. Existing rule store verifies that same signature inside the optimized application. Source IDs/selectors/public key/publisher/workflows unchanged. No source-specific server configuration changes.

## Manga scope

Existing manga failure/empty reconciliation and conservative discovery grouping are retained and tested. New/zero-chapter records remain stored; discovery preference does not rewrite libraries. Distinct One Piece and Strong World0 receive an explicit grouping regression. Cover-save code previously displayed success even when metadata/bitmap conversion returned null; it now reports an error. Existing Android MediaStore saver is reused and tested on API34, with no storage permission workaround or new storage format. Existing recommendation/discovery policies are not redesigned.

## Limitations

These are representative source tests, not proof that every work or current remote chapter is reachable. Public request403 without the application's normal User-Agent was observed in early Python diagnostics; subsequent exact app-header requests succeeded without cookies/challenge bypass. Fixtures/mock failures are explicitly distinguished from live responses. The original phone's exact400/600 cache and selected edition were not available, so that individual mechanism cannot be conclusively attributed. Final physical-phone network acceptance remains required. Do not infer source completeness from advertised totals.

## Failure discovered during optimized runtime validation

The first optimized rehearsal deliberately failed a completed Sea refresh with503. It exposed a bug in the new shared projection: an unverified empty first-page attempt replaced nextPage with1 while retaining fetchedPages={1}. Retry then threw Novel pagination cycle. That candidate was not delivered. The fix retains the prior snapshot verbatim until a verified refresh page exists, and restarts malformed persisted continuation checkpoints while keeping good rows. A permanent regression covers this exact cursor/completion combination. The final optimized replay is run against the same failed namespaced store without clearing it. This finding is distinguished from the original phone regression.

## Automated validation (before final runtime)

-138 selected JUnit tests:134 passed,4 opt-in live tests skipped,0 failures. Includes exact2.0.3 adapter equality,3 real Sea indexes,2 real Kol indexes,14 recorded real chapter bodies, full29-response Cenele index,70-page mock continuation beyond the old64-page budget, checkpoint/cycle/identity/failure/empty policies, existing novel downloads, source rules, manga reconciliation/discovery/cover recovery and reader identity.
-155 Sunovels direct contracts plus43 mobile plus51 Sea reader/transport contracts,249 checks total; fixtures and explicit failure injection, not fresh public HTTP.
-git diff --check passed. Existing downloader, disk formats, HTTP client, NovelContent, SunovelsSource and SunovelsMobileApi byte-identical to task baseline.
-Live bounded diagnostic budget:54 source HTTP requests (including3 early403 diagnostics),2 read-only GitHub requests;14 real chapter HTML responses all200,27 Cenele chapter-index pages (metadata only),3 complete Sea API indexes and2 Kol index pages. No full-catalog or chapter-body crawl. Android fixture request counts are reported separately.
-Original preserved phone backup SHA256 unchanged:eed3cc8f15912477878f3198da0cb34cf96b3fd173be4c3dd65325a36225667c.

## Phone acceptance (Arabic)

1. ثبّت APK كترقية فوق الإصدار الحالي؛ لا تحذف التطبيق ولا تمسح بياناته. يجب أن تقبل الحزمة التوقيع المعتاد والإصدار24.
2. اختر طبعة «بحر الروايات» صراحةً لـ«عبد الظل». انتظر الفهرسة؛ النجاح3190 رابطًا فعليًا مع بقاء أي كاش صالح مرئيًا. افتح الفصل0 والفصل1841.1، وتحقق من هوية الرواية والتقدم.
3. اختر طبعة «ملوك الروايات» للقس المجنون. يجب أن تتقدم القائمة تلقائيًا بلا ضغط متكرر على «تحميل المزيد». تحقق من199–201 و399–401 و599–601 وفصل لاحق، ثم التالي/السابق. لا تستخدم2343 المعلن وحده لإثبات الاكتمال؛ الاستجابة المختبرة تحتوي2372 رابطًا مع الملحقات.
4. أعد فتح رواية أخرى من بحر وملوك، ثم Sunovels Apocalypse City1 وفضاء الخالد المرتد1 و2 و13. النجاح نص حقيقي للرواية والفصل الصحيحين بلا خطأ قفل زائف.
5. نزّل فصولًا محدودة، ألغِ دفعة وأعدها. فعّل الطيران، أوقف التطبيق ثم افتحه. النجاح حفظ النصوص والصور المكتملة والإشارات وموضع القراءة، بلا طلب شبكة أو تكرار الملفات.
6. اقطع الشبكة أثناء الفهرسة. يجب بقاء الفصول المحمّلة سابقًا وإظهار فشل مؤقت، ثم استكمال حقيقي بعد عودة الشبكة، لا قائمة فارغة مؤكدة.
7. تحقق من أعمال المانجا الجديدة ذات0 فصل، وتمييزOne Piece عنStrong World0، وحفظ الغلاف في الصور، مع تسجيل الدخول والمكتبة والتنزيلات المعتادة.
8. عند الفشل أرسل sourceId وeditionId والرابط ورقم الفصل وصورة الخطأ ووقت حدوثه وهل الشبكة متصلة؛ لا ترسل كلمات مرور أو كوكيز. اختبار الهاتف PENDING حتى قبول المستخدم.

## Final optimized Android results

Existing Pixel6 AVD, API34 x86_64, KVM, emulator-5556. Approved-key update from23 to24 succeeded; final corrected24 replaced the local candidate without uninstall or data clearing. All3 optimized DEX files are byte-identical to the ARM64 output. Package flags have no DEBUGGABLE flag. Release source implementation classes are obfuscated; minifyReleaseWithR8 and resource optimization completed in4m50s. Existing optional XMLutil/Window-sidecar mapping warnings remain, with no corresponding runtime exception in these checks.

Six actual Android instrumentation phases:108 checks passed,84 controlled fixture HTTP operations,0 live-source HTTP operations. All source text is recorded real public HTML/JSON;503/truncation/cancellation/slow-page failures are explicit test injections.29 index checks recover the SAME failed cache, load Sea3190/2335/990, repair legacy Kol400→2372, validate a second Kol33-link work, navigate199–201/399–401/599–601/2000, finish Cenele2092 via27 legitimate pages despite>20s injected delay, persist Sea0/1841.1 and render real Arabic text.11 index restart checks restore five complete indexes, bookmarks/progress and saved text with radios disabled and0 requests.34 existing download checks plus11 restart checks preserve all8 completed Kol/Fada/Sea files, image selection, cancellation/retry, source identity and Arabic rendering. Sunovels15+8 checks preserve natural first tap, mobile80paragraph text, stale-lock promotion, authorized3-file persistence, interrupted-download rejection and offline restart. Source-rule signatures verify in every phase. API34 MediaStore image saving returns a readable1080x2321 bitmap.

Direct, exhaustive novel-detail chapter-list UI automation was not added; repository reactive page emissions and actual reader rendering/navigation were tested. The phone's exact old400/600 snapshot/selected edition remains unavailable. The original2.0.3 binary was inspected, not executed against the live sites with clean/restored data. These limits prevent claiming exhaustive phone or all-novel success. Supabase login, ads/community flows and every third-party manga extension require ordinary owner acceptance; their production source/configuration is unchanged.

| Source | Real representative indexes | Optimized reader/download/restart evidence |
|---|---|---|
| Bahr / SeaNovel | Shadow Slave3190; Devil's Sword2335; Shadow Hack990 | Arabic0/1841.1 and Devil's Sword1/13; existing queue and offline restart PASS; Shadow Hack1 parsed from actual fixture on host |
| Kolnovel | Reverend2372; Zeroth Maria33 | Boundary chapter bodies and Next/Previous PASS; saved legacy400 cache repaired; persistent files/progress restored; Maria body host fixture PASS |
| Fada / Cenele | Renegade Immortal2092 over27 source pages | Full reactive traversal PASS; chapters1/2/13 queue/images/retry/offline PASS; additional1–3/13/50/64 host fixtures retained |
| Sunovels | Existing verified partial index capability preserved | Complete accepted mobile text, first tap, authorized downloads and offline restart PASS; no new live Sunovels requests |

Private signed APK: `/home/jalem/Downloads/Mangaro-Releases/Mangaro-R8-Novel-Recovery.apk`
Size: 35548326 bytes (33.90 MiB).
SHA256: `04b22830bd50ca918fc21e2a3106df28fe3d9d5a69bf8525d8220c93d8cc0e34`
Package `app.manhwaar.reader`, version2.0.4/code24, debuggable=false. Existing approved Release certificate matches preserved2.0.3/code21 and previous2.0.4/code23. This is the explicit private Release acceptance configuration, including the existing opt-in self-test harness; it is not a published production rollout. Existing production defaults remain code22. No signing credentials are exposed.

## Exact files changed in this task

- `app/build.gradle.kts`
- `app/src/main/java/eu/kanade/presentation/novels/NovelScreens.kt`
- `app/src/main/java/eu/kanade/tachiyomi/novels/NovelChapterIndexer.kt`
- `app/src/main/java/eu/kanade/tachiyomi/novels/NovelIndexRecovery.kt`
- `app/src/main/java/eu/kanade/tachiyomi/novels/NovelModels.kt`
- `app/src/main/java/eu/kanade/tachiyomi/novels/NovelRepository.kt`
- `app/src/main/java/eu/kanade/tachiyomi/novels/SeaNovelSource.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaCoverViewModel.kt`
- `app/src/sunovelsAcceptance/java/eu/kanade/tachiyomi/novels/NovelRecoveryInstrumentation.kt`
- `app/src/test/java/eu/kanade/tachiyomi/novels/Legacy203SourceFixtures.kt`
- `app/src/test/java/eu/kanade/tachiyomi/novels/NovelIndexPerformanceTest.kt`
- `app/src/test/java/eu/kanade/tachiyomi/novels/NovelIndexRecoveryTest.kt`
- `app/src/test/java/eu/kanade/tachiyomi/ui/home/GroupDiscoveryItemsTest.kt`
- `docs/novels/GLOBAL_NOVEL_RECOVERY_20261014.md`

All compressed public response fixtures and provenance manifests are inventoried in Mangaro-R8-Novel-Recovery.files.json. Mangaro-R8-Novel-Recovery.changes.diff is relative to the preserved task-start worktree, excluding previous unrelated changes. The original worktree changes are not reverted.
