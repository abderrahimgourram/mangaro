# Verified2.5.5 publication and preservation

Owner authorization explicitly extended this program to publish all five APK assets in the existing public repository and update the existing website/mandatory-update policy. Application source remained private. No public Git source push, main merge or history rewrite occurred.

- Public Release: https://github.com/abderrahimgourram/mangaro/releases/tag/v2.5.5
- Public assets: ARM64,ARM32,x86,x86_64,Universal, plus SHA256SUMS.txt. All five GitHub SHA256 digests match locally verified signed APKs; anonymous download HEAD requests returned200 with exact sizes.
- Website: https://mangaro-web.vercel.app
- Promoted production deployment: https://mangaro-guc9n8u2j-jalem.vercel.app
- Remote policy: latestVersionName2.5.5, latestVersionCode47, minSupportedVersionCode47, forceUpdate=true; default download is the verified Universal APK. No new update protocol or application gate was invented.
- Live app-version.json, release.json, homepage, download page and four advertising/runtime files match reviewed staging. app-version.json is served no-store. No real ads were opened/clicked.

The website's pre-existing dirty worktree was inventoried. Exactly release.json and app-version.json changed from that starting snapshot. Its unrelated pending advertising changes were preserved locally and excluded from publication staging: the actual production ad-config/static files and rendered SmartLink remain unchanged. No project environment variables or deployment-protection settings changed.

A verification command initially queried a draft GitHub Release by tag before its tag existed publicly; GitHub correctly returned404. The already uploaded draft was inspected by its stable Release ID and all server digests checked before publishing. Another staging check compared /index.html's intentional redirect body with homepage HTML; the canonical / route then matched exactly. Neither was a source/APK regression. No asset was overwritten and the stable website was not switched early.

Final protected-file audit: 56 source/configuration files unchanged, including advertising, consent, SmartLinks, source adapters/identities, novel models/classifier, session storage and GitHub workflows. Public main remained `cb9149802a077afda855c729e540b85450ededa6`. Private recovery remains `0c9f95b565b0ae5aca2343430128eaaf6f5eb025`. No production Supabase writes, schemas, signing credentials, reader/download identities or original user data changed.

The isolated emulator was shut down after final installed-code47 identity verification; the owner's physical device was never accessed. Source/build commit is `413f45dc8bf1b860d8bb9a20e0c1168094af0e2e`; later program commits document acceptance/publication only. The actual phone black-screen cause remains unestablished because it did not reproduce in tested upgrades. Fadaa public samples worked after intermittent upstream403; not every chapter/provider was certified. Real Google login/two-device cloud acceptance and ambiguous work identities remain owner acceptance items.

Final readiness: **READY FOR OWNER MANUAL ACCEPTANCE** within the explicitly tested scope. This is not a claim of universal stability or completed physical-device acceptance.
