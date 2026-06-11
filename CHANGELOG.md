# Changelog

All notable changes to Pillion are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/), and the project aims for
[Semantic Versioning](https://semver.org/).

## [Unreleased]

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

[Unreleased]: https://github.com/alexandrevega/pillion/compare/v0.1.0-alpha...HEAD
[0.1.0-alpha]: https://github.com/alexandrevega/pillion/releases/tag/v0.1.0-alpha
