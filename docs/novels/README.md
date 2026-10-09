# Mangaro Novels — private local prototype

Development entry: Home drawer → الروايات, visible only in Debug. Production versionName/versionCode, manga sources, publisher, image reader, downloads, account/XP/achievement logic and global startup are unchanged. Networking and storage start only after entering Novels.

## Implementation

`NovelSource` is independent from Mihon SourceManager/CatalogueSource. Four compiled adapters are written against October 2026 live public pages. HTTPS/domain allowlisting, no inherited cookies/auth headers, cancellable requests (maximum two concurrently), bounded responses, natural Arabic failures and explicit original-site fallback. Cenele uses its ordinary public read-only chapter-list AJAX and public page nonce, not account cookies. Sunovels parses public Next.js flight data as JSON for cover URLs, never executes it. SeaNovel searches its available public 223-record catalogue; it does not claim a global full-site scan.

## Unified catalogue and edition identity

Home/search/library are unified. Four lazy adapters stream results independently with a shared two-request bound; one failure leaves other results usable. Search is debounced/cancelled; provider catalogue pages remain on demand. Genres use actual source filters or explicitly limited, verified local metadata. Latest updates are shown only from inspected KolNovel/Cenele ordering. No comparable global popularity ranking is invented. Provider attribution and original-page actions remain accessible in edition information and credits.

Canonical matching is conservative: exact normalized Arabic/Latin names and aliases, compatible qualifier tokens, known author agreement, or actual original titles/corroborating descriptions. Different authors, sequels and distinct records within one provider never collapse merely through a bridge match. A sparse card cannot erase richer metadata. Persistent canonical aliases preserve merged work identities. Source namespace + actual work URL remains the stable edition identity; chapter URL remains its identity. No integer chapter-title inference.

The primary edition uses a complete unique public-link index, then tested access, metadata quality, reliable update time and a deterministic key. Advertised totals and response speed are not selection signals. Public linked counts do **not** prove every individual chapter text is available: representative first/last text samples are checked for competing editions; access restrictions can still occur later. They fail honestly, with explicit alternate-edition choice. Translations are never mixed. Legacy library JSON/settings remain in place; grouping is a non-destructive mapping. Resume positions and completed files stay attached to their original edition even after canonical preference changes.

## Complete chapter indexes and genuine volumes

Cenele's 50 is a transport page size, not a chapter limit. Each real volume uses its own `has_more` cursor. Declared counts never determine the end. Unnamed groups are kept unnamed, not invented volumes. Sunovels follows its zero-based HTML pagination until the inspected final page. KolNovel and SeaNovel provide full lists in one response. An incremental, cancellable collector deduplicates actual URLs, detects repeated/empty/cyclic pages and retains last-known-good complete indexes when a refresh fails. No chapter text is fetched to build indexes. Each genuine volume count is calculated from actual unique chapters. Sources without volumes use clearly labelled numerical navigation ranges above 100 chapters. Lazy rows avoid thousands of simultaneous UI items.

Indexes: five memory entries, disposable 16 MiB disk LRU, protected saved/offline indexes in `filesDir/novels-local/indexes`. Catalogue response cache: sixteen pages/5 minutes; HTTP metadata: eight responses/2 million characters/2 minutes. Discovered metadata registry is bounded to 1,500 unprotected works plus current/saved/history/download editions. Covers use 8 MiB memory and 16 MiB disk LRU. No global startup source fetching or source-wide catalogue crawl.

## Text reader and independent offline downloads

The paragraph-lazy Arabic reader keeps the packaged Noto Naskh Arabic font, optional system font, bold/italic markup, content-aware RTL, size/spacing/margins and dark/light/sepia. Settings/library/reading anchors remain separate from manga/XP/accounts. Reading position is idle-debounced and saved on stop/back with chapter URL, paragraph/offset and content anchor. Complete cached chapter lists enable previous/next across volume boundaries offline.

A dedicated `novels-local/downloads/queue.db` stores durable novel tasks, independent from manga SQL/queues. Single, selected, range, volume and multiple-volume actions share this queue. One active request per provider, two lanes overall, spacing between source requests, seven-minute worker batches. Pause/cancel increment a generation; stale responses cannot commit. Resume/retry are explicit; failed chapters never enter download loops. WorkManager recovers pending work after interruption without app navigation. Existing completed downloads are preserved on cancellation. A file committed before a process crash is validated and recovered without a duplicate fetch.

