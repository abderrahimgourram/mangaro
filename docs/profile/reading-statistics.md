# Owner profile reading projection

The owner/guest profile uses `ProfileReadingStatisticsRepository` and the read-only `profileReading.readStatistics` SQL query. Public profiles keep their existing privacy-filtered DTO and optional public aggregate; they never subscribe to local reading data.

## Counting contract

Comics count current `chapters.read = 1` records. This includes manual marking and restored read flags; it does **not** prove a human finished every page. The Arabic label reflects marked-read semantics. Opening, partial page progress, history, downloads, XP and chapter labels are not counting inputs.

Exact `(source, work URL, chapter URL)` tuples are deduplicated. Different provider editions are retained; work identity grouping is not chapter equivalence. Invalid empty identities make that category unavailable. SQL returns an owner-matched row only when the active workspace is ready; a missing owner row is not a zero. No saved data or chapter identities are rewritten.

Novel position records currently contain no per-chapter completion/read flag. Authenticated novel reading does submit idempotent server completion claims through `NovelCommunity.submitCompletedNovelChapter`; those records share the opaque `reader_chapter_completions` key space with comics and have no content-kind field. The existing owner statistics RPC returns comments/ratings only. The privacy-filtered public combined aggregate is conditional on public showcase settings and provides no category breakdown. None is a reliable local novel subtotal, especially for guest/offline reading. Neither a last position nor a whole-work status can establish a novel chapter total. Consequently the novel subtotal and combined total are unavailable (`—`), while the comic subtotal is displayed when known. Unknown is never converted to zero or an estimated count. A combined total is defined only when both category totals are known. This feature therefore does not claim complete reading totals.

A future reliable novel statistic needs a separately reviewed completed/read contract and compatible reader/persistence/cloud handling. It must not be inferred from advertisement gates or XP. No backend schema/RLS or protected reader changes are part of this implementation.

## Ownership and presentation

The SQL aggregate runs off the main thread and emits only counts. Flow subscriptions cancel when workspace ownership/generation changes. Rendering rechecks the token so A → B → A cannot resurrect old results. Collection is lifecycle-aware. No new remote requests or persistent counter/cache is introduced. Existing cloud restoration updates the same read records without event increments or XP calls.

The interface contains numbers and short labels only; details remain in this document. Missing values display a dash. Existing seven rank illustrations, XP formula, developer visual override, all thirty seals, exactly three selected slots, editing, public privacy and showcase controls remain in use. Long numbers use Compose text auto-sizing; large subtotals or accessibility fonts stack category tiles. Visual/device acceptance remains separate from JVM/static integration checks.
