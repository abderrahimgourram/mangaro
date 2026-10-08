# Mangaro Novels — private local prototype

Development entry: Home drawer → الروايات, visible only in Debug. Production versionName/versionCode, manga sources, publisher, image reader, downloads, account/XP/achievement logic and global startup are unchanged. Networking and storage start only after entering Novels.

## Implementation

`NovelSource` is independent from Mihon SourceManager/CatalogueSource. Four compiled adapters are written against October 2026 live public pages. HTTPS/domain allowlisting, no inherited cookies/auth headers, cancellable requests (maximum two concurrently), bounded responses, natural Arabic failures and explicit original-site fallback. Cenele uses its ordinary public read-only chapter-list AJAX and public page nonce, not account cookies. Sunovels parses public Next.js flight data as JSON for cover URLs, never executes it. SeaNovel searches its available public 223-record catalogue; it does not claim a global full-site scan.

Catalog paging is on demand. Latest/popular controls are exposed only for inspected KolNovel/Cenele query modes; Sunovels uses its real library ordering without invented sort parameters, and SeaNovel exposes its available catalogue. Cenele supports volume metadata and 50-chapter pages; Sunovels maps page 1 to the site's zero-based page 0. Stable chapter identities are actual URLs, never guessed integer titles. KolNovel ordering follows the real reversed source list. Hidden website filler is excluded using ordinary visibility rules; no body-wide chapter fallback.

Native paragraph-lazy Arabic reader: locally packaged Noto Naskh Arabic, bold/italic markup, content-aware direction, font/line/paragraph/margin controls, dark/light/sepia, chapter list and previous/next actions. Settings/library/reading anchors live in `filesDir/novels-local` via AtomicFile. Novel text stays in a bounded two-chapter memory LRU; it is not bundled, persisted for offline distribution or sent to XP/achievements. Reading positions are idle-debounced and saved on stop/back, with stable chapter URL, paragraph/offset and a content hash anchor. Device-local prototype state is independent of accounts; no silent cloud merge is attempted.

Memory cover cache: 8 MiB, separate disk cover LRU: 16 MiB, metadata cache: at most 8 responses/2 million characters/2 minutes. Image networking uses its own no-cookie client and the four approved domains. No eager global novel initialization or source-wide scans.

## Independent source updates

Local repository: `/home/jalem/Downloads/mangaro-novel-sources`. Four namespaced configurations, schema/engine version 1, signed selector envelope. Android pins a separate EC public key, validates signatures/SHA-256/domains/schema/selectors, atomically retains current/previous configurations and rejects invalid or older revisions. Algorithms/endpoints remain compiled; remote rules cannot execute code.

**PUBLIC_PIPELINE_APPROVED=false.** The independent update worker and scheduled GitHub Actions are prepared locally; no public repository, workflow or feed has been published/activated. Scheduled health probes report degradation and publishing requires review plus all real sample checks. No operational relationship to mangaro-source-rule-publisher. This is an explicit review/publication boundary, not a claim that live automatic updates already run.

## Verification

Focused tests: inspected catalogue pagination, source-domain isolation, stable identities/serialization, JSON flight parsing without execution, hidden-filler/container safety, signed-rule validation and tamper rejection. Opt-in `MANGARO_NOVEL_LIVE=1` verifies catalog/search/details/first chapter list/text on one live sample per source. Only titles/counts/URLs/hashes are recorded privately under /tmp; no third-party story fixtures are committed. Normal tests make no live requests. Opt-in `MANGARO_NOVEL_PAGE_PROBE=1` checks distinct real first/second catalogue pages for KolNovel and Cenele; next-link detection uses the inspected `.hpage a.r` and `a.next.page-numbers` structures.

Android's ICU regex parser is stricter than desktop Java; emulator inspection caught an unescaped CSS brace. It was escaped before the second validation pass. A later source inspection identified SeaNovel’s sr-only SEO paragraph, which was excluded from story extraction and covered by a focused test. Font/position persistence was observed across process restart. A write-order guard also prevents older queued slider saves from overwriting newer theme choices; that final guard received compile verification, not a third emulator pass. The oversized source synopsis was collapsed behind an explicit expansion action, with reading action above it.

Private emulator screenshots and source probes remain outside Git. No physical-phone commands, public APK, website deployment, mandatory metadata or official release changes are part of this task.

## Limitations

Four live sites can change or return intermittent 403s, notably SeaNovel. Restricted/removed chapters fail honestly with retry/original-site action; no CAPTCHA/sign-in/paywall bypass. Search/covers/metadata may be absent for individual works. No offline chapter copying is enabled without authorization. Commercial/public content distribution and pipeline publication await the owner's arrangements and review.
