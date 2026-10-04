# Mangaro production Account integration

Linked/deployed project: **mangaro-prod**, `pehsxthjetlltlsqcbfa`, region `eu-west-3`.
Client endpoint: `https://pehsxthjetlltlsqcbfa.supabase.co`.

## Configuration and auth

The production URL and publishable client key are configured in ignored `local.properties`; no credentials file is committed. Environment/Gradle properties retain their existing override precedence. Only publishable/legacy anon client keys are accepted in Android; privileged keys are rejected by the build configuration. No Google client ID/secret is needed by this browser OAuth flow.

Google provider is enabled (verified through public Auth settings). Google Web credentials and its callback `https://pehsxthjetlltlsqcbfa.supabase.co/auth/v1/callback` remain externally managed by the user. No provider secrets were retrieved or changed. The public settings still report Email enabled: disable that provider in Dashboard if server-side signup must also be Google-only. Android exposes only Google OAuth and rejects non-Google/anonymous sessions.

One canonical Android callback: **`mangaro://auth`**, scheme `mangaro`, host `auth`, no path. The supplied Supabase redirect allow-list entry matches it. Both Debug and Release use the same callback; when both apps are installed Android may ask which one to open. No new callback Activity is created. Existing MainActivity handles cold/warm returns and its duplicate-activity guard preserves an active Reader session.

The SDK's PKCE deep-link handler exchanges the code. The app checks the exact route, rejects implicit token fragments, requires a pending verifier (15-minute lifetime), and consumes callback data without logging it. The verifier is encrypted/persisted before browser launch. Tokens/verifiers use Android Keystore AES-GCM under `noBackupFilesDir`. Session restoration runs independently of Home; unavailable/invalid auth falls back to Guest. Sign-out clears cloud credentials only, including offline cleanup, and never touches manga/library/history/downloads.

## Deployed migrations (source of truth)

- `20261003211112_mangaro_account_phase1.sql`: profiles, signup/updated_at triggers, username index, profile/Storage RLS, avatars bucket and platform-helper EXECUTE hardening. Corrected before its first deployment.
- `20261003221412_mangaro_profile_metadata_trim.sql`: forward fix for Google display names truncated at a whitespace boundary. Deployed migrations must not be rewritten.

Continue with the linked CLI's normal `supabase db push`. Local `.temp/` link metadata is ignored. No production reset was used.

## Contract and security

Public profiles contain UUID, nullable username, display name, custom avatar path, Google avatar URL and timestamps. **No email**, token, XP or level columns. Owner email comes only from their Auth session. Google signup uses NEW.id and leaves username NULL; repeat login never overwrites a profile. Profile completion does not block local reading.

Handles normalize to lowercase `[a-z0-9_]{3,24}`; `profiles_username_lower_unique` enforces case-insensitive uniqueness. Display names support Unicode, are trimmed, max 40 characters. Profile updates grant only username/display_name/avatar_path to the authenticated owner; identity, Google avatar and created_at are protected. `set_updated_at()` maintains timestamps server-side.

Public `avatars` bucket accepts JPEG/PNG/WebP, max 2 MiB. Writes/deletes require auth.uid() to own the first folder **and** the exact `<user-id>/avatar.webp` path. Android accepts input up to 2 MiB, validates actual image type/dimensions, resizes to max 1024px and encodes WebP <=1 MiB. Paths stay stable; updated_at versions Coil image URLs. Failed custom images fall back to Google, then the bundled default.

Direct anon/authenticated execution of `rls_auto_enable()` and trigger-only helpers is revoked; `ensure_rls` remains enabled. The Account phase added no Community backend, XP awards, cloud sync or local SQL migrations. Community deployment is documented below.

## Verification

Remote catalogs verified all columns, FK cascade, checks, index, RLS policies, bucket constraints, triggers/functions and their restricted grants. `tests/phase1_rls.sql` passed under actual anon/authenticated roles inside a rolled-back transaction: anonymous writes denied, owner updates allowed, cross-user writes/deletes denied, immutable fields protected, case conflict/invalid handles rejected, 40-character names enforced, signup metadata boundary fixed, and automatic RLS still working. Storage metadata fixtures emulate the Storage API transaction delete guard; no RLS was disabled and no real files were uploaded. All fixture users/profiles/objects and the probe table were rolled back. Security Advisor reported no findings. Public profiles/Auth HTTPS checks succeeded.

