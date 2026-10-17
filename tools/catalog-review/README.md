# Local catalog identity review

Run from the Android repository:

```sh
python3 -m http.server 8765 --bind 127.0.0.1 --directory tools/catalog-review
```

Open http://127.0.0.1:8765 and select
`app/src/main/res/raw/catalog_reviewed_links.json`.
Review exact source references and public evidence, then explicitly approve or
revoke each relationship. Matching titles/covers are candidates, never approval.
Source IDs stay strings to avoid JavaScript integer rounding.

The page makes no remote requests. Export the reviewed JSON, inspect its diff,
and replace the bundled resource only after review/tests. It takes effect in the
next signed APK; it does not publish, update Supabase, migrate records or merge
chapters. Keep existing work keys when adding editions. Revoke rather than delete
records so the reason/history remains reviewable in Git.

Run `node --test tools/catalog-review/review.test.mjs` for tool checks.
