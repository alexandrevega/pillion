# Changelog

All notable changes to Pillion are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/), and the project aims for
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.3.0-alpha] - 2026-11-08

Third alpha. Scooter dashes (XMAX/NMAX) connect, Android reconnects on its own, iOS copes with a bike that
isn't ready yet, and testers can now send logs straight from Settings.

### Added
- **XMAX/NMAX scooter support.** Dash frames are sized per CCU so the scooter dashes connect, on Android
  and iOS; Android's dedicated-display (dash) mode uses the same size. Thanks @backpapp (#8, #9, #10) and
  @mrbnd (iOS size logic).
- **Bluetooth auto-reconnect (Android).** A dropped NaviLite link reconnects by itself, but only after a
  successful handshake so a wrong device doesn't loop. Thanks @backpapp.
- **iOS waits for the bike and retries.** The NaviLite session waits for the bike to show up and retries
  instead of giving up on the first failed attempt.
- **Share debug logs.** Settings -> *Share debug logs* sends the debug log (iOS: the broadcast extension's
  log; Android: a zip of the app + SDL router logcat with a summary of USB/AOA state, installed SDL
  routers and networks) so testers can report problems without adb. Nearby Wi-Fi networks are never
  logged; only the network the phone is joined to. Android part thanks @brunohorta82 (#14).
- **SDL over Wi-Fi (experimental, Android).** Settings -> *Wi-Fi link* lets the Motorize-compatible
  profile connect to the dash over the bike's Wi-Fi hotspot (SDL TCP transport) instead of the USB cable.
  *Scan bike Wi-Fi* finds the dash's gateway and SDL port; the process is bound to the hotspot so traffic
  doesn't leak to mobile data. Reported working on a Tracer 9 GT. Thanks @brunohorta82 (#14).
- The home screen shows the selected bike/profile (and "via Wi-Fi" when that link is active).
- **Virtual-display controls (Android).** Pin one app to the dash, with persistent app selection, DPI
  and layout margins; system decorations are hidden and a STATUS frame shows while the dash waits for a
  fresh image. Thanks @waiasu (#23).

### Changed
- **Dash helper requires a secret.** The privileged dash helper now only talks to callers that present
  its per-session token, so other apps on the phone can't drive it.
- **Faster iOS streaming.** Adaptive streaming reaches 10-12 fps on the bike link (was 1-3); NaviLite is
  stop-and-wait on both platforms and the Android default max FPS is now 15.
- **Build:** migrated to AGP 9.0 / the KMP library plugin with the Android app split into `androidApp`
  (thanks @roinuj39, #6); `androidx.fragment` is pinned so release builds pass lint. Removed the inert
  Compatibility-mode toggle.

### Fixed
- Starting a session twice (tapping Start again) no longer leaves the first mirror engine, screen capture
  and threads running.
- `enableTcpip` no longer hangs the ADB bootstrap for the dash helper (@backpapp).
- **Capture, ADB and virtual-display reliability (@waiasu, #23).** RFCOMM sockets from failed connection
  attempts are closed and the initial CCU connection retries; the ADB helper reuses an active loopback
  transport and no longer stalls the watchdog (ANR); stopped MediaProjection sessions are released and
  stale mirror frames are never shown; mirror and virtual-display lifecycles are separated; IMAGE_ACK
  waits are bounded with a verified 16-bit sequence; a stalled CCU authentication recovers; and the dash
  preserves CCU aspect ratio, gates frames on `SIZE_READY`, and coordinates phone lock/unlock and display
  transitions. Supersedes #15, #16 and #17. Validated on a Pixel 10a + Yamaha XMAX commute.
- iOS: Compose resources ship in the app again (fixes a crash opening Settings).
- Host tests stub `android.util.Log`.

### Known issues
- The dash goes black when the phone locks (mirroring).
- Pair the bike with MyRide or Y-Connect first; Pillion needs the bike already paired.
- On Android, disable battery optimization for Pillion or the system may kill the session.
- iPhone on XMAX / Tracer 7 GT is still being tested; if it doesn't connect, send the debug log
  (Settings -> *Share debug logs*).

## [0.2.0-alpha] - 2026-06-28

Second alpha. iOS joins Android, and a SOLID head-unit architecture lands so the app can drive more than
one dash type — including **SDL over USB** (full-motion H.264) alongside the original NaviLite slideshow.

### Added
- **iOS app** (Kotlin Multiplatform + SwiftUI shell). NaviLite mirroring over the MFi External Accessory
  session, captured via a ReplayKit broadcast upload extension; TCP fallback to the dev emulator.
- **Bike / app onboarding.** First launch (and a "Change" action in Settings) lets you pick the head unit;
  the choice is persisted per platform and the shared UI resolves the right session for it.
- **SDL "Path B" — USB full-motion video** to SDL head units, registering under the Garmin *Motorize*
  identity (NAVIGATION, high bandwidth):
  - Android: AOA transport via the SDL router service + `MediaProjection` mirror, auto-streaming on the
    dash's `FULL` HMI state with a graceful screen-off stop so the bike doesn't hang.
  - iOS: `SDLManager` + CarWindow projection via `sdl_ios` (SPM), declaring the iAP2 EAP protocols and
    `external-accessory` background mode. Renders the app's own dash content (not a phone mirror).
- **Open head-unit model (SOLID).** `HeadUnitProfile` / `HeadUnitRegistry` / `SessionFactory`, an extensible
  `LinkKind`, and a `VideoPreference` (JPEG slideshow vs negotiated H.264) — new bikes/transports plug in
  without touching existing code. See `docs/ARCHITECTURE_HEADUNITS.md` and `docs/SDL_INTEGRATION.md`.
- **Per-platform versioning** + in-app update check sourcing each build's own version.
- Android: self-healing dedicated dash via loopback ADB (survives Wi-Fi loss) and locked-screen dash
  rendering on Android 16.
- **Unit tests + code coverage** (Kover): protocol codec/CRC, captured echo-auth vectors, frame-reader
  resync, the full NaviLite handshake sequence, and the head-unit registry/profiles/identity.

### Known limitations
- iOS SDL/USB connection to a physical head unit is pending on-bike verification (the build is complete and
  ad-hoc/placeholder-signed; no Apple Developer certificate is used).
- SDL/USB requires a USB-capable head unit; Bluetooth-only bikes (e.g. MT-07) use the NaviLite path.

## [0.1.0-alpha] - 2026-06-12

First alpha. Android-only; the shared protocol/engine is ready for an iOS target later.

### Added
- Mirror the phone screen to a Garmin-powered Yamaha dash over Bluetooth (NaviLite), ~480×240.
- NaviLite protocol implementation: framing, CRC-32/MPEG-2, and the universal echo-auth (no per-bike
  key). Unit-tested against captured frames.
- `MirrorEngine` with platform-agnostic `ByteChannel` / `ScreenSource` abstractions.
- Android: RFCOMM transport, MediaProjection screen capture, foreground service with a wake lock.
- Compose UI (light/dark): connect guide, live FPS, Start/Stop, and a Settings screen with
  **Battery & quality** controls (JPEG quality + frame-rate cap).
- Launch-time experimental-safety disclaimer.
- In-app update check against GitHub Releases.
- Adaptive launcher icon.

### Known limitations
- Field-testing is limited to a single MT-07 (2025); other bikes are untested — reports welcome.
- The phone must be mounted in landscape (auto-rotate on) for the map to fill the dash.
- No iOS build yet.

[Unreleased]: https://github.com/alexandrevega/pillion/compare/v0.2.0-alpha...HEAD
[0.2.0-alpha]: https://github.com/alexandrevega/pillion/compare/v0.1.0-alpha...v0.2.0-alpha
[0.1.0-alpha]: https://github.com/alexandrevega/pillion/releases/tag/v0.1.0-alpha
