# Mangaro Account Phase 1 deployment

Android wiring is implemented, but real sign-in requires your own Supabase/Google configuration.
No project, credentials or server deployment is assumed. The migration has not been applied to a live project.

## External setup

- Create/select a dedicated Supabase project. Apply `migrations/20261003211112_mangaro_account_phase1.sql` through its SQL editor or your existing Supabase migration process. This is server SQL, never an Android/Mihon migration. It creates public social profiles (no emails), ownership RLS and a new public `avatars` bucket. Review existing bucket/policy conflicts before applying to an existing project; no existing records are deleted.
- Enable **Google only** in Supabase Auth. Disable email/password, phone and anonymous signup. Configure a Google **Web application** OAuth client and consent screen. Store its client ID/secret exclusively in Supabase's Google provider configuration.
- In Google Cloud, register the Supabase provider callback shown by the dashboard (normally `https://<project-ref>.supabase.co/auth/v1/callback`). This is separate from the Android callback below.
- Supabase Auth redirect allow-list: `app.manhwaar.reader.auth://auth/callback` (Release) and `app.manhwaar.reader.dev.auth://auth/callback` (Debug). These exact app-specific schemes avoid Debug/Release callback collisions. No new Activity is used. PKCE verifier is required, expires after 15 minutes, and is encrypted/persisted before browser launch.
- Put only these client values in ignored `local.properties`, environment variables or Gradle properties:

```properties
SUPABASE_URL=https://YOUR_PROJECT_REF.supabase.co
SUPABASE_PUBLISHABLE_KEY=YOUR_PUBLISHABLE_CLIENT_KEY
```

Environment overrides Gradle properties, which override local.properties. A legacy **anon** JWT key is accepted for compatibility. Privileged secret/service-role keys are rejected by build configuration. Never put Google secrets or tokens in any of these values. The public client key intentionally goes into BuildConfig; RLS is the security boundary.

Missing/invalid configuration keeps Guest mode usable and disables Google login. After setting configuration, build the APK normally. Auth/session restoration never gates Home or local reading. Tokens/verifiers use AES-GCM Android Keystore encryption under `noBackupFilesDir`, not manga storage. Sign-out clears the cloud session only, including when the backend is offline; it does not clear manga data or downloads. Browser dismissal leaves Guest state with no fake success.

## Server security

- Profiles: public reads contain only user UUID, handle, display name, avatar paths/Google photo URL and timestamps. Email comes only from the signed-in owner's Auth session.
- Google signup trigger in an unexposed private schema creates a profile; repeat logins never overwrite it. Existing Google users are backfilled only when their row is absent. No client INSERT/DELETE privileges.
- Username: normalized lowercase Latin letters/digits/underscore, 3–24 characters, nullable until completion. Unique lower(username) index enforces case-insensitive conflicts. Display name is trimmed Unicode, max 80 characters.
- Authenticated users can UPDATE only username, display_name and avatar_path on their own row. Identity, Google photo and creation time are immutable; updated_at is server-maintained.
- Avatars: only `<auth.uid()>/avatar.jpg` can be written/deleted by that user. Bucket accepts JPEG up to 1 MiB. Android validates PNG/JPEG/WebP input up to 5 MiB, decodes bounded dimensions, scales to max 1024px and re-encodes JPEG. Custom path is stored; Google image is fallback. Timestamp URL version lets Coil refresh a replaced image without bypassing its cache.
- No XP/progression persistence, awards, Community backend or cloud Library sync in this phase. Domain XP=0/level=1 remains the initial presentation default, not client-editable server progression.

## Required deployment verification

After applying migration, verify with separate anon/user-A/user-B clients that public profiles contain no email, A cannot update B, protected columns cannot be updated, case variants of the same username conflict, and A cannot upload/delete B's avatar. Verify Google callback, cancellation, process-restart restoration and offline sign-out on your device. These checks cannot be claimed from compilation; no live backend/device checks were run here.
