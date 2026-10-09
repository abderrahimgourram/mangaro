# Private novel sync — approval pending

Target from the Android Release configuration: **pehsxthjetlltlsqcbfa**
(`https://pehsxthjetlltlsqcbfa.supabase.co`). Never use `fofo-game`.

The available Supabase connector does not expose Mangaro. Its live schema, deployed
phase-4 RPCs and policies could therefore **not** be verified in this task.

Review `20261009030234_novel_private_text_state.sql` against the live Mangaro
schema before authorizing its application. It depends on
`20261003234522_mangaro_local_first_cloud_sync.sql` and preserves its five
manhwa table branches, owner lock, server revision trigger and RPC contract.
It adds only private `cloud_novel_state`, `cloud_novel_progress`, restricted
validation functions, owner RLS and `cloud_novel_capabilities()` schema version 1.
No chapter text, download files, public showcase, XP or auth changes are included.

**Required approval:** apply this reviewed additive migration to Mangaro only,
then verify owner/guest/cross-account RLS, revision conflicts, tombstones and
capability/pull/apply RPCs. Do not apply it to another project or merely publish
an APK and assume the backend is ready.

The client probes capability using the existing signed-in Supabase client.
Until the extension is available, novel changes remain locally durable and
pending; manhwa sync continues through its existing five tables. Shared account
status explicitly reports unavailable novel sync. Per-field conflicting edits
remain queued until the user chooses local/cloud changes. Downloads remain
untouched, including when a library record is removed.