Chapter text files are checksummed, bounded gzip JSON under `novels-local/text-downloads/chapters/<edition hash>/<chapter hash>`. Atomic file replacement, fsync, content validation and edition ownership checks protect offline content. The reader opens a valid offline file before any HTTP call. Missing/corrupt completed files are marked for retry; no fake download status. Only abandoned `.part` files older than 24 hours in the dedicated temporary namespace are eligible for cancellable, repeatable maintenance. Active temporary files and completed chapters/library/history/settings are excluded. Download scheduling and maintenance start only on novel use, without idle/backoff conflicts. User-authorized local offline use is enabled; no third-party text is bundled or committed.

## Independent source updates

Local repository: `/home/jalem/Downloads/mangaro-novel-sources`. Four namespaced configurations, schema version 1 / compatible engines 1–2, signed selector envelope. Android pins a separate EC public key, validates signatures/SHA-256/domains/schema/selectors, atomically retains current/previous configurations and rejects invalid or older revisions. Algorithms/endpoints remain compiled; remote rules cannot execute code.

**PUBLIC_PIPELINE_APPROVED=false.** The independent update worker and scheduled GitHub Actions are prepared locally; no public repository, workflow or feed has been published/activated. Scheduled health probes report degradation and publishing requires review plus all real sample checks. No operational relationship to mangaro-source-rule-publisher. This is an explicit review/publication boundary, not a claim that live automatic updates already run.

## Verification

Focused tests: inspected catalogue pagination, source-domain isolation, stable identities/serialization, JSON flight parsing without execution, hidden-filler/container safety, signed-rule validation and tamper rejection. Opt-in `MANGARO_NOVEL_LIVE=1` verifies catalog/search/details/first chapter list/text on one live sample per source. Only titles/counts/URLs/hashes are recorded privately under /tmp; no third-party story fixtures are committed. Normal tests make no live requests. Opt-in `MANGARO_NOVEL_PAGE_PROBE=1` checks distinct real first/second catalogue pages for KolNovel and Cenele; next-link detection uses the inspected `.hpage a.r` and `a.next.page-numbers` structures.

Android's ICU regex parser is stricter than desktop Java; emulator inspection caught an unescaped CSS brace. It was escaped before the second validation pass. A later source inspection identified SeaNovel’s sr-only SEO paragraph, which was excluded from story extraction and covered by a focused test. Font/position persistence was observed across process restart. A write-order guard also prevents older queued slider saves from overwriting newer theme choices; that final guard received compile verification, not a third emulator pass. The oversized source synopsis was collapsed behind an explicit expansion action, with reading action above it.

Private emulator screenshots and source probes remain outside Git. No physical-phone commands, public APK, website deployment, mandatory metadata or official release changes are part of this task.

## Limitations

Four live sites can change or return intermittent 403s, notably SeaNovel. Restricted/removed chapters fail honestly with retry/original-site action; no CAPTCHA/sign-in/paywall bypass. Search/covers/metadata may be absent for individual works. Offline use has been authorized by the user for these four integrations. Public app/rule-feed publication remains outside this local task; provider availability and individual chapter restrictions are external limitations.

## Current focused verification

Pure checks cover conservative matching, conflicting authors/bridges, highest verified counts, migration positions, 3,000 synthetic chapters, special chapters, boundary duplicates, genuine volumes, cursor cancellation/resume, atomic offline files, corrupted files and temporary-file safety. SQLite protocol checks exercise the same durable SQL statements used by Android: restart recovery, pause/resume/cancel/retry, generation guards, completed-file preservation and uniqueness. Opt-in `MANGARO_UNIFIED_INDEX_LIVE=1` follows complete live indexes for four representative novels and one genuine multi-volume work; it records only counts and volume metadata privately under `/tmp`. No public content fixtures or pipeline deployment.

One explicit author transliteration alias is corroborated from the public Cenele record https://cenele.com/cont/revere-insanity/ (original Reverend Insanity, author غو زين رن) and SeaNovel https://seanovel.org/api/novel/novel-37 (القس المجنون, author Gu Zhen Ren). This is an exact curated equivalence, not arbitrary author transliteration. KolNovel’s inspected .alter supplies “Reverend Insanity | Master of GU”; pipe-separated original aliases are preserved for matching. Cenele’s “رواية” presentation prefix is ignored only in matching keys. Sunovels original title is scoped to .main-head h1, excluding the website’s navbar heading. Different known authors and fan/side-story qualifiers remain separate.
