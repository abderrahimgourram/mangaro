# Provider SDK removal — private code41

Removed the advertising SDK and its CMP dependencies, manifest activity, provider
configuration/IDs, consent integration, remote video configuration fetch, embedded
native slots and legacy SDK lifecycle/capping code. Removed provider-specific
keep rules. Retired the SDK-only policy/ownership tests along with their removed
implementation; retained generic chapter-boundary and readiness coverage.

Kept general-purpose AndroidX/Gson runtime dependencies for compatibility with
existing Android and extension APIs. These do not initialize an advertising SDK.
Historical documentation/Git history is not rewritten.

SmartLink URL/ID, approval reference, per-work 20-chapter reader-page journal,
validated external browser launch/return and download unlock batches remain.
The purple reader button change from the preceding worktree is preserved.
OnceAction moved without changing its atomic exactly-once semantics; native SDK
fallback download advertising is gone. No download storage/queue or reader
progress format changes. No source, auth/cloud, community or workflow edits.

SmartLink no longer calls a provider controller. Its external-link preference
uses a neutral setting; a read-only legacy preference lookup preserves explicit
opt-outs. This compatibility key is not an SDK dependency, consent string or
network request. No existing preferences or user files are deleted. The optional
privacy dialog now describes only explicit external browser links. Home no longer
initializes a CMP.

Host verification: 84 selected tests, 0 failures,
0 errors, 0 skipped. Includes four new external-link
privacy/exactly-once tests and existing SmartLink reader/download/registry checks.
First compile exposed a retired native-content parameter at a transition caller;
the unused SDK slot was removed from both sides and the complete focused run passed.
No live ads, ADB or emulator tests. Phone acceptance remains manual.

Release delivery verifies signed ARM64 code41, existing certificate, package,
non-debuggable flag, ZIP integrity, shrinking and absence of provider SDK/CMP
classes and manifest components. Exact build/artifact results are recorded in the
private APK report, rather than inferred from host tests.
