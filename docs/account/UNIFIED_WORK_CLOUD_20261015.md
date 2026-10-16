# Unified work community and cloud sync — 2026-10-15

Status: **PARTIAL — not approved for publication.** Canonical community and real authenticated cloud-engine tests pass; local manga account isolation fails. Google UI and two physical devices remain untested.

## Verified existing production architecture

The existing authenticated local Supabase CLI exposes **mangaro-prod / pehsxthjetlltlsqcbfa**. The MCP connection exposes a different account; it was not used to query that other project's database. Read-only production discovery preceded all changes.

Reused Android: AccountFoundation, SupabaseAccountAuth and its encrypted session, SupabaseCommunityRepository, CommunityUi/NovelCommunity, SupabaseCloudSync, CloudSyncStore SQLite journal v1, CloudLocalGateway, WorkManager and the existing novel account files. No second Auth, community, HTTP, local database or synchronization engine was added. Source edition/chapter identities, reader/download formats and Smart Focus remain unchanged.

Production tables inspected include profiles/profile_roles, community_comments/comment_likes/ratings/reports/reply_notifications, the five cloud_library/history/progress tables, public_favorites and public_showcase_settings. All inspected exposed base tables had RLS enabled. Novel cloud tables/capability were absent before this task. Existing local inferred work grouping is **not** an authoritative community mapping: it was deliberately not used to merge social data.

Read-only preflight counted 41 profiles, 24 comments (3 replies), 30 ratings, 7 likes, 166 cloud library entries, 4,098 progress rows, 141 history rows, 140 collections and 190 memberships. These are captured counts, not invented activity statistics.

## Implemented community scope

Additive `community_works` and `community_editions` link existing opaque edition keys to verified work identities. Only privileged reviewed SQL can edit mappings; authenticated clients cannot. Public read policies disclose identity relationships only. A private append-only audit records mapping changes; revocation reverses a relationship without deleting editions or social records. Roots cannot form chains/cycles. No title, cover, chapter-count or fuzzy matching is used. **No real literary edition mappings were seeded.** A synthetic quarantined work was used for authenticated testing; its two mappings were revoked afterwards and their audit retained.

Existing comment-page, popular-page and comment-context RPCs resolve work scope in one server request, preserving comment IDs, authors, replies, spoiler flags, timestamps, reactions and staff presentation. Chapter scope deliberately stays on its original edition key. The new invoker post RPC derives auth.uid() server-side, validates a cross-edition reply against the verified work, retains the parent's actual stored edition, and confirms request UUID/body/ownership on retries. Existing validation, moderation and XP triggers remain authoritative.

The existing rating RPC writes one canonical user/work vote through its existing unique constraint. A security-invoker projection selects the latest trustworthy updated_at, then UUID for exact ties, per user/work across historical editions. Old rows remain the audit record; they are not counted as extra votes. The summary and weekly-ranking RPCs use effective votes and preserve 1–5 scale and existing ranking formula. Mapping lookup is indexed/batched within SQL, with no per-edition network query.

Android uses the existing repository, probes the capability at most every five minutes and falls back only when that RPC is genuinely absent. Network/auth/server errors are not mistaken for an absent extension. A successful work mutation invalidates other cached work feeds' freshness; the next visible load refreshes without eager N+1 queries. Viewer-specific rating/ownership/like flags are stripped immediately on account changes, independently of delayed responses. Existing account/target-specific drafts and idempotency UUIDs are retained.

## Account metadata and progress

The previously pending novel extension is deployed through a new versioned forward migration. It reuses the existing compare-and-set revision, owner lock, incremental pull, tombstones and queue, adding only private cloud_novel_state/progress. Exact source, edition, canonical chapter URL and text paragraph/offset remain separate. A JSON operator-precedence bug in the old pending validator was reproduced and fixed; bounded legacy chapter metadata is accepted for prior APK compatibility. New uploads omit ephemeral access/lock evidence and published-row inventories. No chapter bodies, images or offline files are synchronized.

First unclaimed login now requires an explicit device-data merge. Returning previously configured owners retain existing automatic sync. Novel guest import no longer replaces established account progress with an older guest record, and per-chapter imports require an unchanged observed baseline. Guest originals and offline files remain on disk.

Initial device seeds honor cloud tombstones. A removed manga membership is retained in the account-scoped private `guestLibrary/` journal archive; the manga record, chapters, history and files are not deleted. Stale higher-page conflicts cannot override a newer explicit reset. The actual SQLDelight triggers use Unix seconds; event clocks now convert correctly to milliseconds and recognize old misformatted cloud dates. A small account-scoped journal checkpoint retains authored progress time across remote application/SQLite metadata writes. Separate chapter progress outranks a history summary. No local SQL schema/version change was necessary.

