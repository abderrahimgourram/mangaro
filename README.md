# Mangaro official website

Arabic RTL, eight static pages and one server-side support endpoint, no frontend framework or third-party analytics.

## Build

`npm run build` generates `dist/`. The default canonical origin is https://mangaro-web.vercel.app; override with `SITE_URL` only after connecting an owned domain.

Release metadata in `release.json` was extracted from the signed Android release's BUILD_INFO.txt and SHA256SUMS.txt. The APK is hosted on GitHub Releases, not in this repository. Do not replace it with a debug APK.

Screenshots are real Mangaro captures: Home / Community Picks, a guest Library, catalogue browse, Reader, and guest Account. WebP copies are resized and system bars are cropped; no UI is fabricated. The guest emulator contains no authenticated profile or email. Reader capture was reused from the existing clean verification set.

Dependency notices are copied from the Android release's generated AboutLibraries registry. The root Android Apache license and Mihon attribution are retained. Noto Sans Arabic is self-hosted under OFL.

Deploy from the linked project with `vercel deploy --prod`, then ensure `mangaro-web.vercel.app` points to the new deployment with `vercel alias set <deployment-url> mangaro-web.vercel.app`.

Release metadata is maintained in `release.json`; the APK is signed with the permanent Mangaro certificate. The app includes the production website legal and support URLs.

Customer support: see [SUPPORT_SETUP.md](SUPPORT_SETUP.md) for server-only Gmail SMTP configuration, ticket generation and focused checks.

## Search Console and SEO checks

The genuine Google HTML verification download is stored at the repository root as
`google3bdfade8e114f8ca.html`. Every build copies its bytes into `dist/`; keep this
file after verification because Google may recheck it. No HTML meta verification
token has been supplied, and no analytics or tag manager is used for verification.

Run `npm run build && npm run validate` for static SEO checks, and
`node --test test/*.test.mjs` for the existing support checks. Public pages use
Arabic content with natural Arabic/English product metadata. There are no separate
English translations, so no English hreflang alternatives are declared.

Vercel routing preserves the exact verification `.html` URL, rewrites the seven
clean page routes to their static HTML, and permanently redirects duplicate page
`.html` and index URLs to canonical URLs. `ad-slot` is intentionally excluded from
indexing with an HTTP header; all eight public content pages remain indexable.
The sitemap lists only those canonical pages, and robots.txt excludes the API.

In Search Console, select the URL-prefix property
`https://mangaro-web.vercel.app/`, click **Verify** with the HTML file method, then
submit `sitemap.xml`. Use URL Inspection for the homepage and download page,
run the live test, and request indexing. Ownership and indexing remain Google's
decisions; deployment alone does not confirm either.
