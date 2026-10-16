# SmartLink gate and reader-transition repair — 2026-10-16

Private code34 follow-up to code33. No ADB, emulator, installation, live ad traffic, backend writes or source-rule publication. Adsterra SmartLink31593088 and support case481902 remain a publisher-reported account-specific exception, not independently verified or general network permission. A browser return is a local navigation event only, never evidence of an impression/view/conversion.

## Confirmed code mechanisms

Download gate: the secondary unavailable action explicitly called `fallback`, while an ad-state IO catch directly invoked the enqueue continuation. Privacy-disabled fallback also created grants. Those paths could start an incompletely unlocked new batch. Both unwanted visible secondary actions and all incomplete-batch enqueue fallback paths are removed. New storage/browser/network/privacy/device-limit failures show retry/error and leave the batch unstarted. Android sheet dismissal/Back cancels only the pending ad navigation; it never cancels existing downloads or removes files. Genuine earned grants and exact-batch partial counters remain durable. Incomplete legacy fallback grants are no longer accepted as earned steps; genuine completed unlocks remain valid. No downloaded file is inspected, rewritten or deleted by this reconciliation.

Reader: the old SmartLink boundary was an optional footer, not a navigation gate. Novel Next and manga active-chapter switching called `dismissBoundary`; the next non-twentieth completion replaced the due boundary with null. Novel resume treated *any* saved position as previously completed, suppressing legitimate resumed completions; its completion effect also checked loading without observing loading changes. These are source-backed failure mechanisms, not claims of a captured phone crash/trace.

## Implementation

- Keep the 1–6 count calculation, RTL dark/gold download UI, progress/bar and explicit external-ad disclosure. A completed live batch invokes its existing OnceAction/queue callback automatically once. No normal unavailable/cancel secondary button remains. Closing does not start anything.
- Keep the existing AtomicFile journal/schema1, adding nullable serviced-count and local reader-transition fields. No Room/SQLDelight or download-format migration. Distinct completions are owner/category/chapter scoped; novel and manga/manhwa counters are independent across works/restarts. Repeated resumes/layouts/old completed chapters do not manufacture completions.
- Persist a due interval until its explicit accepted → away → return flow completes. Pending destination, work and owner are saved before presenting the boundary; novels retain the already resolved chapter row, manga retains the actual local chapter ID and selected image index. No guessed identities. Automatic backward/forward transitions preserve the actual selected page after authorization and verified page-list loading, without writing progress for the unaccepted destination. Earned service remains valid if target loading fails; applying the correct destination clears only its own intent. A deliberate different selection supersedes an old intent safely.
- Novel Next and chapter selector share one transition function. Direct entry into a new chapter also checks an already-due interval before content loading; reopening a known completed chapter does not manufacture another opportunity. Manga toolbar/selector/automatic paged and webtoon transitions share the existing chapter loader/selection admission. Join the already queued verified completion before checking the interval. Delayed readiness of obsolete/unselected manga images cannot create SmartLink completions; the legacy SDK completion observer is preserved independently.
- The reader boundary is a normal visible Arabic dialog before the target is accepted. It never opens a browser from Next, scroll or Back. Only its labeled button starts the external link. System Back cancels the attempted transition and keeps reading progress. The dialog reattaches after Activity recreation; restart restores the persisted matching work/destination. Pending browser proof is retained when recreation starts from an older Activity intent for the same work/destination. Declining a new-entry boundary returns to the existing previous screen rather than leaving a blank reader. Existing privacy-disabled/incognito reading behavior remains exempt and is never reported as an earned advertising step. Privacy opt-out cannot unlock a new download batch.
- Keep short launch debounce, device-wide hourly protection and source/reader validation. No four-hour cooldown. Batches remain distinct and account-scoped. No queue, local personal-data schema, source parser, Home/catalog, Smart Focus, cloud or logout implementation changed.

## Tests and limits

81 focused JUnit cases passed, 0 failures/errors/skips. Nineteen new recovery cases use the real journal and atomic local fixture files plus injected browser lifecycle events: incomplete100-chapter batch, persistent1/4, concurrent4/4 one-shot enqueue, legacy fallback repair, genuine grants/files, novel19/20, webtoon19/20 repeated panels, selector/reopen dedupe, in-progress legacy repair, persistent due intent, earned step/restart, second40th threshold, accounts/guest/late returns, browser failure and superseded destination. Source wiring guards cover absent bypass labels/callbacks, Back/dismiss handling and common novel/manga transition entry points. Existing17 SmartLink tests now enforce the corrected no-fallback/no-skip requirements. Logout, download ownership/removal, Smart Focus/fairness, cloud reconciliation and chapter-selection suites also passed.

Host tests do not prove actual Android dialog rendering, browser/rotation lifecycle or webtoon scrolling. Manual acceptance is required. After a complete process restart, a download enqueue callback is intentionally not serialized: reselect the same batch/subset to reattach the existing queue action; earned steps are reused. Reader destinations *are* persisted/restored. A failed/corrupt ad journal blocks new unlock authorization with a recoverable error; existing downloads remain usable. No automatic ad requests or impressions were generated by tests.

## Changed files

- `app/build.gradle.kts` — private acceptance code34 only.
- `app/src/main/java/eu/kanade/tachiyomi/data/ads/{DownloadAdGate,SmartLinkPolicy,SmartLinkController}.kt` — strict unlock, durable interval/intent and explicit reader dialog.
- `app/src/main/java/eu/kanade/presentation/novels/NovelReaderScreen.kt` — completion/restore detection, shared Next/selector gate.
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/{ReaderViewModel,ReaderActivity}.kt` — ready completion and central transition/restore wiring.
- `app/src/test/java/eu/kanade/tachiyomi/data/ads/{SmartLinkV3Test,SmartLinkGateRecoveryTest}.kt` — tightened prior assertions and recovery regressions.
- This document and the historical V3 documentation follow-up link.

## اختبار الهاتف

1. حدّد100 فصل جديد: يلزم0/4، دون الزرين المحذوفين. نفّذ خطوة ثم ارجع بالنظام، وأعد فتح نفس الدفعة: تبقى1/4 ولا يبدأ تنزيلها. أكمل4/4: تبدأ الدفعة مرة واحدة فقط.
2. جرّب عدم وجود اتصال/متصفح أو فشل الرابط: تظهر رسالة واضحة، ولا تُحتسب خطوة ولا يبدأ تنزيل جديد. الرجوع يعمل والتنزيلات القديمة تظل سليمة.
3. أكمل19 فصلًا جديدًا فعليًا، ثم الفصل20 في الروايات والمانهوا كل على حدة. جرّب التالي، الاختيار من القائمة، والانتقال العمودي: يظهر فاصل واضح قبل الانتقال، والمتصفح لا يفتح إلا من «فتح رابط إعلاني».
4. بعد العودة من الرابط، يجب فتح الفصل المقصود. جرّب تدوير الشاشة وإعادة التشغيل أثناء الفاصل؛ لا تُعاد خطوة مكتملة. اختبر20 إكمالًا إضافيًا للفاصل التالي.
5. إعادة فتح فصل قديم والتنقل ذهابًا وإيابًا لا يزيدان العداد. اختبر A → ضيف → B → A والتقدم والعلامات والتنزيل الجاري دون مسح البيانات. أرسل نوع القارئ والخطوات والوقت عند أي فشل، دون رموز حساب أو نصوص فصول.
