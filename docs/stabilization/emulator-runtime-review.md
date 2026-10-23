
## Additional actual-runtime duplicates reviewed on emulator-5554

The original and Ragnarok comics remained separate works throughout this change.
Ragnarok's exact seven provider routes now have a **separate comic-only** reviewed
identity. Tappytoon's licensed comic identifies Sung Suho and credits JIN/Brix/Daul.
Azora post 1422 explicitly marks MANHWA; Swat 1702270 identifies the same Suho/Beru/
Itarim narrative and Jin; MangaTime ID 697df7af0c5d340ac1547225 supplies the Korean
original title, Daul/JIN/Brix credits and the same narrative. Dar and Hijala expose
Daul/Jin or Redice credits and Suho's awakening; TeamX exposes the same distinctive
Suho story. Starz's actual signed-app details and public page identify Suho, not
the original Jinwoo protagonist. This creates no novel/adaptation edge for Ragnarok.
No title-specific algorithm branch or inferred progress equivalence was added.

The seventh Duke result was MangaLek's exact route. Direct live fetching and the
emulator chapter refresh remain blocked/unavailable (HTTP 403 from the desktop).
Its publicly indexed provider page, crawled two months earlier, explicitly includes
the Korean original name, The Reborn Young Lord alternate name, Korean-manhwa type,
and Cyan Vert/brother-betrayal/second-childhood narrative, corroborated against the
licensed WEBTOON title 6107. This is sufficient edition-identity evidence, **not**
proof of current chapter availability. The route is retained as an alternative;
no cached index or historical chapter total is invented from the indexed page.

Identity-detail requests now prioritize unresolved editions ahead of established
reviewed/external identities within the bounded four-request budget. Known editions
can still refresh their metadata when budget remains; cache invalidation is retained. Separate chapter
verification still executes for competing established editions. Dar/Hijala now
retain explicit author/artist role rows bound to the exact requested host/path;
missing, contradictory, placeholder or redirected credits remain unknown.

A fresh emulator on 2026-10-23 also received MangaLek search stubs that the prior
session did not receive. Exact Solo/Ragnarok/ORV routes are reviewed against the
provider's indexed public pages (two-month-old crawl), aliases, distinctive story
premises and corresponding primary licensed publications. Solo identifies Jinwoo's
E-rank/double-dungeon awakening, Ragnarok identifies student Sung Suho, and ORV
identifies the sole novel reader entering the novel's changed world. Their types
are corroborated by the provider's webtoon/illustrated chapter routes. No claimed
current chapter counts are taken from this older index. These are explicit reviewed
edition entries, not a title-only fallback in the resolver. MangaLek remains an
unavailable alternative where current native chapter requests fail.

ORV now enrolls nine exact editions (eight providers, including two independently
identified MangaTime records). The signed-registry per-endpoint capacity is raised
from eight to sixteen, with a boundary regression accepting 9/16 and rejecting 17.
Evidence URL limits, URL validation, conflict guards and the runtime discovery/
request budgets are unchanged. A too-large enrollment previously rejected the
whole registry in tests; it was corrected before any candidate build or delivery.

## Final runtime boundaries found on 2026-10-23

The evidence owner now chooses its bounded frame **after** prioritizing unresolved
and uncached identities, retaining the first four displayed candidates for SQL
integrity hydration. At most sixteen SQL records, four identity requests and four
chapter-list samples are considered; known/cached prefixes cannot starve later
candidates. Actual ViewModel testing then exposed another pre-truncation in its
caller. Provider interleaving now passes all already-returned candidate metadata
to this existing budget owner instead of cutting twelve rows before prioritization.
The twenty-row regression and actual caller regression failed their old paths.

Actual Search UI ordering was correct internally while LazyGrid kept an earlier
card as its first-visible key and placed late exact Arabic matches above the
viewport. The captured failure and a manual return to the top distinguish this
from a relevance bug. The screen requests the next remeasure's logical top only
when it was genuinely at the top before the leading work/notice changed and no
user scroll is active. Scrolled views and pagination keep normal keyed anchoring.
See the official LazyGridState requestScrollToItem contract linked in
`tools/android/README.md`.

Two further actual ViewModel regressions failed before correction: cached
same-query validation briefly emptied a valid presentation, and partial source
refreshes shrank the visible grid while another provider was still loading.
Same-context refresh retains nonempty already-validated cards until completion.
An empty prior projection never blocks a healthy provider response behind another
pending source; the actual ViewModel retry regression failed before this guard.
Temporary errors retain only that context's prior source rows, re-resolved through
current identity rules, while the actual source status remains Error. Successful
empty responses replace the retained rows. Changed query/provider context receives
no retained rows. Error.toString excludes those models because local Manga fields
can include library state. There is no SQL consolidation or progress/XP operation.

The installed-app `tools/android/verify_search_viewport.py` drives the real Search
tab using accessibility on fixed emulator-5554. Its pre-fix run failed user-scroll
preservation. Live source timing can make the late-exact subcase intermittent;
manual failure screenshots, runtime ordering and automated reruns are reported
separately. The tool installs no UI overlay or synthetic source response and never
clears data, signs in or opens advertising. Evidence is saved outside Git.


## Exact-source local and real UI acceptance receipts

On 2026-10-23 the signed viewport candidate passed the installed-app Arabic
late-match/user-scroll acceptance tool (`viewport-after/receipt.json`). The same
user-scroll assertion failed on its predecessor. Actual production Search then
returned one primary for each reviewed original Solo, Ragnarok, ORV and Duke
identity, respectively grouping 7, 7, 6 and 7 currently returned source editions.
Unrelated suggestions and unreviewed identities remain visible; these figures are
not a claim of catalog-wide coverage. Ragnarok remains a separate work.

Actual Azora Solo and ORV details displayed the novel cards; taps opened SeaNovel
270/952-chapter details, whose reverse cards opened the corresponding real comic.
Back returned to the novel. The installed UI also showed 149 distinct Duke/Azora
chapter rows. A direct existing ReaderActivity component acceptance rendered its
first chapter (26 images); ordinary chapter-entry advertising acceptance remains
separate and no paid ad click was manufactured. Offline refresh retained the
synthetic Solo favorite, bookmark and 204 cached chapter rows. Original-key
install-r preserved those fixtures. Screenshots/SQLite receipts remain outside Git.

The final local full run after the empty-refresh guard executed 1,328 tests:
1,308 passed, 20 optional skipped, zero failures/errors; SQLDelight passed. The
separate opt-in live source/relationship run passed all five cases (zero skips).
The five live tests are a subset of optional cases, not five additional independent
full-suite tests. Real exact-head CI and final integrated artifact receipts are
required before merging; older successful CI does not validate this follow-up.
