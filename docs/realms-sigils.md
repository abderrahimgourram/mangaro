# أختام العوالم

This cosmetic collection has 30 stable IDs, six worlds, and no XP/rank/role writes.

## Evidence and trust boundary

Bookmark/library/explicit marked-read facts come from successful existing repository writes. New Reader activity uses selected-image readiness: an unread/loading page never completes a sigil, and opening Details/Reader alone never starts one. The existing boundary observer forwards a real final-image receipt without changing ad eligibility or page rendering. Existing history with positive recorded duration remains usable for historical reconciliation. Successful downloads capture the original job owner and stable source-scoped chapter identity only after the Downloader reports DOWNLOADED and its final file/directory exists. Failed/cancelled attempts cannot emit this receipt. No historical downloads are guessed. Backfill uses current reliable read/bookmark/library/history records, leaving unknown historical unlock dates null. Personal categories exclude the system category and all managed Mangaro shelves. Successful category creation and explicit work organization count for the actor even with Cloud Sync disabled, without importing unrelated shared-device records. Existing account-scoped category UUIDs are reused so later cloud enablement does not change their identity.

Genre attribution uses exact normalized source metadata aliases. Unknown labels remain uncounted rather than guessing from titles. Work/chapter identities reuse the existing CommunityMangaKey/CommunityChapterKey, including stable chapter remote IDs; no chapter numbers or guessed cross-source title matches are used.

Offline reading/download evidence is inherently device-observed, not independently server-attested. The server does not accept client-supplied achievement counters or unlock IDs. It calculates these cosmetic requirements from account-scoped evidence, but a modified authenticated client could forge its own offline activity receipts. Fully preventing that requires an independent trusted activity/attestation source and is not claimed here. Community achievements, in contrast, exclusively use authoritative existing database comments, replies, ratings and reactions. A client cannot submit social counts or social award calls.

## Isolation and synchronization

The no-backup SQLite journal keys every fact, unlock, equipment choice and pending event by owner. Loading sessions emit nothing. Queued events retain their original owner. Guest state is separate and never uploaded as another account's collection/equipment. Reliable shared-library backfill is allowed only for the existing cloud-bound account (including its explicit non-destructive merge policy); another account is never silently imported. Only a user's three explicitly chosen badge IDs are public. Facts, genres, progress and timestamps remain owner-only under RLS.

Facts merge monotonically and retry in bounded 200-row batches, coalescing local signals. Account-safe RPCs reuse SupabaseAccountAuth.withSession. Foreground reconciliation handles offline recovery and incoming social awards; no background polling while the app is stopped. Equipment uses three ordered nullable slots, rejects duplicates/locked/revoked IDs and uses revision-based compare-and-set. An offline edit conflicting with another device restores the server ordering and explains the conflict.

Earned unlocks survive normal unlike, comment deletion, unbookmark and library removal. Service-role-only mangaro_private.revoke_sigil(user_id, sigil_id, reason) revokes verified invalid awards and clears equipped references. It confers no client privilege. Existing self-likes are preserved but excluded from achievement evaluation; new ones are denied by a restrictive RLS policy and the existing like RPC. Anonymous/unconfirmed accounts cannot create eligible likes. Exactly 200 distinct accounts on one comment is required, never a sum across comments.

## UI and lifecycle

The Home drawer opens the collection without changing the selected tab. All 30 Canvas silhouettes, Arabic descriptions and accent palettes are original and keyed by stable definition IDs. Unlock effects include one bounded particle convergence, a restrained flash and a name reveal; they are immediately dismissible. Lazy-grid viewport, window bounds, resumed lifecycle and system animation scale guard continuous effects. Profiles show exactly three miniature slots. Unlock presentation is dismissible, only on Home, acknowledges display durably, and does not cover the Reader or Downloads. Reduced-motion mode skips materialization motion.

## Verification

Focused Kotlin engine tests: `./gradlew :domain:testDebugUnitTest --tests 'mihon.domain.sigils.RealmSigilsTest'`.

SQL integration tests use real PostgreSQL in PGlite with synthetic accounts only, outside the production database:

```
PGLITE_MODULE=/path/to/@electric-sql/pglite/dist/index.js node supabase/tests/realm_sigils.mjs supabase/migrations/20261008121540_realms_sigils.sql
```

They cover RLS, RPC privileges, permanent unlocks, self-like rejection, 200 likes on one comment versus distributed likes, equipment constraints, moderation and public projection. No synthetic accounts/comments/likes are created in production.

Manual device checks remain: viewport/reduced-motion visuals, restart/account switching, offline-to-online synchronization, reading/bookmark/category/download events and profile equipment across two devices. No ADB, emulator, runtime validation or publishing was performed for this feature.

## Exact feature files

- `app/src/main/java/eu/kanade/presentation/account/AccountScreen.kt`
- `app/src/main/java/eu/kanade/presentation/community/CommunityUi.kt`
- `app/src/main/java/eu/kanade/presentation/community/PublicCommunityProfile.kt`
- `app/src/main/java/eu/kanade/presentation/home/MangaroHomeDrawer.kt`
- `app/src/main/java/eu/kanade/presentation/sigils/SigilArtwork.kt`
- `app/src/main/java/eu/kanade/presentation/sigils/SigilCollectionScreen.kt`
- `app/src/main/java/eu/kanade/tachiyomi/App.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/community/SupabaseCommunityRepository.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/download/Downloader.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/sigils/SigilRepository.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/sigils/SigilStore.kt`
- `app/src/main/java/eu/kanade/tachiyomi/di/AppModule.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeTab.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt`
- `data/src/main/java/tachiyomi/data/category/CategoryRepositoryImpl.kt`
- `data/src/main/java/tachiyomi/data/chapter/ChapterRepositoryImpl.kt`
- `data/src/main/java/tachiyomi/data/manga/MangaRepositoryImpl.kt`
- `data/src/main/sqldelight/tachiyomi/data/categories.sq`
- `data/src/main/sqldelight/tachiyomi/data/sigilEvidence.sq`
- `docs/realms-sigils.md`
- `domain/src/main/java/mihon/domain/account/CloudSyncFoundation.kt`
- `domain/src/main/java/mihon/domain/sigils/RealmSigils.kt`
- `domain/src/test/java/mihon/domain/sigils/RealmSigilsTest.kt`
- `supabase/migrations/20261008121540_realms_sigils.sql`
- `supabase/tests/realm_sigils.mjs`
