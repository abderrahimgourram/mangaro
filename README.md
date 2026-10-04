# Mangaro official website

Arabic RTL, seven static pages, no runtime framework or third-party analytics.

## Build

`npm run build` generates `dist/`. The default canonical origin is https://mangaro-web.vercel.app; override with `SITE_URL` only after connecting an owned domain.

Release metadata in `release.json` was extracted from the signed Android release's BUILD_INFO.txt and SHA256SUMS.txt. The APK is hosted on GitHub Releases, not in this repository. Do not replace it with a debug APK.

Screenshots are real Mangaro captures: Home / Community Picks, a guest Library, catalogue browse, Reader, and guest Account. WebP copies are resized and system bars are cropped; no UI is fabricated. The guest emulator contains no authenticated profile or email. Reader capture was reused from the existing clean verification set.

Dependency notices are copied from the Android release's generated AboutLibraries registry. The root Android Apache license and Mihon attribution are retained. Noto Sans Arabic is self-hosted under OFL.

Deploy from the linked project with `vercel deploy --prod`, then ensure `mangaro-web.vercel.app` points to the new deployment with `vercel alias set <deployment-url> mangaro-web.vercel.app`.

No Android code was modified. This published APK records Android Git HEAD 73507cd99699cb0e53e466ab7b5c7b55838f9acd; it has not been rebuilt to embed the website URLs yet.
