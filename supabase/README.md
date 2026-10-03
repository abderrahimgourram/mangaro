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

Direct anon/authenticated execution of `rls_auto_enable()` and trigger-only helpers is revoked; `ensure_rls` remains enabled. No Community backend, XP awards, cloud sync or local SQL migrations are added.

## Verification

Remote catalogs verified all columns, FK cascade, checks, index, RLS policies, bucket constraints, triggers/functions and their restricted grants. `tests/phase1_rls.sql` passed under actual anon/authenticated roles inside a rolled-back transaction: anonymous writes denied, owner updates allowed, cross-user writes/deletes denied, immutable fields protected, case conflict/invalid handles rejected, 40-character names enforced, signup metadata boundary fixed, and automatic RLS still working. Storage metadata fixtures emulate the Storage API transaction delete guard; no RLS was disabled and no real files were uploaded. All fixture users/profiles/objects and the probe table were rolled back. Security Advisor reported no findings. Public profiles/Auth HTTPS checks succeeded.

**Android OAuth runtime verification pending manual device test.** Compilation/static wiring does not prove Google account selection, callback or device Keystore/session behavior. Google test-user/consent restrictions remain external.

Manual checklist: open as Guest; tap Google; choose account; return to Mangaro; choose username; edit display name; upload avatar; reopen and verify session; sign out; verify local Library/history/downloads remain.
