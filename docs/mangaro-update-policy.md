# Mangaro public update policy

The application checks `https://mangaro-web.vercel.app/app-version.json` on cold
launch and process foreground. Requests use a separate credential-free HTTP
client with no cookies or application/source interceptors and a six-second
deadline. Concurrent checks coalesce.

A valid response blocks only when `forceUpdate` is true and the installed
version code is below `minSupportedVersionCode`. The minimum cannot exceed the
latest version, and the download must be the matching HTTPS APK asset in
`abderrahimgourram/mangaro`. Failed requests and invalid responses clear the
blocking decision. No persisted policy can lock out an offline installation.

The non-dismissible Arabic dialog follows the resumed Mangaro activity, including
Reader, without changing its rendering/navigation. Opening the download does not
count as completing an update. A retry action appears if Android cannot open the
link; external browsers do not report download completion to the application.

For 1.1.3/12 the published minimum is 12, so this build remains usable. Historical
APKs without this checker cannot be forced to update by this configuration. A
future release may raise the minimum to enforce updates on checker-enabled APKs.
