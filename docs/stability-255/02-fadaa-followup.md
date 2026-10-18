# Phase 2 follow-up — reference archive and real public source acceptance

## Reference and scope

Read-only static inspection of the owner-supplied APKS: package
`com.fadariwyat.cenele`, version 2.4.0/code33, Flutter application. Archive SHA-256:
`1de51e2466889a2f8510252619647bafc8ec3ffb6441a5e8a13d6c69c4931b66`.
Only endpoint names and public API schema were used. No application execution,
proprietary code reuse, credentials, signing keys or private API signatures.

Official namespace declarations `/wp-json/nhv/v1` and `/wp-json/cenele-app/v1`
returned public JSON schemas. Ordinary anonymous library/search/details/index
responses were structurally valid: sampled work ID15263 has the exact public
permalink `/cont/rene-immortal/`; index ownership novel_id15263, chapter IDs13942
and13943. API `type` describes nationality (Chinese/Korean/etc.), not comic format.
Separate structured `reader` fields describe text/webtoon. No classifier change.

Current app chapter API requires a signature: an anonymous request returned
HTTP403 with `chapter_signature_required`. No signing secret was extracted or used,
and Mangaro does not adopt that restricted API. Owner integration documentation
was requested only for any future authorized signed-API integration.

## Recovered ordinary HTML path and actual production proof

The earlier 403 is an observation, not a permanent site-removal diagnosis. One
controlled retry later returned HTTP200 on the original public HTML chapter URL.
The unchanged **production CeneleSource** then passed its explicit opt-in live
probe, using ordinary NovelHttp and existing parser (no reference-app credentials):

| Chapter | Paragraphs | Characters | Parsed text SHA-256 |
|---|---:|---:|---|
| الخالد المرتد 1 | 107 | 15899 | b9386e2008fc278fbe589e01c29f91cb0469923529e4b3a4e344bcb30c4feb3b |
| الخالد المرتد 2 | 42 | 5667 | 51d64e377ce5d266183b174e41e3b8a6b1cf33418304b39e977d49430d7a2c6c |

No chapter bodies were persisted. Two HTML source.chapter calls and repository
content validation passed. A separate new bounded production journey passed:
catalog10 works -> search2 results -> exact novel details -> public nonce/index
request -> first volume64 unique chapters, next cursor1001000001, declared total2092.
This is first-page continuation proof, **not full-index completeness acceptance**.
The same initial route now works without a source-code change; exact upstream
reason for transient rejection remains unknown. No parser bug was manufactured.

## Tests, limits and preservation

`MANGARO_FADAA_PUBLIC_PROBE=1` with `FadaaPublicChapterProbeTest`:
existing two-chapter test 1/1 passed; new discovery/details/index test 1/1 passed.
Normal debug compilation passed in both invocations (49.95s and61.25s).
Earlier 67 deterministic tests passed, three unrelated optional live tests skipped.

The investigation initiated 22 bounded logical public requests, including initial
controls, namespace/metadata observations, rejected signed-API probes and the
production reader/journey samples; transport-level redirect/retry counts were not
independently instrumented. No full novel download or source crawl.

Added only an opt-in real production journey regression test and this report.
All source implementations, source/chapter IDs, signed rules/key pinning, readers,
downloads, saved libraries, progress, comic exclusion and advertising unchanged.
Bundled Cenele selector values were independently inspected and match the compiled
fallbacks used by these probes. Unknown phone-cached remote rules remain an owner
acceptance item; no rule history/cache was deleted.

Verdict: current legitimate public source journey **host-verified**; intermittent
upstream access and physical UI acceptance remain limitations. A universal source
repair is not claimed. Do not use the signed API without official integration
credentials/contracts.
