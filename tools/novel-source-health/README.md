# Bounded Mknov / SeaNovel health checks

These tools are independent of the signed four-source CSS-rules publisher and its schedules. They neither activate rules nor change source eligibility, accounts, release versions, or the website. Nothing installs an app or opens an advertisement.

Offline contracts (no live samples):

```sh
python3 tools/novel-source-health/check.py --output /tmp/mangaro-health.json
python3 -m unittest discover -s tools/novel-source-health -p 'test_*.py'
```

Explicit production-adapter observation (needs the project's ordinary SDK/JDK/Gradle setup, no Android device):

```sh
python3 tools/novel-source-health/check.py --live --output /tmp/mangaro-health.json
```

The reviewed default plan contains six public source-qualified works per provider. The Kotlin harness uses the real shipping adapters and shared disk validation, serial pacing of at least one second, host allowlists, no cookies/authentication, a110-request/ten-minute ceiling, ordinary public ownership checks, and synthetic temporary offline storage. It samples first/early/middle/latest/index-position boundaries, and decimal IDs when present. A declared complete index is not proof every chapter body works. HTTP access/rate barriers stop further requests to that host. Cancellation is not turned into a provider parser failure. No chapter bodies or headers enter reports.

The command runs deterministic contracts before live requests. The test task is forced to execute and stale/missing output cannot be treated as fresh health evidence. Exit0 means only the explicitly scoped sample/contract check passed; exit2 means documented upstream/contract limitations; exit1 means test/infrastructure failure. A successful JUnit observation test does not mean all sampled chapter responses passed—inspect the separate JSON outcomes.

Metadata-only public inventory, explicit invocation, at most25 serial requests/three minutes and2,000 Mknov rows:

```sh
python3 tools/novel-source-health/inventory.py --output /tmp/mangaro-inventory.json
```

It never calls a chapter body or full chapter-index endpoint. Dynamic views-sorted page duplicates, changing totals, budget limits and HTTP errors produce incomplete evidence rather than false completeness. SeaNovel counts refer to the accessible catalog snapshot. Responses have HTTPS/endpoint/redirect/proxy/type/size/time restrictions. Reports are atomically written. No automatic retry/crawl is performed by this metadata tool.

No new schedule, public alert, bridge, secret, signed feed or deployment was enabled. Use manually or in a separately reviewed read-only private CI environment. Do not rerun live observations to manufacture PASS, and do not infer Android rendering, every chapter's readability, or translation quality from these checks. `NovelContentQuality.READABLE` means no strong configured corruption signal; UNKNOWN stays distinct and can include valid short notes or unusual multilingual text.
