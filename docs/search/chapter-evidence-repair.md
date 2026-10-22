# Search chapter evidence and source reliability repair

Baseline: `5280d007e2d0531e8f58813fd01fa6d8c602b367` (private integration, PR #9).

The search selector already counted reconciled chapters rather than the largest
chapter label. The production gap was acquisition: new comic editions received
only work metadata, and novel enrichment likewise never advanced their chapter
indexes. Thus correct selector fixtures did not prove that new search results
had usable comparison data.

## Evidence and selection

`MangaSearchEvidence` now verifies competing editions through their existing
adapters, after the existing identity resolver establishes their presentation
group. Unrelated equal titles do not trigger list crawling. A query permits four
serial list checks, each with a ten-second deadline, including a three-second
representative chapter/image-prefix sample. The image check reads at most a small prefix (with a byte-range request and a
1.5-second transport deadline), closes the response and checks image magic bytes;
it consumes only the prefix and saves no image. Servers may ignore Range and
send additional bytes before the response is closed. It does not reconcile SQL
chapters, change the library, read chapters or award XP. Positive evidence is
cached for five minutes; failed checks for thirty seconds, with source-qualified
routes and metadata fingerprints. Cached editions do not starve later providers
of the next query's bounded budget. Cancellation cannot publish into a new query.

The existing novel repository performs at most two index pages per competing
edition, four editions per query, with an eight-second caller deadline. It reuses
persistent index recovery and coalescing, never chapter text downloads. A partial
count remains a lower bound. A partial/failed response cannot erase a retained
complete index. Unknown counts remain null; a confirmed zero requires a complete
response and explicit zero total. Advertised counts and chapter-number labels
are never substitutes for distinct chapter identities. Shared counting preserves
separate remote IDs and suffix routes; an ambiguous ID-less duplicate cannot join
two verified parts.

`ChapterListIntegrity` now retains the digest and completeness of a successful
SQL index across a failed refresh. The latest fetch still reports FAILED: this
does not claim that request succeeded. Old snapshots without this proof remain
lower bounds. Home gives a proven unreadable edition lower priority, and reader
access evidence expires. Equal-count selection no longer depends on which
request completed last. The existing source chooser retains alternatives; no
stored works, chapter IDs or reading data are consolidated.

## Source contract and medium boundaries

TeamX publishes its total as `قائمة الفصول (N)` in an h5 heading. The previous
adapter read only a data attribute and therefore degraded a fully traversed list.
It now reads the actual declared total, rejects conflicting declarations and
checks the parsed unique list against it. Recorded, sanitized Duke index fields
cover all four pages and 148 actual chapter links. Fixture files contain no
reader bodies, account data, cookies, tokens or advertising HTML.

MangaTime search format metadata is retained independently of title aliases and
existing work IDs. Explicit prose and comic formats cannot share a search group,
even before the other edition has format metadata. Existing novel payload
classification and its visible UNKNOWN state remain intact. No saved-library
filter or reader replacement is introduced.

## Connected Stories

The five reviewed adaptation relationships remain unchanged. A renamed
MangaTime route can resolve through its adapter-validated, source-qualified server
ID, without using the slug as proof. Novel alternative enrollment requires an
exact trusted external ID shared with an already reviewed novel edition, with
no conflicting authority. Heuristic catalog membership, title or synopsis
similarity alone cannot authorize it. Both the details entry gate and production
repository use the same evidence. Existing exact on-demand target metadata
loading still works without a prior search-cache hit; no chapter is opened.

## Live evidence and limits (2026-10-22)

Actual adapters returned complete unique lists and a usable page-list sample:
TeamX Duke 148 and Fast Break 15; MangaTime Duke 147 and Omniscient Reader 15;
Azora Duke 148. Actual Solo Leveling and Omniscient Reader work metadata resolved
both directions. These numbers are observations, not hardcoded production rules.
The original no-chapter screenshot's selected source is not known and that
failure was not reproduced on those live sources. MangaLek returned HTTP 403;
its live accessibility is unresolved and no restriction was bypassed. Candidate
editions without identity evidence intentionally remain separate.

Run the full application tests normally. Opt-in live evidence:

```sh
MANGARO_SOURCE_REPAIR_LIVE=true MANGARO_CONNECTED_STORIES_LIVE=true \
./gradlew :app:testDebugUnitTest --tests '*SearchSourceLiveContractTest' \
  --tests '*ConnectedStoryLiveMetadataTest' --max-workers=2
```

Modified legacy Kotlin files are individually retired from the formatting
baseline and fully checked with the original rules. No ad implementation,
Supabase proposal/schema, source ID, XP rule, signing or release configuration
is changed. Public release remains out of scope.
