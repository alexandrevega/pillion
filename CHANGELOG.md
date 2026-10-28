# Changelog

All notable changes to Pillion are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/), and the project aims for
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.3.0-alpha] - unreleased

Third alpha. The SDL path grows a **Wi-Fi transport with auto-discovery**, self-heals its sessions, and
fixes the projection lifecycle on Android 14 and 15.

### Added
- **SDL over Wi-Fi (TCP).** MirrorSettings + Settings UI expose the bike's IP/port; a blank/"auto" IP arms
  automatic transport selection, and a **USB-priority toggle** (default ON) moves a live TCP session to USB
  the moment the cable is attached.
- **Wi-Fi auto-discovery.** Joining a Wi-Fi network auto-discovers the SDL host (DHCP server = hotspot host,
  `/24` sweep fallback once per network); losing Wi-Fi drops the TCP session like an unplug. New
  `TcpDiscovery` scans the subnet for the SDL port.
- **Persisted Bike IP/port** in settings — typed once, reused every Start; the IP field persists as-you-type.
- **Self-healing SDL sessions.** Sessions rebuild on death (20s grace after suite reconnect), failed/hung
  auto-starts rebuild with escalating backoff (8/24/72s, cap 10), and the video gate resets on a fresh
  session. `HMI-NONE` tears video down after 5s and re-arms automatically.
- Wake lock with an 8h timeout; receiver foreground-service hardening; `onDestroy` clears pending callbacks.
- LeakCanary as a debug-only dependency.

### Fixed
- **Android 14:** the `mediaProjection` foreground-service type is now registered while *arming* capture
  (Android refuses a later upgrade — `attemptVideoStream` threw `SecurityException`); FGS ordering fixed.
- **Android 15:** one shared process-level virtual display per projection grant — a second
  `createVirtualDisplay` per grant killed projection; the surface rebinds on remote-display recreation, and
  the spent token + shared display are released on stop.
- Memory-leak weakpoints in the SDL/video pipeline.

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
[0.3.0-alpha]: https://github.com/alexandrevega/pillion/compare/v0.2.0-alpha...HEAD
[0.2.0-alpha]: https://github.com/alexandrevega/pillion/compare/v0.1.0-alpha...v0.2.0-alpha
[0.1.0-alpha]: https://github.com/alexandrevega/pillion/releases/tag/v0.1.0-alpha
