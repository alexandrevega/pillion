# SDL (Path B) integration — full-motion H.264 to USB bikes

Adds SmartDeviceLink as a second head-unit path alongside NaviLite. **Proven end-to-end on a real
Yamaha Tracer** via our standalone probe (`streetcross/sdlprobe`) — the app registers as Garmin
Motorize over a bare USB cable, reaches HMI FULL, and screen-mirrors H.264 to the dash. This doc is
the recipe to port that into Pillion as an `SdlProfile` (a `MirrorController` implementation), on
**Android and iOS**.

> SDL is **not** NaviLite-over-a-byte-channel. It's its own stack (transport + RPC + H.264 video),
> handled by the SDL library. So `SdlProfile` does **not** reuse `ByteChannel`/`MirrorEngine`/
> `NaviLiteCodec`; it wraps the SDL library and exposes the same `MirrorController`/`MirrorState`.

## The identity the bike accepts (proven)
- **appName**: `Motorize`  (Garmin's `R.string.app_name`; matched exactly — the Tracer has no app
  icons, HOME-hold matches the app by **name + ID**, so a wrong name → "Connection Error").
- **fullAppID**: `7a5f3f25-8b82-4e0f-a173-80aefee79897`  (Garmin Motorize's real ID, from the APK/iPA).
- **appHMIType**: `NAVIGATION`
- **transport profile**: **USB-only + `requiresHighBandwidth(true)`** (matches the decompiled
  `GarminSdlService`; the bike identifies the video app by this high-bandwidth USB profile).
- Per the architecture doc, ship this as a **bring-your-own `AppIdentity`** (user supplies the ID at
  first run) rather than baking the Garmin ID into the binary.

## Android — port `streetcross/sdlprobe` (the proven implementation)

Dependency (`gradle/libs.versions.toml` + `composeApp/build.gradle.kts` androidMain):
```
com.smartdevicelink:sdl_android:5.8.0
```

Port these classes into `composeApp/src/androidMain/.../sdl/` (from sdlprobe):
`SdlService`, `SdlRouterService`, `SdlReceiver`, the `RouterServiceMessageEmitter` crash-guard
(`SdlProbeApp.installSdlCrashGuard`), `ScreenMirrorDisplay`.

**The four manifest fixes — all required** (each cost hours; see the streetcross memory recipe):
1. `SdlRouterService` declared with **`android:process="com.smartdevicelink.router"`** (else
   "Not using correct process. Shutting down" → handoff hangs, blank/frozen screen).
2. **`<queries>`** for action `com.smartdevicelink.router.service` (Android 11+ visibility, else
   `onFinishedValidation valid=false; name=null`).
3. **`BLUETOOTH`** permission with **no** `maxSdkVersion`.
4. FGS types `connectedDevice|mediaProjection`; pass the explicit FGS type in `startForeground`.

**Video on FULL (auto-stream — the bike waits for it):** reuse Pillion's `MediaProjectionScreenSource`
*capture surface* but feed the VirtualDisplay Surface to SDL `VideoStreamManager` (ScreenMirrorDisplay)
at the **negotiated** resolution + the dash's max bitrate (`VideoStreamingParameters`) — not the JPEG
path. On `OnHMIStatus FULL`, start the stream immediately.

**Lifecycle wins already learned (carry them over):** auto-register as Garmin on USB attach; one
stable session (no generic/Garmin ping-pong); graceful projection-stop (stop video + downgrade FGS so
the service survives → bike doesn't hang); wake-lock while casting; log `OnTouchEvent`/`OnButtonPress`.

Wire it behind the head-unit layer: the Android `SessionFactory` returns an `SdlMirrorController`
(wrapping `SdlService`) when the selected profile's `LinkKind == UsbAoa`.

## iOS — mirror the Motorize iPA (SDL 6.3.1)

From the decrypted `com.garmin.motorizeapp` iPA:
- **Dependency**: `SmartDeviceLink` **6.3.1** (CocoaPods or SPM) into `iosApp/project.yml`.
- **Info.plist** (`iosApp/iosApp/Info.plist` and the extension) — add to
  `UISupportedExternalAccessoryProtocols` the exact set Motorize declares:
  **`com.smartdevicelink.prot0` … `com.smartdevicelink.prot29`** + **`com.smartdevicelink.multisession`**
  (keep `com.garmin.navilite.data` for Path A).
- **`UIBackgroundModes`**: add **`external-accessory`** (Motorize has it; keeps the USB session alive
  when backgrounded). NaviLite already uses `ExternalAccessory.framework`, so same transport family.
- **No special entitlement** needed — Motorize had none; the EAP protocol strings are the declaration.
  Sign with your own development team in `Signing.xcconfig` (gitignored).
- **SDL setup** (Swift, alongside `EAConn.swift`): `SDLManager` with `appName="Motorize"`,
  `fullAppID="7a5f3f25…"`, `NAVIGATION`, video streaming on; video fed from ReplayKit
  (`Extension/SampleHandler.swift`) into `SDLStreamingMediaManager`. Bridge state to the Kotlin
  `MirrorController` via the existing Swift↔Kotlin/Native seam.

## Verification path (we have both phones; the bike is the tester's)
- **Android**: gradle build → `adb install` → confirm app launches, SDL initialises, registers up to
  "waiting for head unit" (full link needs the bike / the Fedora emulator when back online).
- **iOS**: Xcode build → install on the iPhone → same (UI + SDL init + no crash).
- **Full SDL link + video**: requires a USB bike (cr0wsky's Tracer) or the Fedora SDL-Core emulator.

## Known limits (carry the expectations from real-bike testing)
- **USB bikes only** (no-USB bikes like MT-07 can't do Path B).
- **Screen-off freezes the mirror** (MediaProjection/ReplayKit need the screen on); the real fix is a
  `DashContentSource` rendering off-screen (architecture doc) — Phase 4, not now.
- **Mirror looks softer** than a native render (portrait phone → 640×360 landscape). Same fix as above.
