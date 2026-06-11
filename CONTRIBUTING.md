# Contributing to Pillion

Thanks for helping out! Bug reports, **bike-compatibility reports**, and pull requests are all
welcome. This is a non-commercial interoperability project — please keep contributions in that
spirit.

## Ground rules

- **No third-party code or binaries.** Never commit Garmin/Yamaha apps, firmware, decompiled sources,
  `.ipa`/`.apk`/`.update` files, Bluetooth captures, or any other copyrighted/vendor assets. The
  `.gitignore` blocks the common ones, but it's on you to keep the tree clean. Pillion is an
  *original* implementation of an *observed* protocol — keep it that way.
- **No personal data.** No real names, emails, device names, Bluetooth MACs, or your bike's
  ESN/serial in code, docs, or commit messages. Model-level facts (e.g. a CCU part number, bike
  model) are fine.
- **Safety matters.** See [SAFETY.md](SAFETY.md). Don't add features designed to be watched while
  riding.
- By contributing, you agree your contribution is licensed under the project's
  [PolyForm Noncommercial 1.0.0](LICENSE.md) license.

## Reporting bike compatibility

The single most useful contribution. Open an issue with:

- Bike **make / model / year**
- The dash / CCU **part number** if you know it (e.g. `006-B4160-00`)
- What happened: did it connect? authenticate? show an image? what fps?
- Phone model + Android version

## Project structure

Kotlin Multiplatform + Compose Multiplatform. The app module is `composeApp`:

```
composeApp/src/
  commonMain/   # shared, platform-agnostic: protocol + engine + Compose UI
    protocol/   # NaviLite framing, CRC-32/MPEG-2, auth   (see docs/PROTOCOL.md)
    core/       # ByteChannel / ScreenSource abstractions, MirrorEngine, Handshake
    ui/         # Compose UI
  androidMain/  # Android actuals: RFCOMM transport, MediaProjection capture, service
  commonTest/   # protocol unit tests (CRC + captured auth vectors)
```

The shared code knows nothing about Android — platform pieces are injected through the `ByteChannel`
and `ScreenSource` interfaces. Adding **iOS** later means new actuals (External Accessory + ReplayKit)
with **no** change to the protocol, engine, or UI.

## Building & testing

```bash
./gradlew :composeApp:assembleDebug         # build the APK
./gradlew :composeApp:testDebugUnitTest     # run protocol unit tests
```

Requires the Android SDK + JDK 17 (or Android Studio). Create a `local.properties` with
`sdk.dir=/path/to/Android/sdk` (it's gitignored).

## Code style

- Follow **SOLID**. Keep classes small and single-responsibility; depend on the `core` interfaces,
  not concrete platform types. New platform behavior goes behind an interface in `commonMain` with an
  actual in the platform source set.
- Match the surrounding Kotlin style (official Kotlin conventions, 4-space indent). Keep public types
  documented with a one-line KDoc explaining the responsibility.

## Pull requests

- Keep PRs focused and small where possible; describe what you changed and how you tested it (bench
  fps, on which bike if relevant).
- Make sure `:composeApp:testDebugUnitTest` passes.
- **Commit messages use [Conventional Commits](https://www.conventionalcommits.org/)**, e.g.
  `feat(android): …`, `fix(protocol): …`, `docs: …`, `chore: …`, `refactor(core): …`.
