# Phase 2 — Fadaa/Cenele access diagnosis

Source: `novel.cenele`, native `CeneleSource` in NovelSources.kt. No adapter,
source identity, endpoint, chapter URL, parser, cache or downloaded data changed.

## Symptom -> evidence -> disposition

Reported: source stopped working. Five anonymous HTTPS requests, serial with
1.2-second pacing, 20-second timeout and 2 MB observation bound, official host
only, no cookies/credentials/proxies or challenge bypass. Date: 2026-10-18.

| Stage | Public route | HTTP | Observation |
|---|---|---:|---|
| Home | / | 200 | HTML |
| Catalog | /cont/?m_orderby=views | 403 | Access denied |
| Search | /?s=<الخالد المرتد>&post_type=wp-manga&paged=1 | 200 | Two legitimate result cover links |
| Details | /cont/rene-immortal/ | 403 | Access denied |
| Chapter | /cont/rene-immortal/<book-1>/<chapter-1>/ | 403 | Access denied |

The text sample is the same existing public route in FadaaPublicChapterProbeTest,
not a guessed chapter or substituted novel. No chapter body was received or saved.
The search response still matches `.tab-thumb a`; HTTP 403 precedes details/index
configuration parsing and CeneleChapterContent. Therefore no new parser defect or
provider-wide outage is established. GitHub/host access is not Android acceptance.

Exact cause established: these requests were rejected upstream on catalog/details/
reader routes. The reason for rejection (access policy, challenge, temporary issue)
is unknown; no evidence of deletion, permission to bypass, or a safe local repair.
No further retries were used to manufacture success. The production NovelHttp
retains HTTP_403 with its status; it does not treat it as an empty successful index
or pass an error response to prose extraction. Existing canonical/work route checks
and retryable parse versus public-unavailable distinctions remain intact.

## Acceptance

Public search remains accessible from the observation host. Public details/index/
prose acceptance is BLOCKED BY UPSTREAM ACCESS; source repair is NOT claimed.
The program must not call a final 2.5.5 candidate accepted on this evidence.
Deterministic source/index/parser/download/format tests are recorded separately
in the program report after execution. No production probe of accounts or ads.

Next owner acceptance: verify site availability normally, then legitimate in-app
details/index and actual prose for a known permitted work. If access recovers and
a parser failure remains, capture sanitized response structure and fix that exact
contract. Existing cached/downloaded files and reading progress are untouched.

Normal debug compilation and 70 selected test cases completed: **67 passed, zero
failed, three explicitly opt-in live tests skipped**, 121.93 seconds. Filters:
CeneleChapterContentTest, NovelIndexRecoveryTest, NovelIndexRetryRecoveryTest,
NovelDownloadSourceRegressionTest, NovelContentTypePresentationTest, NovelSourceTest,
NovelReaderScopeTest. No test contacts production unless explicitly enabled.
Wrong chapter/work identities, transient errors, 403/404/429, parser errors,
checkpoint continuation, cached downloaded content and comic exclusion covered
by deterministic fixtures. This does not substitute for blocked live prose acceptance.
