# Exact-content legacy formatting policy

Approved by the owner on 2026-10-20 for private integration. This supersedes the
previous full-clean merge requirement, **not** the formatter rules. Spotless
8.9.0, ktlint 1.8.0, `.editorconfig`, targets and formatter steps are unchanged.

## Blocking gate

Run `python3 tools/formatting/check_baseline.py`. CI checks out complete history.
The gate first verifies the approved `bd0e8b575dd00460cf376295a9c4313f7181ea39`
inventory, ancestry, original formatter configuration and each frozen SHA-256.
It then runs the original `spotlessCheck`, including build logic, with an init
script that excludes **only exact, individually validated legacy file versions**.
The init script independently repeats preflight; failure is fatal. No source is
formatted or rewritten by this check. There are no directory exemptions.

The initial baseline contains 538 files: 525 non-advertising and 13 protected
advertising implementations. All match the reviewed inventory and anchor bytes.
New files receive no exemption. Non-baseline files receive no exemption even
when unchanged. Modified legacy files fail preflight, including advertising code.

To modify a legacy file, format the entire file with the original rules and
explicitly remove its baseline entry in the same reviewed change. Spotless then
checks it normally. Remove entries when deleting files as well. A retired entry
cannot be reintroduced, and a committed deletion followed by recreation cannot
reuse its old exemption, even when the replacement has identical bytes.
Neither updating a hash to bless changed bytes nor adding new exemptions is
accepted. Baseline maintenance is deliberate; there is no generation/update mode
in the validator. Every removal must have a nonempty reason in the manifest's
`retirements` map, and be reviewed in the PR description and commit.

Formatter configuration changes invalidate the baseline. A future rule change
requires a separately reviewed policy/inventory migration; updating manifest
hashes alone does not pass. Additional `.editorconfig` overrides are rejected.
This policy, init script, manifest and workflow are security-sensitive review
surfaces; as with any CI implementation, a deliberate edit to the policy itself
must be reviewed rather than treated as an ordinary baseline refresh.

## Visible debt, not a claimed full-clean result

The ordinary `./gradlew spotlessCheck` **still fails** on the 538 inherited files.
It remains available unchanged for full-debt remediation. The exact paths,
recovery comparisons and findings are in `pr4-formatting-inventory.md`.
CI separately publishes the remaining count/list and labels it as full-repository
debt, alongside the blocking baseline-aware result. A green incremental gate
does not claim that full Spotless passed or that this debt was repaired.

## Verification and preservation

`python3 -m unittest discover -s tools/formatting -p 'test_*.py' -v` exercises
content changes, clean-file retirement, new files, forbidden additions, hash
rewrites, deletion/recreation, retirement/reintroduction, configuration mutation,
nested overrides, symlinks and advertising changes using disposable Git repos.
New-file clean/dirty behavior is additionally verified against actual Spotless
in the integration evidence, not solely a mocked lint function.

Security audits of resolved dependencies, compiler/unit tests, SQLDelight and
release optimization remain required. No advertising source bytes, source IDs,
reader state, user data, backend policies, signing or version configuration are
changed by this policy. The separate Mknov database validation branch is not an
Android integration dependency and no proposal is deployed to production.
