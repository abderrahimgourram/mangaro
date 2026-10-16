# SmartLink V3 — private acceptance

Publisher-supplied SmartLink ID: **31593088**. URL: `https://asiafilm.org/4/71f2337020da186c3958a071da292bec`.

Approval provenance: the publisher reports an explicit account-specific exception under Adsterra support case **#481902**. This was NOT independently verified and is NOT general network permission. Adsterra's public [publisher requirements](https://adsterra.com/blog/set-up-publishers-dashboard/) ordinarily prohibit incentivized traffic. Preserve the actual account correspondence for compliance review before production activation; this implementation does not contact the support account or certify approval.

## Activation and existing systems

Default production configuration keeps V3 OFF. The local signed private R8 acceptance uses both `-PsunovelsPrivateAcceptance=true -PsmartlinkV3PrivateAcceptance=true`, versionCode 32. Existing InMobi/CMP settings and SDK remain; the V3 private reader/download surfaces substitute explicit SmartLink actions for their old InMobi placements. Refused advertising preferences are respected. No website JavaScript, WebView, tracking SDK, provider HTTP probe or analytics event was added. No URL parameters or account identifiers are appended. The supplied destination is opened only by a clearly labeled **فتح رابط إعلاني** button, in an external HTTPS browser.

## Selected download batches

The existing manga details/updates and novel details/reader entry points provide deduplicated stable chapter identities. Completed/queued entries are excluded; retry/reorder of an already-running operation retains existing queue semantics. Selection is frozen before waiting on the browser, so later reader navigation cannot download another chapter instead.

A batch key is SHA-256 over length-prefixed owner namespace and sorted exact selection, never a title or maximum chapter number. The configurable default is `ceil(unique selected chapters / 25)`, clamped to **1–6** actions. Each existing batch retains its original requirement after configuration changes. Successful unlock also retains owner-scoped hashed chapter coverage: retries of an incomplete subset do not demand advertising again. Mixed selections charge only for genuinely new, not previously opened chapters. This is distinct from source access authorization. Progress is `returnedActions / required`; advertising unavailability is a separate fallback reason, never invented completed steps. The original callback enqueues the original selection only after local unlock or an explicit/unavailability/privacy fallback. The queue/storage/content-access checks are unchanged. Dismissing the sheet never cancels or deletes downloads.

The RTL sheet discloses the external ad, reports navigation steps accurately, offers retry/unavailable fallback and never equates returning with an impression, view, conversion or verified advertiser event. A permanently unavailable ad-state file fails open for downloading with a visible explanation; it is not overwritten with fabricated successful counts.

## Return evidence and persistence

SmartLinkJournal serializes all mutations through one mutex, saves a candidate atomically, and only then emits reactive state. Android storage is an AtomicFile under noBackupFilesDir/smartlink-v3/state.json, with explicit fsync and byte verification. Unknown/corrupt schema is preserved and fails safely. No database migration or download-format change occurs.

A token must have all three facts: explicit successful startActivity of an external browser, initiating Activity actually stopped (not a configuration-change stop), and return to the application under the same account namespace. Duplicate resumes and racing callbacks settle once. The launching operation belongs to the application controller scope, so disposal of the initiating screen cannot cancel confirmation. An away-before-confirm callback ordering is supported. One pending external navigation is allowed device-wide.

Completed steps, requirements, fallback and pending-away evidence survive recreation/restart. If Android kills the process before evidence is durably confirmed, no speculative step is awarded. Activity recreation can restore the sheet/progress, but its old executable enqueue callback is intentionally NOT serialized. Re-select the same chapters after recreation/cold restart to reattach the original queue action; completed steps are reused. There is no automatic background enqueue of an unconfirmed selection. A true split-screen browser that never stops the initiating Activity may not earn a step; unavailable fallback remains accessible. These are conservative local-navigation semantics, not proof of destination loading.

Owner namespaces derive from existing LocalAccountScope file ownership, including independent guest generations; only hashes and ad bookkeeping are persisted. Auth/workspace transitions cancel outstanding tokens. Another account cannot advance, dismiss or bypass the previous account's batch. No personal library, progress, bookmark, cloud or download record is modified by the ad journal.

## Chapter boundaries and device frequency

Novel and manga/manhwa completion ledgers are separate, account-scoped and persistent. Every twentieth distinct eligible completion exposes one optional boundary, not an automatic browser launch. Manga completion reuses the existing selected-last-page/readiness guard and source + work URL + chapter URL; already-read manga and incognito sessions do not generate fresh completions. Novel completion requires verified rendered content, finished position restoration, and the final story block reaching the viewport end. Existing historical novel positions without V3 evidence are conservatively suppressed; an old position is not proof of a new completion. V3-observed unfinished chapters can resume without losing eligibility. Reopening or toggling already-completed chapters cannot inflate counts. Leaving/dismissing a boundary prevents it reappearing as a new opportunity.

Novel boundary content is a separate footer AFTER story blocks; paragraph keys/anchors and rendering algorithms remain unchanged. Manga/manhwa uses the existing completed-chapter transition footer with a normal bounded button, no scroll/touch interception, overlays or Next-button redirection. Next/Previous remain independent. The user can always continue reading without the ad.

Initial global device protections: **3-second launch debounce and 24 accepted external launches per rolling hour**, shared across accounts and both placement types; there is no four-hour cooldown. Local clock anomalies fail conservatively. The counter records only accepted browser launches, not impressions. Destination errors/authentication/cookies remain the browser's responsibility; no completion, purchase or click inside the advertiser page is requested or automated.

## Validation and remaining acceptance

Host tests exercise real journal concurrency and real local file serialization/atomic replacement using seeded events. They cover distinct selection, fixed/clamped requirements, duplicated/reordered returns, configuration recreation, process restoration, write failure, offline fallback, device limits, chapter uniqueness, kind separation, account isolation, boundary dismissal and navigation transparency. Existing logout/download ownership/ghost-removal/Smart Focus/catalog/cloud tests are retained. These are deterministic host fixtures, not Android lifecycle or live ad verification. No SmartLink was visited by automated tooling. No ADB, emulator, installation or publication is performed.

Final exact test/build/signature/checksum results and changed-file inventory are saved alongside the private APK outside Git. The previous verified GitHub checkpoint remains unchanged; this task does not push.

Manual acceptance: install over the current Release without clearing data; select 50 genuinely pending chapters and verify 0/2; open each labeled link explicitly and return; verify no double counting on rotations/repeated resumes, then enqueue. Recreate/restart and re-select the identical batch: reuse steps. Test offline/no-browser/unavailable fallback without deleting existing files. Check A → guest → B → A isolation. Read 20 genuinely new chapters independently in novels and manga/manhwa, verify optional footer only, back-and-forth deduplication and uninterrupted Next/Previous. Verify browser-specific split-screen behavior and destination safety manually; send no credentials or story text in diagnostics.

Follow-up: [2026-10-16 strict download gate and durable reader-transition repair](SMARTLINK_GATE_REPAIR_20261016.md) supersedes the original optional/fail-open gate behavior described above.
