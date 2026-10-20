# PR #4 quality-gate recovery (2026-10-20)

Starting head: `5b66c0aea59294bfe65ef172ead0e2e245303268` on
`fix/mangaro-autonomous-stabilization-20261020`. Initial worktree was clean.
The private repository's default is `integration/search-foundation`, still at the
immutable recovery commit `0c9f95b565b0ae5aca2343430128eaaf6f5eb025`.
PR #4 targets the existing polish feature branch, not that integration branch.
PRs #1–#3 remain independent draft dependencies; no automatic batch merge is safe.

## Four historical failures: stale contracts, not ownership bypasses

Read-only history inspection identified commit `84cb0bd35` as the earlier change
that updated the shared configured URL, removed historical skipped-page debt,
and removed the hourly launch quota. Its tests were not updated alongside it.
The immutable recovery contains those production changes and the stale tests.
Earlier archived recovery execution reproduced all four failures (39 tests,
35 passed, four failed). No advertising implementation changed in this repair.

- `ReaderAdActionTest.readyOwnerCanUseTheExistingBrowserLaunchFlowImmediately`
  expected the superseded URL. Keep the exact current URL assertion and shared
  configuration equality; account readiness/owner checks remain intact.
- `SmartLinkBrowserRecoveryTest.everyAndroidPlacementUsesTheSameConfiguredLink`
  had the same stale literal. Keep both exact destination assertions.
- `ReaderAdPagesTest.jumpingTo100RequiresEveryUnfulfilledPrecedingPageAndRestartKeepsPaidSteps`
  repeated four times over an index contract that now returns just the destination
  boundary, causing a null dereference after the first fulfilled step. The corrected
  test asserts the exact 80→81 boundary for chapter 100, one earned persisted step,
  and a still-required distinct 20→21 boundary when returning to chapter 22. The
  separate sequential test continues to assert every boundary through chapter 141.
- `SmartLinkV3Test.deviceRateCapAndShortDebounceApplyAcrossAccountsWithoutFourHourCooldown`
  asserted an hourly quota that no longer exists. Its replacement preserves
  cross-account short-debounce denial, post-debounce eligibility, bounded timestamp
  retention, repeated legitimate steps and later eligibility. No production quota,
  gate, timestamp, browser navigation, account guard or advertisement was changed.

Initial corrected combined run: 39/39 passed, zero skipped. Subsequent compiler,
isolated/order/full-suite verification is recorded in the final local report.
These host tests never launch a browser or make live advertising requests.

## Security check

Run `39459298394` failed in Dependency Review before testing dependencies:
`Dependency review is not supported on this repository. Please ensure that
Dependency graph is enabled along with GitHub Advanced Security`.
The branch-protection API separately returns HTTP 403 requiring a paid plan or
public visibility. No purchase, visibility change or protection bypass was made.

The PR proposes an alternative gate using the supported OSV API over actual resolved Gradle
versions, including transitives and the included build-logic classpath. See
[the scan contract](../../tools/security/README.md). It rejects incomplete graphs,
API failures, malformed/paginated incomplete responses, and all reported findings.
The original scan found 15 package/advisory pairs (14 advisory IDs) across 869
versions; this was genuine vulnerability evidence, distinct from GitHub's
entitlement failure. The corrected graph has 866 versions across 146 configuration
inventories and no reported findings as of the recorded scan.

| Dependency | Before | Corrected minimum / selected tool version |
| --- | --- | --- |
| jsoup | 1.23.1 | 1.23.2 |
| Kotlin Gradle/compiler/Compose plugins | 2.4.10 | 2.4.21 |
| Guava in telemetry's independent graph | 31.1-android | 32.0.1-android |
| Wire in baseline-profile test graph | 5.2.1 | 6.4.5 |
| Commons Lang build classpath | 3.16.0 | 3.18.0 |
| jose4j build classpath | 0.9.5 | 0.9.6 |
| JDOM build classpath | 2.0.6 | 2.0.6.1 |
| Bouncy Castle build modules | 1.79 | 1.85 (aligned provider/PKIX/util) |

Existing libraries are updated/constrained; no scanner dependency is bundled in
Android. The selected versions are published releases, not invented versions or
EAP compiler upgrades. POM licenses remain MIT, Apache-2.0, JDOM's permissive
license or the existing Bouncy Castle license. No security ignore list is added.
The OSV database does not prove safety of unpublished forks, native bundled code,
SDKs, malicious packages or unknown vulnerabilities; those remain review limits.