Google OAuth was subsequently manually verified by the user on a real Android device. Google test-user/consent restrictions remain external.

Manual checklist: open as Guest; tap Google; choose account; return to Mangaro; choose username; edit display name; upload avatar; reopen and verify session; sign out; verify local Library/history/downloads remain.

## Community Phase 2

New forward migrations: `20261003223459_mangaro_community_phase2.sql` and
`20261003224550_mangaro_community_private_report_dedup.sql`. The second fixes a
permission issue discovered by rollback tests after deployment: report
idempotency catches unique violations without granting private report reads.
Both were deployed to the same production project; deployed Account migrations
were not edited. CLI/remote migration history matches.

`community_comments`, `community_comment_likes`, `community_ratings` and
`community_comment_reports` explicitly enable RLS. Social writes require the
caller to own the row and have a completed public profile. Column grants and
triggers protect comment/rating identity and timestamps. Replies must target a
top-level comment in the exact same manga/chapter context; deleting a parent
cascades its replies, likes and reports. Ratings have separate partial unique
indexes for manga and chapter targets. Likes/reports have composite uniqueness.
Reports have no public/client read policy or SELECT grants.

Security-invoker RPCs provide public comment pages (20 rows, newest-first
`created_at + id` keyset cursor), database rating summaries and authenticated
idempotent rating/like/report operations. The comments projection joins public
profiles and counts in one server request; it never selects email or private Auth
metadata. Reply pages load only when expanded. No privileged read endpoint,
Realtime socket, XP awarding, cloud Library sync or local SQL change was added.

The existing `CommunityRepository` now uses the single Account-owned Supabase
client. Details, chapter discussions and the Reader sheet share this adapter.
Server-confirmed mutations refresh observable state; failed submissions retain
text, double-submit is prevented, failures offer retry and leave reading usable.
Shared handle text renders explicitly LTR within unchanged Arabic RTL layouts.

