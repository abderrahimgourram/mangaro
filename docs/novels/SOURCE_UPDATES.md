# Independent signed novel-source feed

## Current state — 2026-10-13

The public production feed is now
[`published/v2/manifest.json`](https://raw.githubusercontent.com/abderrahimgourram/mangaro-novel-sources/main/published/v2/manifest.json):
signed schema 2 / engine 3 / PARTIAL revision 4. Its actual manifest/envelope bytes
were fetched, pinned-key verified and matched the source repository's published
artifacts. It includes Kolnovel/Cenele/SeaNovel replacements over the trusted
four-source packaged baseline. Sunovels' existing availability restriction remains.
No selector, signed artifact, key, activation flag or approval gate was changed.

The current client already has `PUBLIC_PIPELINE_APPROVED=true`, supports engines
1–3, prefers v2 with v1 fallback only for 404/410, and preserves both signature,
hash, monotonic revision and whole-source validation. Lazy checks begin after
entering Novels on the existing six-hour schedule. Automatic publication/recovery
remains disabled in the publisher infrastructure.

SeaNovel now uses validated public catalog/search-index metadata, normalized
Arabic/original-title/author search, stable retained pagination snapshots, real
update times and versioned WebP covers. Its full embedded chapter array must match
the official total and ascending unique IDs. Sea-only parser cache keys trigger
revalidation of obsolete metadata without changing edition/chapter identity or
deleting offline files. Canonical chapter ownership and interrupted reader HTML
are checked before accepting new text. Existing sanitation/download storage remains.

Direct requests from this development machine succeeded; no Android runtime or
device access was tested. The Vercel HMAC endpoint is an observational health
checker, not an Android content transport. Locally prepared checker improvements
were not deployed. See `SEANOVEL_REPAIR.md` in the sibling `mangaro-novel-sources`
worktree for evidence, reference review, verification limits and manual acceptance.

## Historical pre-publication notes

The following describes the earlier disabled/schema-1-only state. The current
state above supersedes its activation, engine and scheduling statements.

**Activation is blocked:** GitHub-hosted SeaNovel catalog probes return HTTP 403. `PUBLIC_PIPELINE_APPROVED=false` is deliberately retained; the production feed has not been published. Signed public test fixtures passed the Android-pinned key and schema checks, but they are not a live update feed. Resolve normal provider access, then verify actual published assets before enabling the switch.

Repository: https://github.com/abderrahimgourram/mangaro-novel-sources

Feed: https://raw.githubusercontent.com/abderrahimgourram/mangaro-novel-sources/main/published/manifest.json

This is separate from manga rules and the application updater. The existing EC public key in `assets/novels/rules-public.der` remains pinned. A signed schema-1 manifest binds the engine, increasing revision, immutable envelope path and envelope SHA-256. The envelope independently signs the rules payload. Android verifies both signatures, hashes, exactly four source IDs/domains and bounded allowlisted selectors before touching current/previous files. No downloaded code executes.

The current novel engine accepts versions 1–2; published complete-index rules require engine 2. Structural changes still require compatible compiled adapters. Selector updates for enabled compatible clients do not require a new APK. Old APKs with the updater disabled cannot be remotely enabled by publishing this repository. No APK is built or released in pipeline activation.

Checks are lazy, scheduled only after opening Novels, once per day with a connected-network constraint. They use an isolated unauthenticated HTTP client, strict GitHub paths, no cookies/redirects, bounded responses and timeouts. Cancellation prevents installation of a cancelled candidate. Network or signature/schema failures preserve packaged and last-known-good rules. Scheduling failures are logged and cannot crash the novel entry.

Current and previous disk envelopes are atomic; the highest valid revision among disk and bundled candidates is selected. Downgrades are rejected. A server rollback is a reviewed *newer revision* carrying verified prior selectors, after fresh live health checks. Corrupt or incompatible candidates never overwrite the local good rules.

GitHub runs daily/manual health monitoring and read-only PR validation. Publication is manual/reviewed on main, after five-operation live checks for all four sources. The health report is bound to the exact candidate hash. Only then is the dedicated novel-rule signing secret accessed. Immutable uploaded release assets are downloaded and verified before the last-known-good Git feed advances. Previous signed revisions remain available. No chapter text, manga rules, APK release or website metadata is published by the pipeline.
