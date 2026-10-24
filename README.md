# Screen Memory

Screen Memory 1.2 saves named digital zoom and position presets relative to a manually established Native image. A translucent overview lets you select a memory; an alignment canvas helps you edit it with a remote.

This app targets only the vendor service `com.vt.tv.laserprojectorservice.LaserProjectorService`. It requires Android API 31 or later and that service to be available. It is not a general Android TV controller, and compatibility with other hardware or firmware is unverified.

## Using the remote

- Use the D-pad to move focus and Select/Enter to activate a tile. Selecting a memory submits its estimated offsets. Hold Select on a memory to edit it; Native opens a read-only view.
- Select **+** to add a memory. Set its name and aspect width/height; **Guide** refreshes the alignment guide. Use the zoom and direction buttons for live adjustments, then **Save** to store the memory.
- **Back** leaves the editor without saving. Live adjustments already submitted are not undone. Back from the overview closes the activity.
- **Reset** requests the Native offsets without deleting the memory. **Delete** removes a memory without moving the image.
- When tracking is **UNKNOWN**, manually align the image to your intended Native state, then confirm **Current image is Native**. Confirmation itself sends no adjustment commands and sets tracked offsets to zero. Native is a reference, not an automatic hardware calibration.

Offsets are Native-relative command-count estimates, not absolute hardware measurements. Aspect ratios are visual guides; they do not measure or guarantee the projected aspect ratio. Changes through another remote, app, or projector settings invalidate tracking and require manual re-reference, even if the app still displays a known state. Restarting or stopping control can also make tracking UNKNOWN.

Successful submission does not establish hardware completion. Step sizes, coordinates, scale, physical boundaries, and hardware completion remain unmeasured. Software limits (100 offset / 120 operation steps) and estimated digital zoom limits are not verified hardware bounds. The notification offers **Open** and **Stop**; stopping invalidates tracking and cannot undo commands already submitted.

## Build and local checks

Use Windows PowerShell or PowerShell 7 on Windows, JDK 21 on PATH (`java`, `javac`, `jar`, `keytool`), Python 3, and the official Android SDK with platform `android-31` and build tools `35.0.0`. No Gradle or additional libraries are required. From a fresh checkout with no `build` directory:

```powershell
$SdkRoot = 'C:\Android\Sdk'
$KeyPath = Join-Path $env:LOCALAPPDATA 'ScreenMemory\debug.keystore'
New-Item -ItemType Directory -Force (Split-Path $KeyPath) | Out-Null
# A child PowerShell preserves the build's exit status while capturing both streams.
& powershell.exe -NoProfile -File .\build.ps1 -SdkRoot $SdkRoot -KeyPath $KeyPath 2>&1 | Tee-Object -FilePath build.log
if ($LASTEXITCODE -ne 0) { throw 'Build failed; inspect build.log' }
python .\verify_offline.py .\build\screen-memory-debug.apk --dexdump .\build\dexdump.txt --build-log .\build.log
if ($LASTEXITCODE -ne 0) { throw 'Verification failed' }
```

The build refuses an existing `build` directory to preserve evidence. It compiles with warnings treated as failures, runs three host Java test suites, packages and signs `build/screen-memory-debug.apk`, and checks signature/alignment. To run only the source/XML subset:

```powershell
python .\verify_offline.py
```

The verifier checks an automated subset. Independent binary manifest, message payload, and control-flow review remain necessary; CI does not verify hardware behavior or broad compatibility.

Preserve your signing key locally and keep it out of Git. A self-built key cannot update an installed app signed with a different key. CI generates an ephemeral random debug key, so its builds are not update-compatible with differently signed installations or previous CI runs. No release signing or distribution is configured.

## Privacy and limitations

This is a debuggable APK, not a release-hardened build. Memories and an operation journal are stored in app-private files; operation events also go to logcat. These can contain usage details: review diagnostic output before sharing it. Android backup is disabled. The manifest has no network permission; the only requested permission is for the app's foreground service. Vendor-service behavior is outside this repository's verification boundary.

No license grant is provided in this repository. Do not assume permission to redistribute or relicense the source.
