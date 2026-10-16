# Sunovels self-healing reader — engineering handoff

Implemented in the current `feature/adsterra-production` worktree, HEAD `a3cddc54fe2d69e6d37ac91d2ba88c4134d45902`. Existing dirty work was preserved. Validation is host JVM, static source and Debug compilation only. No device, emulator, ADB, Release build, push, deployment, workflow edit or infrastructure removal occurred.

**What is proven, and what remains unknown**

The owner confirmed that the failing selection was Resume. `NovelScreens.kt` passes `current.novel` and `current.position.chapter` directly to reader entry. Resume retains its own saved edition and full URL; it does not select a chapter by title, order or the current index. `NovelIdentity.kt` reconciles works without rewriting source URLs or saved reading positions. `NovelModels.kt` makes chapter ID the full URL. Therefore a descriptive saved title cannot determine whether this phone requested `/0`, `/1`, another address, or another saved edition.

The packaged 1,461-work bootstrap and the accepted metadata backup contain **no Birth chapter rows and a null Birth chapterCount**. Neither contains `ولادة نوح`. Three bounded public GETs on 2026-10-14 verified the source-backed first two published URLs: the index, `/0` and `/1` all returned HTTP 200. Headers are `0 الفصل` and `1 الفصل`; neither reader response contains `ولادة نوح`. The index declares 2336. The responses were 284,495, 111,932 and 163,806 bytes respectively. See `sunovels-self-healing-evidence/public-requests.json` for exact URLs, timings and body hashes. No complete 47-page crawl was performed.

The current count UI shows `index.chapters.size` for a complete snapshot, otherwise loaded rows against `index.declaredTotal ?: novel.chapterCount`. There is **no total-minus-one calculation**. A complete saved snapshot with 2335 rows can display 2335; a saved reported total can also affect an incomplete snapshot. The seed cannot supply that number. Which saved phone value did so remains unconfirmed. Index parser key v6 now treats old snapshots as partial, retains their rows, and obtains the current declared count through the existing bounded two-page refresh.

The exact phone root cause is **not proven**: its stored URL, index snapshot and failure-stage evidence are unavailable. We reproduced a strict title defect with a synthetic saved `ولادة نوح` row at a known source-backed `/0`; this is a regression fixture, **not a claim about the phone URL**. Before this patch `parseChapter` rejected `record.title != chapter.title`. That branch emitted the verification message with `SUN_READER_DOCUMENT`, rather than the exact reported unavailable message. The latter exists in shared `HtmlNovelSource.text` for a missing selected container and `extractNovelContent` for empty/non-letter story text; Cenele also uses it. Sunovels checked its container before the shared helper, so an empty sanitized container is the applicable direct extraction branch. A saved edition from another source remains another diagnostic possibility, not an established explanation.

**Execution path and forensic hypothesis results**

Resume/Start → saved/full-URL chapter → reader preflight → memory/checksum disk → source ownership/access gate → coalesced direct GET → status/final URL/type/length checks → public Flight owner and chapter-number proof → explicit source lock → validated HTML/Flight extraction → text validation → atomic reader evidence and existing memory cache → reader UI. Downloads use the same repository text path and existing two-lane queue, atomic files and checksums.

| Hypothesis | Recorded evidence and disposition |
|---|---|
| Title/number/owner mismatch | Strict saved-title equality was a confirmed code defect. Generic/descriptive title and generic header word order are now reconciled only after matching owner, chapter number and URL. Wrong numbers and different descriptive identities still fail. |
| Zero/count mapping | Live first page includes `/0`; declared total is 2336. Seed has no chapter mapping/count. Full URL identities and BigDecimal comparison remain; no Double deduplication or URL translation. Exact phone count origin is unresolved. |
| Non-p containers | Shared extractor already handles div/span/br. Missing rendered containers previously failed; fallback now reads only the verified unlocked record's public, byte-length-framed Flight content, with an explicit reader-render marker and complete document. It never scans arbitrary page body or arbitrary Flight text. |
| Negative cache | Prior HTTP 404/410 could persist TRANSPORT/MISSING and ordinary preflight refused it. Legacy transport negatives are now retry candidates, never readable proof. New parser/transport negatives remain ephemeral diagnostics. Proven INDEX/MISSING and PUBLIC_READER locks remain protected. |
| Concurrent indexing/reader | Prior evidence writes copied an earlier index snapshot, and manual revalidation had a separate flight. Updated index mutations use the current memory snapshot, overlay independent reader evidence on index writes, and reject older response epochs. Reader/revalidation GETs share one source flight. These are source-level protections, not a device race reproduction. |
| Hidden filtering | Visible attribution and story text remain unchanged. Existing hidden `p.d-none` removal passes regressions. A hidden whole reader container is now rejected. No evidence that visible story was stripped in the fresh samples. |
| Locks/throttling/transport | Fresh `/0` and `/1` returned 200; no live 429/503 was observed in this task. Existing `/675` and Shadow fixtures contain explicit source locks. Deterministic 403/404/429/503, redirect, interrupted-stream and timeout injections verify recovery classification. |

