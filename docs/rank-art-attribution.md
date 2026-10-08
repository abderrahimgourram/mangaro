# Mangaro rank artwork attribution

Seven offline rank relic illustrations. They follow the existing `RankVisuals.resolve(level).tier` milestones (1–4, 5–9, 10–14, 15–19, 20–24, 25–29, 30). They do not change canonical rank titles, XP or developer presentation.

## License and modifications

All seven foreground motifs are by **Lorc**, https://lorcblog.blogspot.com , from Game-icons.net. Each individual source page was verified on 2026-10-08 as **CC BY 3.0 Unported**: https://creativecommons.org/licenses/by/3.0/ . Commercial redistribution and adaptation are permitted with attribution, a license link and modifications notice.

Mangaro added seven distinct original relic silhouettes, filigree, chains, wings, faceted gemstones, abstract constellation etching and metallic/enamel shading. These are adaptations, not untouched third-party illustrations. Original ornamentation and the resulting illustrations are offered under CC BY 3.0 too; application code licensing is unchanged. No endorsement by Lorc is implied.

No achievement artwork is reused for ranks. No third-party visual frame assets were imported. Shared composition helpers draw original Mangaro metalwork.

Attribution remains available offline from the collection toolbar and Settings → About → «فنون الأختام والتراخيص». The existing website Open Source page complements these APK notices.

| Tier | Levels | Illustration | Original motif | Author / source | License |
| --- | --- | --- | --- | --- | --- |
| 0 | 1–4 | تميمة البداية | Gem pendant icon | [Lorc · source](https://game-icons.net/1x1/lorc/gem-pendant.html) | [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/) |
| 1 | 5–9 | بوصلة الرحلة | Compass icon | [Lorc · source](https://game-icons.net/1x1/lorc/compass.html) | [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/) |
| 2 | 10–14 | بلورة البصيرة | Crystal ball icon | [Lorc · source](https://game-icons.net/1x1/lorc/crystal-ball.html) | [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/) |
| 3 | 15–19 | نصل العزم | Broadsword icon | [Lorc · source](https://game-icons.net/1x1/lorc/broadsword.html) | [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/) |
| 4 | 20–24 | شعار التنين | Dragon head icon | [Lorc · source](https://game-icons.net/1x1/lorc/dragon-head.html) | [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/) |
| 5 | 25–29 | سيف السيادة | Winged sword icon | [Lorc · source](https://game-icons.net/1x1/lorc/winged-sword.html) | [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/) |
| 6 | 30–30 | التاج السماوي | Crown icon | [Lorc · source](https://game-icons.net/1x1/lorc/crown.html) | [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/) |

## Reproducibility

Pinned official SVG repository revision: `82d948812bfe3f269ef8f731dcdb07b08160edc4`. `artwork/ranks/manifest.json` records exact asset URLs, author, license, verification date, source and generated hashes, and WebP sizes. Sources are preserved unchanged; editable adaptations are in `artwork/ranks/illustrations/`.

`python3 tools/generate_rank_art.py` performs an offline hash-checked rebuild with librsvg and ImageMagick. Seven 512×512 transparent WebPs use quality 88 and stripped metadata; only optimized resources are packaged. The shared Coil resource loader performs bounded cached decoding.

Existing XP, rank thresholds and all achievement requirements remain unchanged. Manual profile/reduced-motion checks are left to the user; no device runtime testing is claimed.
