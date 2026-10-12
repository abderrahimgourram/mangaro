# Targeted native source and presentation verification — 2026-10-12

Emulator: `emulator-5554`, Pixel 4a, Android 11 / API 30. No physical device or public APK release.

MangaTime publishes two separate Sword King catalogue records. `survival-story-of-a-sword-king-in-a-fantasy-world` / `6a45138ab141ade445e09b09` has 304 distinct owned chapters, despite its highest chapter number being 302. `survival-diary-of-the-sword-king-in-a-fantasy-world` / `6a5077a84967e53233f0b228` has zero published chapters. Both official cover responses had SHA-256 `2b8af14e4e706b4041beb9072b06cfc4a16984bd3a9079e38eb6b32f357b75c7`. The long, distinctive titles, byte-identical artwork and matching provider-declared format corroborate their work identity; cover resemblance alone does not.

Search now includes same-provider candidates. Creator/edition/format contradictions still prevent matches. Same-provider title equality alone needs corroboration. Directly corroborated variant names are presentation-only aliases; persistent manga IDs, source URLs, library records and chapter ownership are never merged. Whole-group pairwise matching remains enforced. The original `Survival` query returned 80 raw records and 37 visible work cards; the verified result is 36 cards, with both MangaTime records retained internally and the working 304-chapter entry selected. Counts for two other providers in this group remain unknown; global selection stays internally provisional.

Normal search reads owned cached chapter proofs. For at most two likely same-provider variants it checks one chapter-metadata row, verifies chapter ownership and fingerprints at most 512 KiB of each official cover. It never traverses the complete index or downloads chapter text/pages. Requests are asynchronous, two concurrent, within the existing six-second enrichment budget. Verification-originated health events now coalesce instead of cancelling their own enrichment. Confirmed empty indexes cannot win using stale counts; transient errors remain retryable uncertainty. Explicit retry rechecks bounded evidence.

The MangaTime API for `one-piece` / `6999fdf2a3d831d3564fe9a2` genuinely returns `chapters=[]`, `hasMore=false`, `nextCursor=null`. The same is true for the Diary record. The earlier generic refresh error resulted from an empty terminal response lacking an explicit total in the adapter. Only this authoritative empty terminal now provides zero evidence to existing reconciliation. Previously cached chapters/progress/download rows remain preserved. Missing/malformed continuation, HTTP errors, invalid HTML and ownership mismatches cannot authorize an empty complete index. No chapters or alternative URLs were invented.

Actual UI/reader acceptance:

| Operation | Genuine observation |
|---|---|
| MangaTime Sword King | 304 distinct chapters, shared Coil cover decoded, real native reader image rendered |
| MangaTime ONE PIECE | Zero terminal published entries; actual details show no chapters, without claiming a working reader |
| MangaTime Solo Leveling | 202 distinct chapters (maximum display number 200.5), shared cover and native reader image passed |
| MangaStarz Crimson Reset | 64 observed chapter links, transport completeness still DEGRADED; native reader image rendered; cover blocked |
| MangaStarz Solo Swordmaster | Shared cover and real native reader image passed; transport classification preserved |
| Novel search عبد الظل | One matched work; cached Cenele 3215 versus SeaNovel 3190 / Kolnovel 712; no provider/provisional labels |

Crimson Reset's official cover and all public OG/Twitter alternatives point to `https://starz.starzmanga.com/wp-content/uploads/2025/12/download.jpg`. The asset returns HTTP 403 with an access-denied HTML body, including legitimate Android/desktop User-Agent and originating-page Referer. This is an upstream asset access problem, not a malformed URL or decoder defect. No replacement cover, speculative host rewrite, certificate bypass or challenge bypass was introduced. Its native reader works independently. The operator must restore the published asset's access or publish a legitimate working cover URL.

Details titles wrap fully. The unexplained purple arrow was the reply icon in the empty-comments state; it was removed without changing comments/ratings behavior. Normal search labels and resolver badges are hidden for both content types; original text, creator credits, internal IDs, diagnostics and legal attribution remain.

Adaptive icons now use a transparent derivative of the original gold book/M artwork, a full-bleed `#20132F` background and fractional foreground insets. Original assets and legacy fallbacks remain intact. Real launcher screenshots verify circular, rounded-square and squircle masks, light/dark mode and 360/440 density. Android 13+ monochrome resources remain configured, but themed launcher runtime is NOT YET VERIFIED because this emulator runs API 30. Launcher notification dots are system badges, not part of the artwork. Emulator shape, night-mode and density settings were restored.

Final focused regression suites: 115 distinct tests passed, zero failures/skips; the final cursor change was additionally rerun through the 16 MangaTime parser/adapter tests. Debug application and instrumentation builds passed. Synthetic GIF/JSON fixtures are regression tests only; all observations above used genuine network-backed source data and actual Compose/native-reader UI. Screenshots, logs, exact paths and preservation results: `/home/jalem/Downloads/Mangaro_Source_Repairs_20261012/`.

No MangaStarz production parser, signed rule, publisher workflow, trust anchor, version metadata, shared download engine or cloud schema was changed. Existing corrective commits and unrelated working-tree files were preserved.
