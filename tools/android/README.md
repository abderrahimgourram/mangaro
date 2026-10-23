# Installed-app Search viewport acceptance

Run only against the dedicated disposable **emulator-5554** with the real signed
Mangaro candidate installed. The script refuses a non-emulator and hardcodes its
ADB serial. It does not install/uninstall, clear data, sign in, click advertising,
change source responses or replace the app's UI. It drives the actual Search tab
using Android accessibility, and saves genuine UI hierarchy/screenshot evidence.

Requirements: JDK 17 (`JAVA_HOME`), Android SDK API 34/D8 (`ANDROID_HOME`, or the
existing `~/Android/Sdk`), ADB, and Python 3. No third-party Python dependencies.

```bash
python3 tools/android/verify_search_viewport.py \
  --evidence-dir "$HOME/.cache/mangaro-search-viewport-$(date -u +%Y%m%dT%H%M%SZ)"
```

The evidence directory must be new and outside the repository. The native text
helper is compiled there and pushed to `/data/local/tmp` on emulator-5554. Public
query strings are encoded only to preserve Arabic input, not to hide secrets.
The helper accepts editable nodes from Mangaro's package only.

The test establishes an earlier Duke result, performs a new Arabic Solo query,
and verifies a real matching **result card below the input**, not the identical
query inside the EditText. It then scrolls and refreshes to check that the app does
not pull a user-scrolled viewport back to the top. A short/non-scrollable live list
is reported as NOT TESTED for that second assertion. Unavailable seed data is
BLOCKED; missing expected results fail rather than being silently accepted.

Live transport timing varies. A successful rerun on older code is not proof that
an intermittent late-arrival defect never existed. The captured original failure,
public runtime ordering and a manual return to the logical top established the
old LazyGrid key-anchor defect. Actual signed-candidate results must be reported
separately from JVM/data-layer tests; this script introduces no mocked screen.

The production screen requests the next remeasure's logical top only if it was
at the top before the leading work/notice changed, with no active scroll. It uses
the [official LazyGridState contract](https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/grid/LazyGridState):
`requestScrollToItem` overrides preservation by first-visible key for the next
remeasure. User-scrolled views and pagination retain normal keyed positioning.