**Important unresolved scope:** the existing manga SQLite library/history is still device-shared. Owner-isolated Supabase rows and novel account files do not establish private per-account manga UI workspaces. Historical device-shared manga ownership cannot be inferred safely. This requirement remains PENDING and the full mission must not be described as complete.

## Migration, backup and recovery

Deployed additive migrations:

- `20261015174837_unified_work_community.sql`.
- `20261015175820_novel_cloud_metadata_compatibility.sql`.

Recovered the exact already-deployed `20261010002256_mangaro_showcase_novel_metadata.sql` from remote migration history, resolving the missing local history entry without rerunning or rewriting it. CLI dry-run identified exactly the two new migrations; no seeds, custom roles, vault updates, workflow or publisher deployment occurred.

Physical backup listing reported no available backup/PITR. Before schema changes, affected public metadata and schema definitions were exported privately to `/home/jalem/.local/share/mangaro/cloud-backups/unified-20261015/` with restricted permissions. All 13 exported tables were restored to a LOCAL verification namespace and compared row-for-row, then rolled back. This is a tested affected-metadata backup, **not** a complete Auth/Storage/project disaster-recovery backup. No credentials were exported.

No historical comment/rating row was rewritten, merged or deleted. After testing, all **24 original comment records and 30 original rating records match the private backup exactly**, including IDs, authorship, replies, spoilers and timestamps. Only the two newly created synthetic comments and one synthetic vote were removed under exact UUID/owner/nonce guards. Both synthetic mappings were revoked; zero active mappings remain. Two designated Auth test accounts/profiles and their private fixture cloud metadata remain isolated from real users. Novel cloud metadata began receiving legitimate client rows after the extension became available; those are not test fixtures. Schema rollback restores original RPC definitions/disables capability discovery while retaining new tables, pending metadata and audit records. Mapping correction uses revocation; irreversible historical reconciliation still requires separate approval.

## Validation

Permanent SQL suites: unified_work_community.sql (27 checks), novel_cloud_metadata.sql (11 checks), plus the existing phase4_cloud_sync.sql. Each passed locally, then against the **actual Mangaro production schema** using two fresh temporary auth.users fixtures and real anon/authenticated roles, with every fixture transaction rolled back. Tests cover shared historical feeds/replies, identity-safe retry, 1–5 effective ratings, separate works/chapters, keyset and popular pagination, spoofed/cross-owner mutations, private incremental pulls, tombstones and absence of body fields. Migration reapplication also passed locally.

