# Phase 3a — authentication lifecycle recovery

## Evidence -> correction -> proof

1. Authenticated SDK session followed by a failed profile fetch bound the correct
local workspace but never retried profile loading. Recovery without another SDK
status event was absent. A real SDK 3.8.0 / loopback PostgREST 503 -> 200 test failed
on original production code waiting eight seconds for recovery, even after allowing
20 seconds for the original error. It now passes without a second login or manual
state injection. Initial profile loading and each recovery attempt have a 12-second
bound; three delayed retries at 2/5/15 seconds are application-owned, serialized
with the existing auth mutex, owner-checked and canceled on logout/account change.
Explicit account-screen retry remains after the bounded attempts are exhausted.

2. Installed SDK currentUserOrNull reads only SessionStatus.Authenticated. During
RefreshFailure it returns null although encrypted storage can retain the local
owner. The old fallback could switch a restarting offline user to guest, or leave
a previous Authenticated application state after SDK auth expired. The fallback
now uses encrypted SDK storage only for LOCAL workspace identity during initializing/
refresh failure. Application auth becomes Loading; no privileged cloud sync is
authorized by this fallback. It never imports a token or fabricates a profile.
A real expired-session refresh failure preserves local owner A, persisted session
and non-authenticated state. SDK controls actual token validity/refresh/logout.

3. Serialized browser launches alone did not coalesce rapid repeated login taps.
A 1.5-second monotonic duplicate-launch guard returns recoverable feedback; failed
launches are not throttled. A PKCE SDK fixture verifies one URL-launch invocation
from two rapid explicit requests. No live Google account/browser was used.

## Verification

Normal debug compilation plus AccountLogoutTest: **15 passed / 0 failed / 0 skipped**,
104.34 seconds. Tests include actual SDK session/persistence cleanup, offline/401/503
logout, double taps, UI coroutine cancellation, deletion failure, account switches,
SQLite A -> guest -> B -> guest -> A and reopening, retry cancellation, profile
recovery, PKCE duplicate launch and expired refresh ownership. Before-fix failures
are retained privately. One intermediate test compilation required the SDK's
experimental UrlLauncher opt-in annotation; no test ran in that failed invocation.

## Scope and limitations

Changed SupabaseAccountAuth.kt, AccountScreen.kt and AccountLogoutTest.kt only, plus
this report. Existing storage encryption, SDK version/configuration, credentials,
PKCE route/verifier validation, logout semantics, workspace schema and server
permissions are unchanged. No production Supabase calls, migrations, RLS changes
or real-account experiments. No ad/source/reader/download changes.

The consulted SDK source is the locally installed 3.8.0 source archive. Current
[session documentation](https://supabase.com/docs/guides/auth/sessions) and
[OAuth documentation](https://supabase.com/docs/reference/kotlin/auth-signinwithoauth)
were checked. The announced TLS CBC removal is scheduled no earlier than
2026-11-10; it is not evidence explaining today's reported login failures.

Real Google OAuth and real-account old-to-new device acceptance remain unperformed.
After exhausted offline retries the local workspace remains available with an
account recovery message; automatic cloud operations wait for valid SDK auth and
profile restoration. These tests do not establish a universal login fix.
