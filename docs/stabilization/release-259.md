# Mangaro 2.5.9 release review

PR15 retains source-qualified identities and the already accepted reversible alias
presentation repair. Version 2.5.9/code49 supersedes publicly downloaded
2.5.6/code48. All four existing ABI splits and Universal remain enabled.

## TeamX: external availability versus mandatory contracts

On 2026-10-26 the unchanged official `/series/?page=1` endpoint returned HTTP301
to a Discord invitation, ultimately HTTP200 non-work HTML. Earlier local and
GitHub checks returned HTTP502/timeouts. This is unavailable upstream content,
not proof of zero chapters or a search identity failure. No new host is assumed.

A new mandatory regression failed on the previous adapter: redirected HTML could
mutate saved metadata on a detail-only request. All production TeamX HTML response
entry points now reject non-HTTPS or external final response hosts before parsing.
Native work IDs and stored chapters are not rewritten. Recorded index/pagination,
parser, fallback and failure-retention tests remain in the mandatory JVM gate.

The six unchanged live assertions are tagged `teamx-live`. Default tests exclude
only this external tag; `:app:testDebugUnitTest -PteamxLiveOnly=true` executes them
with ordinary failing exit status and separate XML/HTML reports. CI runs both and
publishes the external outcome even on failure. External health is explicitly
non-blocking per the owner's release instruction; it is never described as a
source PASS or folded into deterministic passing totals. A deterministic parser,
identity, data or Release acceptance failure still blocks publication.

## Formatting configuration review

The fully formatted app build script changes only version metadata and external
health test selection/report paths. Original Spotless/ktlint/plugin versions,
editor settings, targets and steps are unchanged. A separate pinned configuration
snapshot is reviewed for this release; the original legacy inventory/ancestry,
source hashes and irreversible exemption retirement remain anchored to their
original snapshot. Changes beyond that exact configuration snapshot fail closed.
The modified live-test file is fully formatted and its exemption is retired.

## Publication gates

Private exact-head CI, signed optimized Release acceptance on emulator-5556,
original certificate, preserved synthetic state, ABI contents, and actual public
artifact verification remain required. TeamX stays explicitly unavailable until a
live check succeeds. No mandatory-update minimum increase is authorized.
