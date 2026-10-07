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
