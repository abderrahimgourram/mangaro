# Independent signed novel-source feed

**Activation is blocked:** GitHub-hosted SeaNovel catalog probes return HTTP 403. `PUBLIC_PIPELINE_APPROVED=false` is deliberately retained; the production feed has not been published. Signed public test fixtures passed the Android-pinned key and schema checks, but they are not a live update feed. Resolve normal provider access, then verify actual published assets before enabling the switch.

Repository: https://github.com/abderrahimgourram/mangaro-novel-sources

Feed: https://raw.githubusercontent.com/abderrahimgourram/mangaro-novel-sources/main/published/manifest.json

This is separate from manga rules and the application updater. The existing EC public key in `assets/novels/rules-public.der` remains pinned. A signed schema-1 manifest binds the engine, increasing revision, immutable envelope path and envelope SHA-256. The envelope independently signs the rules payload. Android verifies both signatures, hashes, exactly four source IDs/domains and bounded allowlisted selectors before touching current/previous files. No downloaded code executes.

The current novel engine accepts versions 1–2; published complete-index rules require engine 2. Structural changes still require compatible compiled adapters. Selector updates for enabled compatible clients do not require a new APK. Old APKs with the updater disabled cannot be remotely enabled by publishing this repository. No APK is built or released in pipeline activation.

Checks are lazy, scheduled only after opening Novels, once per day with a connected-network constraint. They use an isolated unauthenticated HTTP client, strict GitHub paths, no cookies/redirects, bounded responses and timeouts. Cancellation prevents installation of a cancelled candidate. Network or signature/schema failures preserve packaged and last-known-good rules. Scheduling failures are logged and cannot crash the novel entry.

Current and previous disk envelopes are atomic; the highest valid revision among disk and bundled candidates is selected. Downgrades are rejected. A server rollback is a reviewed *newer revision* carrying verified prior selectors, after fresh live health checks. Corrupt or incompatible candidates never overwrite the local good rules.

GitHub runs daily/manual health monitoring and read-only PR validation. Publication is manual/reviewed on main, after five-operation live checks for all four sources. The health report is bound to the exact candidate hash. Only then is the dedicated novel-rule signing secret accessed. Immutable uploaded release assets are downloaded and verified before the last-known-good Git feed advances. Previous signed revisions remain available. No chapter text, manga rules, APK release or website metadata is published by the pipeline.