**Fixes and safeguards**

`SunovelsResolution.kt` supplies typed ephemeral outcomes/provenance and cancellation-aware per-URL coalescing. The persisted six access states and JSON model schema are unchanged. A successful resolution returns its own immutable report and epoch to the repository, avoiding a lookup of another asynchronous response's proof. Only newly verified readable content or an actual source-verified lock is written as durable reader evidence. Failure paths retain last-good body/proof; Retry respects cooldown rather than promoting an empty or unavailable row to readable.

Normal uncached open without redirects performs one chapter GET; the existing transport caps redirects at five requests. A stale numeric label triggers one refresh of its stored index page and requires the exact published URL. A cosmetic subtitle difference attempts that same single page after reader verification. No address is guessed and no additional pages are searched. Failure of this optional metadata repair preserves the already verified reader body. Initial index loading stays bounded to two pages; incremental checkpoints/fingerprints remain unchanged. The v6 parser key refreshes metadata without deleting old rows, progress or files.

Transport uses existing NovelHttp/OkHttp/semaphores: two source slots, four global slots, 1.5-second source start spacing, 8 MiB response cap, existing deadlines and exponential backoff. No automatic retry storm is added. Retry-After and explicit URL cooldown remain enforced. Final URL must resolve to the exact same novel/chapter route; unrelated host redirects are refused before a request. Content type and complete byte length are checked. Div/br extraction reuses the shared sanitizer, preserving visible attribution and formatting; public Flight fallback is capped at 1,000,000 bytes per text root and never executes JavaScript. Locks are checked before extraction. `/675`, Shadow and ambiguous Reverend 1153/1154 remain protected, including when later index metadata is incomplete.

Existing checksum-validated downloads and previously verified memory content remain the first path. Text LRU stays at two entries/1,000,000 characters; no duplicate persistent cache or network stack was introduced. Diagnostics and transport observations each retain at most 32 entries and hold no story bodies. The host replay retained 4161 diagnostic characters at completion; measured host heap was 125779360 bytes under a 384 MiB cap. These are host measurements, not Android memory/performance measurements. The local search mean was 0.343 ms. The replay used 24 baseline requests and 17 failure-injection requests, all source-only; two concurrent reader/revalidation requests made one GET, and targeted stale-row repair made two GETs total. The task's real-source budget was exactly three requests, without retries or full catalog scraping.

Debug-only `تشخيص الفصل` is available in the reader, preflight failure dialog and beside Resume. Reports show the caller's exact saved ID/title, current extracted title/number, requested/final URLs, status, read byte count, extraction strategy/counts, classification/provenance, attempts, cooldown and validated offline/memory status. Before a request, unknown fields remain unknown. Error responses may show zero read body bytes because their bodies are not extracted. Reports contain no body, cookies, auth headers or private account identifiers. Resume diagnostics also expose a saved non-Sunovels edition without changing that source. Preflight Retry now retries that exact selection; it does not require resetting progress or restarting a full index.

**Migration and preservation evidence**

No Room migration exists or was added. Generated SQLDelight schema remains version 16, with migrations 1–15. Novel queue SQLiteOpenHelper stays version 1. Novel metadata/index/library remain existing atomic JSON; chapter downloads remain existing checksum files. `NovelModels.kt`, `NovelIdentity.kt`, `NovelMetadataStore.kt`, downloader/queue/disk files and SeaNovel are byte-identical to the files captured at task start. Existing dirty SQL changes predate this task and were preserved. Index parser key v6 is a cache freshness marker, not a schema migration. IDs, bookmark sets, paragraph/offset positions and legacy JSON round trips pass contracts. Release activation flags, signed revision 4, bootstrap asset/receipt, Supabase, InMobi, Workers, D1 and workflows were not edited.

**Validation and artifact**

