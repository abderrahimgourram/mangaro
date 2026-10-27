# Original signed updater UI probe

This separately installed instrumentation APK must be signed with the existing
release certificate; it is never a distributable application artifact. It targets
`app.manhwaar.reader` without rebuilding, patching, or making the published APK
debuggable.

The probe creates an additional instance of the target's production controller
and replaces only that instance's public-manifest HTTP transport. The actual
published parser, version comparisons, lifecycle methods, dialog, and Android
input handling run unchanged. Fixture results must be labelled as controlled
manifest UI results, not as live-server verification.

Use only an explicitly authorized emulator. Test minimum 48 and 49 on published
code48 and code49. Test real offline startup separately: disable emulator Wi-Fi
and mobile data, force-stop and launch the original app without instrumentation,
then restore networking. Do not throw checked exceptions through a Java proxy
into a shrunk Kotlin interface: its removed Exceptions attribute wraps them in
UndeclaredThrowableException, which does not represent a real network failure.
The fixture deliberately accepts only the official manifest URL and supplies the
actual official version49 Universal asset URL. Follow controlled tests with a
separate real-HTTPS test after any authorized policy deployment.

The probe records a screenshot and fails if a mandatory dialog can be dismissed
with Back or an outside touch. It never changes reading data or application
preferences. Uninstalling or downgrading the target is not part of the probe;
use a disposable emulator overlay when older APK testing requires it.