`tests/phase2_rls.sql` passes against actual anon/authenticated roles with all
fixtures rolled back: public reads, denied guest/incomplete-profile writes,
ownership, immutable fields, shallow same-target replies, blank/length/target
validation, duplicate constraints, rating updates, keyset pagination, payload
privacy and cascades. All four RLS flags and platform automatic-RLS hardening
were verified remotely. Security Advisor has no Community findings; it reports
an Auth-only leaked-password-protection warning (Google Auth configuration was
not changed). See [Supabase password security](https://supabase.com/docs/guides/auth/password-security#password-strength-and-leaked-password-protection).

Google OAuth was manually verified by the user before Phase 2.
**Android Community runtime verification pending manual device test.**

## Progression Phase 3

Forward migrations `20261003230846_mangaro_server_progression.sql` and
`20261003231953_mangaro_progression_rpc_hardening.sql` add trusted progression.
Existing deployed Account/Community migration files remain unchanged.
`user_progression` holds owner-readable total XP and server-calculated level;
`xp_events` retains unique action awards and revocation audit; and
`reader_chapter_completions` permanently records the first claim per user/stable
chapter key, including claims capped at zero. All three explicitly enable RLS;
clients have owner SELECT only, with no INSERT/UPDATE/DELETE grants.

Rewards are fixed server-side: top-level comment **12 XP** (5 eligible awards per
UTC day), reply **4 XP** (5/day), completed chapter **2 XP** (50/day). Per-user row
locks serialize eligibility checks and balance updates. Zero-amount capped
ledger entries prevent later re-awards. Deleted comments and cascaded replies
revoke their contributions once while positive original awards still consume
their original award-day caps. Existing comments receive no retroactive XP.
Profiles backfill/start at zero XP, level one, through an additional profile
signup trigger without replacing Auth triggers.

`progression_level(total_xp)` is the canonical server curve: the transition from
level L costs `40 + (L-1)*8`; cumulative threshold is `40*n + 4*n*(n-1)` for
`n=L-1`. Every boundary was tested, including 4407→29 and **4408→30**. Level stays
within 1–30. Android's existing rank resolver presents the canonical Arabic
rank names; its added threshold helper only formats progress inside the
server-confirmed level. No client XP/rank/level mutation exists.

Generic award/revoke/balance functions live in the non-exposed
`mangaro_private` schema and are not executable by client roles. Public
`claim_chapter_completion(manga_key,chapter_key)` is an invoker wrapper around a
private, authenticated, complete-profile checked implementation deriving
`auth.uid()`. Clients cannot supply user ID, reward, XP or level. A bounded
public author-level projection returns user ID/level only, joined into the
existing comment page RPC, without total XP or per-comment network requests.
Security Advisor flagged the original public definer endpoints; the second
forward migration moves those implementations behind the invoker wrappers.
Final Advisor findings contain only the existing unrelated Auth password warning.

Account restoration maps server progression, retaining last-confirmed values
on progression read failure. Confirmed comment/reply creation, deletion and
chapter claims refresh only the current owner's progression. Ratings, likes,
reports, edits and login earn nothing. Account shows total XP and an accurate
current-level earned/required bar; level 30 shows maximum level. Drawer and
comments reuse the existing restrained rank/level presentation.

Reader claims are asynchronous and session-deduplicated, with safe retry after
failure. They use the existing Community key mapping and existing final-page
completion path, requiring a rendered final page after an earlier rendered page
in the current Reader session. Opening/resuming directly at the end, an error
page, incognito reading or a one-page chapter does not automatically claim XP.
Local reading progress/completion/navigation semantics are unchanged. Chapter
claims are **not cheat-proof**: third-party local reading cannot be verified
cryptographically by this backend. The server controls deduplication and caps;
a modified client can still submit otherwise well-formed completion claims.

`tests/phase3_xp.sql` passes with rollback-only fixtures under actual client
roles: direct mutation denials, owner-private XP, complete-profile gating,
rewards/caps, duplicate events/claims, delete/repost protection, zero-boundary
revocation, historical UTC award windows, capped claims remaining ineligible
later, public comment levels, all level boundaries, ledger consistency and
internal-function privileges. No production fixtures remain. No Realtime,
polling, cloud sync, local SQL migration, publisher or workflow changes.

**Android progression runtime verification pending manual device test.**

## Local-first cloud replication — Phase 4

Migration `20261003234522_mangaro_local_first_cloud_sync.sql` adds only private
`cloud_library_entries`, `cloud_library_collections`,
`cloud_library_entry_collections`, `cloud_manga_history`, and
`cloud_chapter_progress`. Explicit owner RLS and column grants deny guest reads,
other-user reads/writes, hard deletion, client revision/timestamp writes and
cross-owner membership references. No public source restoration projection exists.
`cloud_apply_change` is an authenticated security-invoker compare-and-set RPC;
`cloud_pull_changes` is an owner-only security-invoker RPC limited to 200 rows.
Trigger-only `mangaro_private.cloud_stamp` supplies revisions/timestamps. Owner
advisory transaction locks serialize sequence allocation with pulls to prevent
late commits below a cursor. Rows are tombstoned, not hard deleted.

Account UI asks for explicit merge consent once per account/device. Later declines
are remembered; enable/disable and manual sync remain available there. The isolated
`noBackupFilesDir/cloud-sync.db` journal contains account-scoped consent, collection
UUID mappings, coalesced dirty hints/outbox, and durable unresolved incoming rows;
it contains no credentials or downloaded files and changes no Mihon schema.
Built-in shelves reuse their internal shelf identifiers for deterministic UUIDs.
Other categories retain individual account-scoped UUIDs, even with identical names.
The Library's “All” view is not uploaded as a category.

First enable snapshots local state before applying cloud changes. First merge
unions active Library/membership data, preserves independent history, and retains
furthest progress/completion. Later changes use observed revisions; stale
removals/unread mutations cannot blindly replace newer cloud state. On a revision
conflict, newer server read/unread state wins; higher pages can be reconciled when
read state agrees. A user may issue a new explicit mutation after that baseline.
Re-enabling captures changes made while disabled. Sign-out/account switching stops
that account's work without deleting local/cloud data; requests carry their owner
and are rejected if a different session is used.

Successful local repository writes persist bounded per-entity hints. Network-only
unique WorkManager jobs coalesce writes with a 20-second opportunity and exponential
retry, and also run after enabled session restoration/manual sync. No polling,
Realtime, per-card request or per-page network call is introduced. Remote writes
use coroutine-local suppression to avoid echoing back to cloud. Receipt cursors
advance only after durable local inbox storage; separate applied checkpoints stop
before unresolved rows. This allows other works to sync while an extension or
chapter is unavailable without losing that pending restoration.

Restoration matches source ID/URL and verifies the existing opaque Community key.
It never matches titles, fabricates chapters, or refreshes every source. Missing
sources/chapters remain unresolved and retry during sync or, for newly available
chapters, from the cached inbox without requiring a network request. History-only
works stay outside Library. Remote completion does not call XP claim RPCs and a
persistent account/chapter marker suppresses later restored-completion claims.
Historical upload/restoration awards zero XP. Downloads and images are never
uploaded. Cloud sync never controls Home readiness or Reader rendering.

Verification: `supabase/tests/phase4_cloud_sync.sql` uses rolled-back fixtures with
real anon/authenticated roles, validates isolation, revision security, CAS,
constraints, ownership-safe membership, independent history and zero sync XP.
Focused Android/domain tests cover repeat restoration, category identity, guest /
account isolation, initial additive policies, remote suppression and restoration
without XP. Run `:app:assembleDebug` for the APK. Cross-device/offline/runtime sync
still requires manual device verification; these checks do not claim device testing.

## Phase 5 retry hardening

`20261004004606_mangaro_comment_retry_hardening.sql` permits inserting a saved
comment request UUID into the existing primary key. Ownership/profile RLS, body
validation, immutable fields and XP triggers are unchanged. An ambiguous retry
confirms the existing owner, target, parent and trimmed body; it never updates
a conflicting comment. Editing a draft starts a new request.

`supabase/tests/phase5_comment_retry.sql` rolls back all fixtures and verifies
owner/guest boundaries, immutable IDs, retry uniqueness and zero additional XP
from duplicate posts, ratings or likes. Client regression tests use a local HTTP
fixture to cover lost responses, confirmed writes followed by failed reads,
account changes during sync, and unreadable sync metadata. No device testing is
claimed by these checks.

## Profile presentation

`20261004085113_mangaro_profile_presentation.sql` adds optional public `bio`
(trimmed, max 160 Unicode characters) and owner-scoped `cover_path` fields.
The existing profile ownership policy and restricted column grants remain in
force. `profile-media` accepts bounded image types; owners can write/delete only
`<auth.uid()>/cover.webp`. Android preprocesses at most 4 MB of JPEG/PNG/WebP
into a centered 1200×500 WebP (at most 1 MB), including EXIF orientation.
No cover uses a network-generated placeholder; local decorative artwork remains
visible while a custom image loads or if it fails.

The authenticated, security-invoker `profile_own_statistics()` reads live own
comment/reply and current-rating counts, without public/private Auth metadata.
Library count comes from the existing local observable Library, not a cloud
counter. Failure shows unavailable values and retry, never fabricated zeroes.
`supabase/tests/profile_presentation_rls.sql` verifies profile validation, media
ownership, count isolation and privacy using fully rolled-back fixtures.
The migration was deployed through the authenticated Supabase connector; its
recorded server version is also the local filename. Google OAuth, XP rules,
Community flows and cloud replication are unchanged. Profile/media Android
runtime verification remains manual.

## Community presentation / public profiles

`20261004110246_mangaro_community_public_presentation.sql` adds only two
security-invoker reads. `community_public_profile(uuid)` explicitly projects
public profile presentation fields, server-confirmed level and live comment /
rating counts. It never reads Auth metadata, owner XP or private cloud state.
`community_comments_popular_page(...)` ranks the whole discussion by likes,
then creation time and ID, using bounded 20-row keyset pages. Newest comments
and shallow replies keep the original endpoint. Both paths deduplicate comment
IDs in the existing repository; changing sort starts a fresh cursor.

`supabase/tests/community_public_presentation.sql` verifies these reads under
anon RLS, including a highest-liked comment outside the newest initial page,
privacy, bounded cursor continuation and no duplicate pages. All fixtures,
including trigger-generated progression events, are rolled back. No Community
write rules, rewards, Auth or cloud sync policy is changed. Reader profiles use
the existing overlay; returning does not recreate the reading session.

## Explicit comment spoilers

`20261004132417_mangaro_comment_spoilers.sql` adds `spoiler boolean NOT NULL
DEFAULT false` to the existing comments/replies table. Owner-only insert/edit
column grants and existing RLS remain in force. Both newest/reply and most-liked
pages include the flag without changing ordering, cursors, counts or public
profile fields. Flag-only edits update the server timestamp and never award XP.
The migration was deployed to `pehsxthjetlltlsqcbfa`; its filename matches the
recorded remote version.

`supabase/tests/comment_spoilers.sql` checks defaults, owner edits, non-owner and
guest denials, reply metadata, bounded pagination, privacy and unchanged XP on
editing. All fixtures roll back. Existing Phase 2, retry and public-presentation
security suites also pass with the new schema. Android conceals the body until
explicit reveal, including in previews and replies; reveal state is temporary.

## Private reply inbox

`20261004135703_mangaro_reply_notifications.sql` creates an owner-private inbox
from new verified replies only. Self-replies create no event. The trigger-only
SECURITY DEFINER insert helper has an explicit empty search path and no client
EXECUTE grants. Recipients can select their rows and update only `read_at`;
the server assigns the first read timestamp. Clients cannot insert, delete,
change identity or reset read state. Parent/reply deletion cascades the event.

`reply_inbox_page` is a security-invoker 20-row `(created_at,id)` cursor read with
joined public actor data. Spoiler bodies are replaced with null previews in SQL
and independently concealed by Android. `community_comment_context` reuses the
existing public fields to resolve the exact parent ID and target, including
threads older than the first comment page. No Auth or private cloud data is read.

The rollback-only `supabase/tests/reply_notifications.sql` covers recipient and
cross-user RLS, blocked inserts/direct trigger invocation, self-reply suppression,
spoiler/privacy checks, bounded duplicate-free pagination, immutable identity,
server read timestamps, individual read and mark-all-read. Local work updates
are captured from the existing Library updater; they need no backend table.
History uses the existing local history/chapter data and cloud semantics.

## Phase 9 — identity and opt-in showcase

Forward migrations `20261004144708_mangaro_identity_showcase` and
`20261004144745_mangaro_reserved_names_rls` were deployed only to mangaro-prod.
The second documents explicit client denial for the private reservation registry;
Security Advisor now reports only the existing leaked-password warning.

Production already had a valid canonical unique username index. No duplicate or
reserved-name accounts were found; no accounts were renamed, deleted or merged.
Canonical trimming/lowercasing and the existing index remain authoritative; the
availability RPC is advisory. Reserved names: admin, administrator, moderator,
support, mangaro, system; jalem is reserved to the independently confirmed owner.
The developer role is bound to UID ad5f6ca4-dec3-466e-a276-676284faab26, never a
username/display name. It supplies badge and Level 30 **presentation** only;
actual XP/ledger/level curve remain unchanged, and no private-data/admin powers
are granted.

Public Library defaults private. Explicit owner save publishes only selected
opaque manga keys, title snapshots and controlled cover paths. Private cloud
Library/categories/history/pages/URLs are never exposed. Favorite slot limits
are server enforced: 5 initially, 10 at Level 5, 15 at Level 15, 20 at Level 25;
the trusted developer role allows 20. No essential functionality is gated.
Showcase covers use a private `showcase-covers` bucket, WebP <=256 KiB, processed
at 320x480 with existing Coil source/cache handling. Public viewers get one
bounded batch of signed image URLs (two hours); disabling hides the showcase and
prevents new non-owner signing. Previously viewed/cached images or unexpired
signed URLs cannot be recalled immediately.

Public chapters-read is an opt-in aggregate of unique server-known chapter keys:
active synced `is_read` rows plus live completion claims without a cloud row.
An explicit cloud unread/tombstone overrides an older completion claim.
Unsynced historical local reading is not invented; existing cloud sync can make
it available. No chapter list/page/time appears publicly and no XP is awarded by
showcase/aggregate computation. Favorites resolve an exact source-scoped key
locally, then through bounded existing source search on explicit tap; unavailable
items retain their showcase data and show a generic message, never title-fuzzy
navigation to another work.

`tests/phase9_identity_showcase.sql` checks normalization/uniqueness/reservations,
role spoofing, role surviving handle changes, max presentation independent of XP,
private defaults, cross-owner isolation, malformed metadata, atomic failures,
slot limits, read-count dedup/unread handling, cover ownership and public payload
privacy. All fixtures roll back. Existing Community, XP, Cloud, spoiler and reply
notification tests also passed. The XP test allowlist now recognizes the three
narrow Phase 9 helpers; generic XP mutation functions remain inaccessible.

A live two-connection case-variant username race was verified: one transaction
succeeded, the other received 23505 from the existing unique index. Both temporary
auth identities were removed afterward; remaining fixture count was zero.


## Phase 9.5 — rank identity and Library Showcase

One forward migration: `20261004153836_mangaro_showcase_featured`, deployed to
mangaro-prod. Adds `featured boolean default false` to existing public_favorites;
existing sort_order and visibility are reused. Owner-only atomic save derives
identity from auth.uid(), retains deployed slot limits, accepts at most three
featured works, and rejects unknown/private fields. Public projection adds only
featured flags and published Showcase availability for the quiet empty state.
All private Library/history/progress/URLs remain protected; no new Library model.

`RankVisuals` is the shared deterministic palette. Visual tiers use 1–4 / 5–9 /
10–14 / 15–19 / 20–24 / 25–29 / 30, independent of unchanged canonical Arabic rank
names. Primary / surface / decorative soft colors:

| Levels | Primary | Surface | Soft |
|---|---|---|---|
| 1–4 | #A7A0B8 | #24212B | #6D667A |
| 5–9 | #C38A62 | #2B211D | #7D5540 |
| 10–14 | #63B89C | #182824 | #376E60 |
| 15–19 | #6F9FE8 | #182333 | #405F91 |
| 20–24 | #A67BE8 | #241C34 | #654B90 |
| 25–29 | #D6B56D | #2B2518 | #8B743E |
| 30 | #E4C77A | #2B2419 | #8B743E |

MAX uses secondary #AE86F3; other tiers use their primary as secondary. Frame
edges, cover fades, page/M emblems, level pills and name accents reuse these
tokens. Developer role remains independent: a separate M role glyph plus MAX
presentation, never a fake verified check. No social powers are unlocked.

The editor selects from the actual local Library with covers/search, orders with
explicit move controls, features a subset up to three, removes items and previews
the same public layout. Featured cards appear first, followed by a three-column
cover grid. A disabled Showcase publishes no items or chapters count. Empty
published shelves show a quiet empty state, never private Library size. Exact-key
resolution is unchanged. Owner editing is in their existing Account profile;
public viewers receive no edit controls.

Deployed slot economy is intentionally unchanged: 5 initially, 10 at Level 5,
15 at Level 15, 20 at Level 25; trusted Developer gets 20. It differs from the
illustrative 10/20 thresholds in the request. A small account-scoped ephemeral
tracker records tier increases from confirmed targeted progression reads;
profile opening consumes one notice, including any real slot increase. Restoration
alone, Developer max override, account switching, and revoked levels do not create
milestone notices. XP rewards/caps/curve and Level 30 threshold are untouched.

Focused domain tests cover every tier boundary, palette determinism and >=4.5:1
text/surface contrast, MAX/role independence, one-shot milestone/account isolation,
ordered selection and feature/cap bounds. SDK tests retain exact keys, flags and
private defaults through server responses and account changes. Rolled-back server
tests verify default privacy, ordering persistence, three-feature and slot limits,
atomic failures, owner isolation and public payload fields. Phase 9 identity and XP
regressions also passed. Security Advisor has no new findings; only the existing
leaked-password warning remains. Android runtime/visual verification is manual.

## Phase 9.5 final — weekly community picks and automatic sync

Starts from d95c704, preserving the already-deployed rank tiers and curated Showcase.
One new migration: `20261004160027_mangaro_community_weekly_ranking`.
`community_weekly_ranking()` is a stable SECURITY INVOKER public aggregate, safe
search_path, explicit anon/authenticated EXECUTE grants, no new table or RLS change.
It returns <=100 opaque manga keys and average/count/weighted score, never rater
IDs, source identity, private URLs or private user data. Android matches only exact
keys against real existing catalogue/local manga identities; unresolved keys are
not replaced by title. No individual rating downloads and no N+1 rating requests.

Production audit: 3 manga ratings across 2 works; vote counts 2 and 1, averages
5.0 and 3.0, global mean 4.3333. Confidence m is max(2, ceiling(median votes per
rated work)), currently 2. Score is (v*R + m*C)/(v+m), where C is the global
rating-weighted mean. At least 2 votes are required for a ranked slot. Ties use
score DESC, votes DESC, average DESC, then opaque key ASC. m adapts to distribution;
no arbitrary large fixed threshold. One isolated 5-star vote cannot displace a
properly rated work. The scale stays 1–5 and ratings still award 0 XP.

Home's old Story banner, next-story action and saved selection logic are removed.
The replacement uses exactly five substantial snap-scrolling cards when five
resolvable candidates exist. Every card has position/title; community cards show
real average/count, weekly fillers explicitly say اختيار الأسبوع and never invent
ratings. Real Popular/Latest catalogue results supply fallback candidates. Exact
identity alternatives are retained, with no new source/search engine or title merge.

ISO weeks use UTC Monday–Sunday (including ISO week-year boundaries). SHA-256 of
week:key determines filler order. A bounded <=160-candidate catalogue pool is
frozen locally for the week, persisted with the five cards, so launches, refreshes
and recomposition cannot reshuffle picks. The next week re-seeds from available
real candidates. Public ranked entries replace fillers as signal becomes usable.
Cached cards survive offline/ranking failure; optional ranking reads time out in
12 seconds. Refresh is on Home return (30-minute success throttle) or manual Home
refresh, no polling/Realtime. Home readiness never waits for this extra read.

Cloud starts automatically on first authenticated login and restored sessions for
the bound account. The isolated journal keeps `_device/bound` locally. A different
account (or ambiguous legacy multi-account journal) is paused and requires one
explicit non-destructive merge; it cannot silently inherit the previous Library.
The existing configure/seed/merge/queue/worker/suppression/restore paths remain
unchanged. Unique account work, network constraints and exponential backoff remain.
Sign-out cancels account work, preserves all local reading/download data, and
capture never sends a previous account's queue as the next user. No routine enable
prompt or large Cloud card remains. Account has a compact status/Sync now utility;
only real account-binding ambiguity exposes a merge decision.

Account's published Showcase covers now occupy the profile surface above stats,
with existing selection, ordering, three-feature limit, server capacity, private
defaults and exact-key taps preserved. Shared rank palette, MAX and Developer
presentation remain as documented above; XP, trusted role and Auth architecture
are untouched. No Reader, parser, source engine, notification/spoiler semantics,
GitHub Actions or publisher changes.

Tests cover ranked/filler mixtures, deterministic ties/weeks, singleton rating
exclusion, exact keys, offline/process cache restoration, titles/no fake ratings,
fresh/restored automatic startup, duplicate session events, queue coalescing and
account-switch/sign-out isolation. Existing Home, cloud restore, profile Showcase,
public identity and rank tests are retained (obsolete Story selection test removed).
`phase95_weekly_ranking.sql` rolls back all fixtures and checks aggregate formula,
read/update identity, private payload exclusions, Guest access and cross-user RLS.
Security Advisor has no new findings; the existing leaked-password warning remains.
Android visual/runtime checks are user-manual; no ADB/emulator/install used.