- Host contracts: **127 PASS**, preserving all original 83. Includes public p/div/br/Flight, descriptive/generic title variants, blank/truncated markup, explicit locks, foreign/nonchapter redirects, 403/404/429/503 and Retry-After, timeout after valid load, one-page stale identity repair, concurrent reader/revalidation, interrupted indexing, false legacy lock revalidation, real lock/ambiguity fixtures and synthetic saved Resume/count regression.
- Host runtime: 43083 ms. Repository persistence/UI wiring has static guards and Debug compilation; no Android runtime tests or real phone concurrency success are claimed.
- `git diff --check`: PASS.
- `./gradlew :app:assembleDebug --console=plain`: **PASS, 34 seconds**. Existing warnings remain.
- Static integration and APK inspection: PASS; ARM64 split, `.dev` app ID, seed integrity, and new resolver/diagnostic markers present in DEX.
- APK: `/home/jalem/Downloads/ManhwaAR_V0.2_Mihon_Fork_Kit/ManhwaAR_V0.2_Kit/ManhwaAR_Android/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`
- APK bytes: 85713055
- SHA-256: `9a11690bd29ba491785f5eb7ad3316e113f958dddc0f02a58cf00f6c329c90c4`

Reproduce contracts with `python3 tools/sunovels-direct/run-contracts.py --fixtures /tmp/mangaro-sunovels-v2-audit/public --output /tmp/mangaro-sunovels-repeat`. These are retained public fixtures from the preceding bounded audit; failure variations are generated deterministically by the runner/contracts. The gateway is unavailable by construction: replay refuses every non-Sunovels host, and static guards verify Debug always selects direct. This proves host/source independence; the owner still must accept Android behavior.

**Exact changed files in this task**

- `app/src/main/java/eu/kanade/presentation/novels/NovelReaderScreen.kt`
- `app/src/main/java/eu/kanade/presentation/novels/NovelScreens.kt`
- `app/src/main/java/eu/kanade/presentation/novels/SunovelsReaderEntry.kt`
- `app/src/main/java/eu/kanade/tachiyomi/novels/NovelHttp.kt`
- `app/src/main/java/eu/kanade/tachiyomi/novels/NovelRepository.kt`
- `app/src/main/java/eu/kanade/tachiyomi/novels/SunovelsAccess.kt`
- `app/src/main/java/eu/kanade/tachiyomi/novels/SunovelsRequestPolicy.kt`
- `app/src/main/java/eu/kanade/tachiyomi/novels/SunovelsResolution.kt`
- `app/src/main/java/eu/kanade/tachiyomi/novels/SunovelsSource.kt`
- `tools/sunovels-direct/SourceContract.kt`
- `tools/sunovels-direct/run-contracts.py`
- `tools/sunovels-direct/validate-integration.py`
- `docs/novels/SUNOVELS_SELF_HEALING_READER.md` (this report)
- `docs/novels/sunovels-self-healing-evidence/artifact-validation.json`
- `docs/novels/sunovels-self-healing-evidence/build-result.json`
- `docs/novels/sunovels-self-healing-evidence/build.log`
- `docs/novels/sunovels-self-healing-evidence/change-inventory.json`
- `docs/novels/sunovels-self-healing-evidence/host-contracts.json`
- `docs/novels/sunovels-self-healing-evidence/public-requests.json`

**Manual Android acceptance**

1. Update the existing Debug `.dev` installation using this ARM64 APK, preserving app data. Do not uninstall, clear storage or reset progress. If updating requires removing the existing installation, stop and retain its data.
2. Before pressing Resume on Birth, use the adjacent `تشخيص الفصل` action and Copy Diagnostic Report. Record selected source, full persisted chapter ID and displayed title; do not infer `/0` or `/1` from `ولادة نوح`.
3. Press Resume. Confirm existing bookmark and reading offset. If it fails, copy the report from the preflight dialog or reader. Inspect requested/final URL, failure code, HTTP status, parser strategy and cooldown. Retry that same selection after cooldown; capture both reports.
4. Refresh the index normally: confirm declared 2336 and explicit partial coverage; old rows, chapter zero and progress remain. Verify source-backed `/0`, `/30` and `/135` separately from the saved Resume identity.
5. Test known `/675` and Shadow: keep them locked. Reverend 1153/1154 must remain ambiguous/ineligible. `تحقق من الإتاحة` may verify a legacy false lock through one public GET; it must not unlock true restrictions.
6. Start an incremental index load, interrupt it by navigating away, and reopen a cached eligible chapter. Continue the index and confirm preserved rows/checkpoints.
7. Download several eligible chapters through the existing queue. Go offline and verify saved text, bookmarks and offsets; unavailable/empty chapters must not become successful downloads.
8. With the gateway unavailable, repeat direct discovery/search/details/reading/download checks. Never delete D1 for this test. Production activation and infrastructure removal remain subject to explicit owner acceptance.

Remaining limits: genuinely locked/challenged content remains unavailable; conflicting source addresses remain unresolved; source markup may change; incomplete indexes stay explicit. The new report is required to attribute the original phone's exact failure and count, and no physical-device success has been claimed.
