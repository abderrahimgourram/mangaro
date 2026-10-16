# Full-screen reader gate — private code35

Follow-up to code34, preserving its persisted counters and transition recovery. No ADB, emulator, installation, live advertising requests, database migration or backend changes.

## Confirmed presentation defect and correction

Code34 still rendered `SmartLinkChapterBoundary` inside the novel chapter list and manga transition panel. Its navigation guard used a floating `AlertDialog`. The optional footer could also service an interval before a pending transition existed. Remove the footer from both reader types and replace the navigation dialog with a full-window, non-floating Android Dialog using MATCH_PARENT dimensions. Its RTL purple/gold screen has one explicit advertising action; Back cancels the attempted transition without granting a step. Scrollable layout supports large fonts without adding advertising touch targets to chapter gestures.

The existing reader loader remains the admission boundary for novel Next/selector and manga paged/webtoon automatic transitions. No counter or reader navigation algorithm is replaced. The same Activity owns the external launch, accepted/away/return proof, account fence, pending destination/page and recreation recovery. A browser return is a local navigation event, not proof of an impression, view or conversion. Historical SQL reading progress is not imported into advertising counters. Novel and manga/manhwa counts remain independent and account-scoped across works.

## Minimal text

Reader: «متابعة القراءة»، «افتح إعلانًا واحدًا للمتابعة»، «فتح إعلان».

Download: «فتح التنزيل»، `1 / 4`, «افتح الإعلانات المطلوبة لبدء التنزيل»، «فتح إعلان». Preserve the existing progress bar, 1–6 calculation, durable earned steps and one-shot queue continuation. Only presentation text changes; download policy, storage and queues do not change. Both screens retain the short disclosure «إعلان خارجي · Adsterra · asiafilm.org».

SmartLink31593088 and support case481902 remain a publisher-reported account-specific exception, not independently verified or general permission. Privacy choices and device-wide launch protections are unchanged.

## Verification scope

84 focused JUnit tests passed, 0 failures/errors/skips (13 suites). The first compile exposed an invalid inherited ScrollView.LayoutParams reference; using its actual FrameLayout.LayoutParams corrected it before the successful run. `git diff --check` passed.

Focused host tests exercise the real persisted policy/journal with injected browser lifecycle events. New fixtures cover 19/20/40 across works, duplicate completions, restart and exact destination/page restoration for both reader categories, and historical completions creating no retrospective debt. Static wiring checks require full-window layout, cancellable Back, no chapter-end SmartLink button, minimal text and unchanged durable download authorization. Existing browser-return, webtoon readiness, account isolation, logout, queue removal/ownership, Smart Focus and cloud reconciliation suites remain in the focused run.

Static layout checks and injected lifecycle events do not prove Android on-screen appearance or actual browser lifecycle. Those remain manual acceptance; no runtime pass is claimed.

## Changed files

- `app/build.gradle.kts`: private acceptance versionCode35; normal production defaults unchanged.
- `app/src/main/java/eu/kanade/tachiyomi/data/ads/SmartLinkController.kt`: full-window reader gate and concise text; keep existing proof/cancellation handling.
- `app/src/main/java/eu/kanade/tachiyomi/data/ads/DownloadAdGate.kt`: concise download text and remove obsolete reader footer implementation.
- `app/src/main/java/eu/kanade/tachiyomi/data/ads/NativeBoundaryAd.kt`: SmartLink uses the transition gate; existing SDK path unchanged.
- `app/src/main/java/eu/kanade/presentation/novels/NovelReaderScreen.kt`: remove SmartLink chapter-end item only.
- `app/src/test/java/eu/kanade/tachiyomi/data/ads/SmartLinkV3Test.kt`: update intentional copy/footer contract.
- `app/src/test/java/eu/kanade/tachiyomi/data/ads/SmartLinkFullscreenGateTest.kt`: focused new fixtures and presentation wiring checks.
- This document.

## قبول الهاتف

1. أكمل19 فصلًا جديدًا: لا فاصل. بعد إكمال20، جرّب الانتقال: تظهر شاشة كاملة بالنص المختصر، ولا يفتح المتصفح تلقائيًا.
2. اضغط «فتح إعلان» ثم ارجع: يفتح الفصل المقصود وموضعه مرة واحدة. جرّب إعادة التشغيل أثناء الفاصل، ثم20 إكمالًا إضافيًا للفاصل التالي.
3. كرر للروايات والمانهوا العمودية/المانغا، مع تغيير العمل وإعادة فتح فصول قديمة. الرجوع لا يمحو التقدم ولا يلغي الفاصل المستحق.
4. تحقق أن تنزيل الدفعة يحفظ `1 / 4` وأن سلوكه السابق لم يتغير، ثم جرّب A → ضيف → B → A دون مسح البيانات.
