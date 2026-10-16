# Logout recovery and complete source checkpoint — 2026-10-16

## Reproduced mechanism and limits

Before this fix, `SupabaseAccountAuth.signOut()` called `publishGuest()` before entering the `try/finally` that cleared the SDK session. `LocalAccountWorkspace.bind()` marks the workspace not ready before its suspending SQL operations. MainActivity then removes the AccountPanel composition; AccountPanel launches actions in rememberCoroutineScope. Cancellation at that boundary could therefore interrupt the workspace switch BEFORE session cleanup was protected. LocalAccountActivities had already hidden the old private view. Restart could load the untouched encrypted session again. This is a source-backed cancellation mechanism, reproduced through deterministic cancellation injection; no physical-phone crash stack was available, and no claim is made about an additional device exception.

Logout now belongs to the existing application Auth scope. Concurrent taps join one operation. SDK local sign-out semantics are preserved, with a bounded remote attempt and noncancellable SDK cleanup before publishing guest state. Offline/network failures produce a truthful local-only logout warning, not a claim of server revocation. AtomicFile deletion is checked rather than assumed successful. A failed local cleanup remains recoverable and is not acknowledged as success.

The complete session observer/workspace transition now shares the existing Auth mutation mutex. An obsolete queued status is ignored. Workspace observer failures become safe UI errors. MainActivity reveals only the newly composed account-neutral boundary after hiding the previous private frame; this boundary provides retry if local restoration fails. No personal table or download file is wiped, and guest data is never implicitly claimed.

## Verification

Final focused host run: **108 passed, zero failed/errors/skipped, 18 suites**. Eleven new logout tests use the real installed Supabase SDK against loopback Auth/PostgREST responses and controlled session storage. They cover persistence cleanup before guest exposure, caller cancellation, double taps, expired sessions, transport failure, local cleanup failure/retry, workspace failure/retry, stale user mutations, reopening the SDK, and A → guest → B → guest → A.

The last case exercises the REAL existing LocalAccountWorkspace and generated SQLDelight schema in ONE persistent SQLite file, with three catalog/chapter identities, A's favorites/categories/history/bookmarks/page-8 positions, B's independent changes, and file close/reopen. A's exact tested projection returns; B cannot inherit its private state. SQLite integrity and foreign-key checks pass. This is a seeded host SQLite test, not an Android or live Google login test.

Existing focused cloud reconciliation, account-owned deferred download deletion, novel download removal/cancellation/persistence, guest merging, Smart Focus, source chapter transactions, canonical community viewer isolation and Smart Catalog V2 tests also passed. No ADB, emulator, device installation, real OAuth or production Supabase mutation was performed. Android Keystore/filesystem behavior and phone UI/logout/re-login remain manual acceptance items.

## Checkpoint and private build

The initial dirty worktree was backed up outside Git with restricted permissions before editing. Accumulated Smart Focus, source/index/download repairs, account workspaces, Unified Cloud, canonical communities, code29 deduplication, code30 Smart Catalog V2, resources, tests, tooling and safe SQL migrations are retained together because they are interdependent. Existing intentional deletions are preserved. Workflows, publisher/signature rules and production backend configuration are unchanged in this task.

The existing origin is the PUBLIC `abderrahimgourram/mangaro` repository. The checkpoint branch is `backup/mangaro-complete-20261016`. Current workflow triggers were inspected: this backup branch push does not publish a Release, rules, website or database migration. Private keystores/signing configuration, local.properties, credentials, databases, generated APKs, Python caches and raw build/logcat evidence remain local. Maintained documentation and sanitized fixture/provenance summaries remain source-controlled. Staging exposed an inherited missing `.gzip` binary attribute: Git would have changed compressed bytes through LF normalization. The attribute is corrected; all staged gzip blobs are decompressed and compared byte-for-byte with the intact worktree before commit. Public Android configuration is not treated as a private credential.

Private acceptance versionCode **31** is unused among the preserved local Release artifacts; normal production version configuration is retained. Build via `:app:assembleRelease -PsunovelsPrivateAcceptance=true`, using the existing Release certificate, minification and resource shrinking. Artifact/build/signature/checksum results are kept alongside the private APK outside Git; compilation is not phone acceptance.

Smart Catalog V2 source and code30 build/tests are preserved. Its physical-phone acceptance is still pending. The two screenshot duplicate-looking pairs remain unverified where provider/reviewed identities were unavailable; no title-only merge was introduced.

## Manual acceptance

1. ثبّت التحديث فوق Release الحالية دون حذف البيانات. سجّل الخروج، ثم أغلق التطبيق وافتحه: يجب بقاء واجهة الضيف وعدم عودة الحساب.
2. اختبر A → ضيف → B → ضيف → A. لا تظهر مكتبة A أو تقدمه أو تنزيلاته لدى B، ثم تعود بيانات A عند تسجيل دخوله.
3. كرر الخروج دون إنترنت وأثناء تنزيل/مزامنة. يجب حفظ الملفات المكتملة، وعدم إعادة إسناد أعمال A إلى B. رسالة الخروج المحلي لا تعني إبطال جلسة الخادم.
4. عند فشل محلي يجب ظهور رسالة قابلة لإعادة المحاولة، لا نجاح زائف أو واجهة خاصة للحساب السابق. أرسل وقت الخطأ ونصه؛ لا ترسل رموز جلسة أو بيانات اعتماد.
