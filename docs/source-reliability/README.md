# Mknov / SeaNovel reliability audit

Baseline: 42d32b13a31eea2bf5c618296ff35608388047a4. The existing 2.5.6 illustration release is independent; this task changes no release metadata and publishes no APK or mandatory policy.

## Verified initial findings

- Mknov is the native anonymous JSON adapter, source novel.mknov, API api.mknov.com/v1. No bridge supplies its reader text. Public chapter IDs20716 and20967 of work96 currently return predominantly Arabic text through getChapterBatch. Work96 is Shadow Slave, not Invincible (work108). Public website rendering and JSON content are different representations; historical website corruption does not establish corruption of the current Android API response.
- SeaNovel is the native public JSON-metadata/server-rendered-HTML adapter, novel.seanovel. The separate Vercel HMAC health attestation is not the Android reader and will not be changed. The public1979 Invincible reader itself contains extensive phonetic English transliteration in Arabic script; control1620 and Terra Nova chapter1 are readable in reviewed extracts. Length/paragraph checks alone accept all three. A parser cannot reconstruct missing translation.
- Mknov's current chapter endpoint checks response ID and access but omits the indexed chapter-number check and does not honor an explicitly restricted index before requesting content. Its membership LRU can be consulted after eviction rather than retaining immutable request-owned index evidence.
- SeaNovel's canonical check uses Jsoup absUrl, unlike the stricter existing Cenele check. Raw canonical URL credentials/ambiguous routes need deterministic regression coverage before correction.
- Existing durable checksums establish byte integrity, not linguistic quality. Shared content validation must run at source, repository and storage boundaries for these two sources only. Corrupt payloads must be retained on disk until an atomic validated replacement; no progress or library deletion.
- Mknov public catalog reports1463 works. A bounded views-sorted traversal yielded1463 rows /1462 unique IDs; one duplicate across mutable pages means completeness cannot be asserted. SeaNovel catalog and search snapshots each expose223 distinct slugs, not proof of every work on the website.

## Design before implementation

Reuse both adapters, NovelHttp, repository single-flight loading and NovelDownloadDisk atomic writes. Add a pure local content-quality assessment with explicit verdict/reason/counts, no network or stored prose. Strong replacement-character/script-pollution signals and conservatively calibrated phonetic-density evidence are diagnostics, not language understanding or probability estimates. Preserve quotations, proper names, prose illustrations, and unknown short/multilingual text. A severe quality failure gets an Arabic typed error and is never marked a successful new download. No automatic retry of a deterministic quality failure. Source-side corruption is not silently replaced by another edition.

Keep exact source/work/chapter IDs. Retain immutable numeric/access binding from Mknov's public index through body validation. Canonical Sea URLs must preserve and validate the raw identity before DOM URL normalization. Revalidate restored two-source payloads without deleting original files; healthy old files retain their checksum/schema/ownership.

A bounded opt-in host health harness must exercise production Kotlin adapters; reports contain URLs, IDs, hashes, counts and reasons only. Existing four-source signed feeds, keys, schedules, Sea attestation, unrelated dirty monitoring worktree, Android ads, content-type classification, search, auth and website remain untouched. No production publication.

## Mknov identity/access checkpoint

Three new production-adapter tests failed on the original source and pass after the fix: response chapter-number mismatch; conflicting returned work_id; and explicit index restriction before any body fetch. The full MknovSourceTest suite now passes16/16. Index snapshots retain a local immutable binding (numeric chapter label, published availability, source URL); LRU eviction cannot replace that request-owned evidence. Existing zero/decimal ordering, irregular IDs, count checks, response access checks, endpoint allowlists and edition URLs are preserved. No title-specific exception or extra per-chapter metadata request was introduced. API responses without work_id still require the requested chapter ID and numeric label to match the work-owned index.
