# Resolved dependency security gate

GitHub Dependency Review fails before inspecting this private repository because
its current GitHub plan lacks the required Code Security entitlement. The build
now uses a standard-library OSV API client over Gradle's **resolved** Maven graph;
it does not skip security scanning or depend on paid GitHub features.

## Run

```sh
python3 -m unittest discover -s tools/security -p 'test_*.py' -v
./gradlew --no-configure-on-demand --no-configuration-cache \
  -I tools/security/dependency-inventory.init.gradle \
  exportSecurityInventory :build-logic:exportSecurityInventory \
  -Pinclude-telemetry -Penable-updater --max-workers=2
python3 tools/security/scan_dependencies.py \
  build/reports/security/dependencies.json \
  gradle/build-logic/build/reports/security/dependencies.json \
  --output build/reports/security/osv-results.json
```

The init script registers opt-in tasks, resolves each project's classpaths in its
own task (Gradle 9 requires owning-project locks), and combines configuration
provenance. It includes debug/release compile/runtime, unit/instrumentation test
classpaths where configured, other JVM/Android variants, annotation processors,
root/subproject buildscript classpaths and the included build-logic dependency
graph. Required application debug/release configurations must be present.
Application dependency/version/signing configuration is not changed.

The client queries selected `group:artifact:version` coordinates, not declared
version ranges or just the version catalog. Direct and transitive versions are
checked together; configuration membership is retained. Batches are bounded at
100, pagination is followed with a bound and repeated-token rejection, and HTTP
retries are bounded. Missing inventory, unresolved dependencies, empty/malformed
responses, API errors and scan failures return nonzero. **Any reported OSV
vulnerability blocks the gate**, including test/build dependencies. There are no
severity exceptions, ignored IDs, always-success exits, or baseline waivers.
The generated JSON reports must be retained even when the gate fails.

OSV receives public Maven coordinates only, never source, credentials, user data
or chapter content. Its lack of a finding is not proof of universal safety:
unpublished/forked coordinates, bundled native binaries, JDK/Android SDK/toolchain
vulnerabilities, malicious packages, licenses and unknown vulnerabilities need
separate review. First-party project nodes are recorded rather than sent as
Maven packages. Generated Gradle version-catalog accessor jars are local build
outputs, not separately published Maven components.

## References and licensing

- [Gradle resolution results](https://docs.gradle.org/current/userguide/graph_resolution.html)
- [Gradle owning-project resolution](https://docs.gradle.org/current/userguide/viewing_debugging_dependencies.html#sub:resolving-unsafe-configuration-resolution-errors)
- [OSV querybatch API and pagination](https://google.github.io/osv.dev/post-v1-querybatch/)
- [OSV supported manifests and resolved-version guidance](https://google.github.io/osv-scanner/supported-languages-and-lockfiles/)
- [GitHub Dependency Review availability](https://docs.github.com/en/code-security/supply-chain-security/understanding-your-software-supply-chain/about-dependency-review)

The exporter and client are original project code. No scanner library, plugin,
binary or third-party source is added to Android or copied into this repository.
They use the existing Gradle runtime and Python standard library. Vulnerability
metadata comes from the public OSV service; reports preserve advisory identifiers.
This gate does not change branch protections or enable release publication.

## Review policy

The PR uses this supported alternative to the unsupported internal Dependency
Review step. The existing `Build & Test App` status identity, unit tests and
migration checks remain. Formatting uses the separately owner-approved strict
exact-content legacy policy documented in `docs/stabilization/formatting-policy.md`;
the ordinary full Spotless check remains visibly non-clean. No branch protection or
required-check setting is changed. A successful OSV response is not a successful
run of GitHub Dependency Review. The actual status requirements must be inspected
under repository security policy before a merge; this is not permission to bypass
another failing gate or to publish a release.