Primary references:
- [jsoup patch notes](https://jsoup.org/news/release-1.23.2)
- [jsoup advisory](https://github.com/advisories/GHSA-65r4-943x-97jj)
- [Kotlin advisory](https://github.com/advisories/GHSA-r937-wjx7-w2jp)
- [Kotlin stable release history](https://kotlinlang.org/docs/releases.html)
- [OSV querybatch API](https://google.github.io/osv.dev/post-v1-querybatch/)

## Formatting and protected files

Two targeted structural violations are corrected without changing behavior:
`app/build.gradle.kts` mixed Boolean condition grouping and two adjacent KDocs in
`HttpSource.kt` (move the existing repair helper ahead of the original method's
KDoc). The source-api formatter now passes. Modified test files use explicit
imports rather than wildcard imports.

The full existing `spotlessCheck` remains mandatory and unchanged. It also
covers inherited formatting debt in unrelated code. A private temporary copy of
unchanged `SmartLinkPolicy.kt`, checked using the same Spotless 8.9.0 / ktlint
1.8.0 and `.editorconfig`, fails `standard:max-line-length` at line 250. The copy
and protected production file have identical SHA-256. No formatter was applied
to the original. Making the full gate green would require editing protected
advertising files or changing the check's scope/rules. Neither is authorized by
this task. There is no baseline exclusion, ratchet, suppression, fake-success
fallback or repository-wide formatting application in this repair.

## Historical Supabase session: native-session and Apps failures distinguished

The configured native server is enabled with exactly the Mangaro project ref and
`read_only=true`, no custom credential headers. The active host Codex process
started at 10:26:55 local, before the previous report; this is not a newly started
host process. Native Supabase tool names are absent from the current registry;
native resource lookup returns `unknown MCP server 'supabase'`.

The separately exposed Supabase Apps tools reject both `list_tables` and the
strictly read-only `SELECT current_user, current_setting('transaction_read_only')`
with `MCP error -32600: You do not have permission to perform this action`.
The unauthenticated service endpoint is reachable (expected HTTP 401).
This proves neither a failed owner OAuth login nor successful project access.
That earlier session could not inspect deployed schema or verify account membership.
The resumed native connection below supersedes this access blocker.
No credentials were read out, requested, reset or copied. No personal rows were
queried, no migration executed, and no production data/RLS changed.

The existing [metadata-only diagnosis proposal](cloud-readonly-diagnosis-and-proposal.md)
remains applicable. Production historical completeness and sync remain partially
verified; local account isolation and truthful partial-count behavior must not be
replaced with guessed totals or a bypass of ownership checks.


## Resumed native connection and verification

After restarting the host, native Supabase tools became available and read-only
project verification succeeded. The earlier Apps errors above remain accurate for
that earlier session. See the updated [deployed-contract findings](cloud-readonly-diagnosis-and-proposal.md).
The root-project verification completed: 1,249 tests, 1,235 passed, zero failures,
14 optional skips; SQLDelight migration verification also passed. The four fixture
classes passed separately (3/12/7/17 cases) and together in explicit class-name
order (39/39). No production advertising source was changed.

The interrupted AssertionError was a faulty regex-based comparison of quoted
substrings in Gradle Kotlin DSL: a character literal containing a double quote
caused it to consume surrounding code as a string. The Kotlin compiler's lexer
was used for the corrected comparison. Imports are compared as a set; whitespace,
comments, optional semicolons/trailing commas, the reviewed equivalent Boolean
grouping and single-call DSL branch braces are normalized. All 3,668 remaining
body tokens match. Negative controls reject changed application IDs, altered
Boolean guards and added calls. The assertion was not silently removed.

The earlier estimate of 556 formatting violations mistakenly counted serialized
Spotless lint-cache markers. The corrected canonical-output comparison recorded
258 differing files before changed-file cleanup. That is a snapshot, not an exact
remaining-violation count. The real full check reported five failing tasks; current
changed-file cleanup also fixes the app Gradle task, leaving inherited Kotlin
formatting debt in app, domain, data and core/common. No check scope was narrowed.

## Final compatibility and additional evidence

Release Kotlin/R8 verification and the updated baseline-profile Wire consumer
compiled successfully with the revised dependency graph:

```sh
./gradlew :app:minifyReleaseWithR8 :baseline-profile:compileBenchmarkReleaseKotlin \
  -Pinclude-telemetry -Penable-updater -Dorg.gradle.jvmargs=-Xmx3g --max-workers=2
```

This does not assemble or publish an APK. Existing optional Android window/XMLutil
warnings were retained; no R8 suppression or signing/version change was introduced.
The earlier five disposable upgrade scenarios and native reader/profile/story
screenshots belong to the preceding QA source, not a newly built artifact with
this dependency stack. Physical-device acceptance remains pending.

Each of the four formerly failing methods also passed alone using the original
AGP `:app:testDebugUnitTest --tests <fully-qualified-method>` task, zero skips.
A temporary custom-task probe failed during configuration because AGP had not yet
registered `testDebugUnitTest` in its `afterEvaluate` callback. That helper was
not used as evidence of product failure or success; the actual AGP task replaced
it. Individual classes, explicit ordered combined classes, the four isolated
methods and the full project run are distinct recorded checks.

GitHub run `39494251451` on source head `4e6f082f4` completed the scanner's tests,
resolved inventory export and OSV queries successfully. Its downloaded private
artifact contains 866 selected Maven versions, 146 configuration inventories and
zero matching advisories. That is independent CI evidence, not a claim that the
unsupported Dependency Review action succeeded. The complete formatting gate
remains unwaived, and PR #4 remains draft/unmerged while it is not green.

## Advisory/version review

The public OSV records were reviewed for the actual affected coordinate and range.
The original 15 package/advisory matches represent 14 distinct advisory IDs.
These are minimum fixed versions from the relevant records, not title/string
similarity matching or declared-version guesses:

| Package | Advisory | Relevant fixed bound / selected replacement |
| --- | --- | --- |
| jsoup | GHSA-65r4-943x-97jj | 1.23.2 / 1.23.2 |
| Kotlin Gradle plugin | GHSA-r937-wjx7-w2jp | 2.4.20-Beta1 / stable 2.4.21 |
| Guava | GHSA-5mg8-w23w-74h3; GHSA-7g45-4rm6-3mm3 | 32.0.0-android / 32.0.1-android |
| Wire runtime/runtime-jvm | GHSA-7xpr-hc2w-34m9; GHSA-9rm7-3qhh-h2mc | 6.3.0; 6.4.5 / 6.4.5 |
| Commons Lang | GHSA-j288-q9x7-2f5v | 3.18.0 / 3.18.0 |
| jose4j | GHSA-3677-xxcr-wjqv | 0.9.6 / 0.9.6 |
| JDOM2 | GHSA-2363-cqg2-863c | 2.0.6.1 / 2.0.6.1 |
| Bouncy Castle provider | GHSA-574f-3g2m-x479; GHSA-9pwp-9qqc-pr26; GHSA-c3fc-8qff-9hwx; GHSA-qp49-qgx5-5m26 | coordinate-specific ranges reviewed; selected 1.85 |
| Bouncy Castle PKIX | GHSA-wg6q-6289-32hp | 1.84 / aligned 1.85 |

Provider/PKIX/util remain aligned. The original graph, full advisory records and
corrected local/CI graph inventories are retained in private diagnostic evidence.
The scanner delegates exact ecosystem-version matching to OSV and fails closed
on request/response errors; it does not implement a misleading generic lexical
version comparator. No public/source artifact contains personal cloud records.

## Completed CI outcome and bounded live parser probe

Run `39494251451` finished FAILURE at `:data:spotlessKotlinCheck`.
The scanner steps passed; CI unit tests, migrations and build were consequently
skipped, not passed. Local complete-project tests and R8 verification above are
independent evidence. No status/check requirement was removed to bypass this.

An opt-in live parser run passed Mknov's work/index/first-chapter acceptance but
failed the short-chapter test when an identity-negative request returned upstream
HTTP 520 before parsing. A single independent public control later returned 200,
and rerunning the **unchanged** short-chapter test passed all its readable/identity/
quality/offline controls. Both outcomes are retained; no expected error was widened
and no parser/source production code was changed. This is bounded sampled evidence,
not comprehensive catalog verification or a guarantee against provider outages.

Native read-only inspection identifies a real server compatibility proposal that
still needs nonproduction DDL/RLS/old-client validation and separate production
approval. It was deliberately not installed by a private code push. Historical
cloud completeness remains partial despite the now-working MCP connection.