These are real database RLS tests, **not Google OAuth sessions or cross-device tests**. The new functions are invoker functions, new tables retain RLS, the ratings view has security_invoker=true. Post-change Supabase security/performance advisors added no findings. Existing unrelated findings remain: [leaked-password protection](https://supabase.com/docs/guides/auth/password-security#password-strength-and-leaked-password-protection) and [duplicate comment indexes](https://supabase.com/docs/guides/database/database-linter?lint=0009_duplicate_index); no unrelated Auth/index cleanup was performed.

Final Android results and artifact metadata are recorded below. No physical phone was used.

## Pending acceptance and identity evidence

No specific pair of persisted edition IDs with a verified original-work relationship was supplied. Local inferred grouping does not replace that evidence; consequently zero live communities have been merged. Editorial linking remains PENDING. Original chapter/source records will remain separate when a verified work mapping is later added.

At the owner’s instruction, two designated Supabase Auth email/password test users were created **in the correct existing mangaro-prod project** through the authorized Admin API, then signed in through genuine GoTrue sessions. A separate hosted project was not created; local Docker/Auth was unavailable without host permission changes. Test writes were confined to these new users and a quarantined synthetic community. No Google/Auth provider configuration was changed and no privileged key entered the APK. Seven live authenticated community HTTPS checks passed, including cross-edition replies, idempotent retry, 4→5 voting without duplication, distinct works and viewer isolation. These used a synthetic verified relationship, not a real novel mapping.

The optimized Android SDK/sync engine used **two independent durable SQLite/journal/novel-file stores and fresh processes on ONE API 34 x86_64 emulator**. This is actual authenticated network synchronization, not SQL role simulation, but is not a two-phone or production Google-login UI test. Six phases passed (41 checks): seed device-store 1, restore device-store 2, offline mutation, newer reset on store 2, reconnect/conflict resolution, process restart. A seventh phase passed four cloud/novel/journal isolation checks and failed the local manga library privacy check. The failure is reproducible: `mangas.sq:getFavorites` filters `favorite = 1` without an account scope, and the existing manga SQLite database is shared. B’s seven private server tables are empty, while the A manga favorite remains in the device database.

Do not present 45 passing Android assertions as full acceptance: **one required privacy assertion fails**. Completing account-private manga UI needs a reviewed personal-state workspace/overlay and explicit historical guest ownership handling across favorites, chapters, history and categories. Changing RLS cannot repair a shared local query. No destructive clearing or speculative ownership migration was attempted. The fresh/switch-account merge prompt remains explicit, preventing automatic upload into a different account.

Production Google sign-in UI/session lifecycle and two actual devices remain PENDING. Request interactive browser Google authorization only when that dedicated test is scheduled; never request passwords/tokens.

## Manual acceptance (Arabic)

1. ثبّت نسخة الاختبار فوق Release دون حذف البيانات. تحقّق من Smart Focus والقراءة والتنزيلات في المصادر المعتادة.
2. استخدم حسابي اختبار فقط؛ لا تفترض أن مكتبة المانجا المحلية أصبحت معزولة بين الحسابات في هذه النسخة.
3. بعد اعتماد رابطَي طبعتين للعمل الأصلي نفسه: أضف تعليقًا وردًا من طبعتين، ثم غيّر التقييم 4→5 وتحقق من ثبات عدد المصوّتين. تبقى مناقشات الفصول منفصلة.
4. سجّل الحساب نفسه على جهازين، وأضف رواية ومانهوا؛ اختبر موضع النص والصفحة مع الحفاظ على الطبعة نفسها.
5. افصل الشبكة، غيّر التقدم، ثم أعد الاتصال؛ تحقق من عدم إعادة عنصر محذوف أو طمس إعادة تعيين أحدث. اختبر عودة الموضع بعد إغلاق التطبيق.
6. عند الفشل أرسل المصدر والطبعة والخطوة والخطأ فقط؛ لا ترسل جلسات أو مفاتيح أو رموز Google.

## Final validation and private artifact

- Targeted JUnit: **187 total, 183 passed, 4 optional live tests skipped, zero failures/errors** (app 163 including 4 skipped; domain 24).
- SQL: 27 canonical-community + 11 novel-cloud assertions and existing phase4 cloud suite passed locally and on the correct production schema in rolled-back fixture transactions. Additive migrations were re-applied locally without error.
- Genuine Auth HTTPS community: **7 passed**. Follow-up real JWT reads verified B sees zero rows in all seven private tables, and A’s newer explicit novel/manga reset persisted server-side.
- R8 Android sync: **45 assertions passed, 1 required local-manga privacy assertion failed**, across seven phases. This failure remains a release-acceptance blocker.
- R8 offline reader regression: **31 passed**, 20 checksum-valid chapter files restored, real Arabic novel text and manga page rendered, progress/bookmarks retained, **zero source/fixture HTTP requests**. Initial attempt stopped after 26 checks because an emulator **com.android.systemui / SystemUIService ANR** dialog covered the activity. Its event and trace were retained (pid 745, main thread waiting in the logging mutex); no Mangaro crash was observed. Dismissing that system dialog and repeating only this test passed; no app code was changed for it.
- Existing signed PARTIAL revision 4 assets verified through the optimized source loader. Source adapters/HTTP/Focus/download implementations, workflows and publisher unchanged from this task’s starting snapshot. No claim that every live source was re-tested.
- `git diff --check`, APK ZIP integrity and `apksigner verify` passed. ARM64 and the actually installed x86_64 build contain identical three DEX files.
- Final Release build: **6m 51s**, R8 minification/obfuscation and resource shrinking enabled. No new dependency or broad keep rule.
- APK: `/home/jalem/Downloads/Mangaro-Releases/Mangaro-R8-Unified-Cloud-Test.apk`.
- Package `app.manhwaar.reader`, version **2.0.4 / 26**, **35,581,130 bytes**, `debuggable=false`.
- SHA-256: `c706e81f8fef4b60b79ea99acd7bf80c6a5eaecd2ef3bacb17b3c131d447d5f6`.
- Existing Release signing certificate preserved: `3e52c0d0f434c8b6e2c0edee9eff0864aa4a0368bac4e007fc0d70900aa302ba`; update-compatible with code 25. Prior APKs untouched.
- Local evidence: `/home/jalem/Downloads/Mangaro-Releases/Mangaro-R8-Unified-Cloud-evidence/` includes exact changed-file inventory and task-only patch, sanitized test reports, migration/rollback evidence and matching compressed R8 map. Credentials/private backup bodies excluded.

This is a **private incomplete acceptance build**, not a production rollout. Do not use real account switching as a privacy acceptance test until the local manga workspace gap is fixed. No push/publication was performed.
