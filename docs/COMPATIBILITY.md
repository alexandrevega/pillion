# Bike compatibility

Pillion works with any Yamaha that uses the **Garmin "Communication Control Unit" (CCU)** — i.e.
any bike that works with the Garmin StreetCross app. The NaviLite protocol is shared across these
models, and the auth is universal: there is **no per-bike key or pairing secret** to extract (see
[PROTOCOL.md](PROTOCOL.md)).

Bikes with the larger **Garmin dash that uses the Motorize app** (Tracer 9 GT/GT+, TMAX) speak a
different, SDL-based protocol. As of 0.3.0-alpha these are supported **over the USB cable** (full-motion
H.264); Wi-Fi for Motorize is documented but still experimental. Pick the matching head unit in
onboarding — *StreetCross-compatible* (Bluetooth) or *Motorize-compatible* (USB/Wi-Fi).

## Confirmed working

Reported working by the community so far:

| Model | Phone / OS | Streams? | fps | Reported by |
|-------|------------|:-------:|-----|-------------|
| **MT-07 (2025)** | — | ✅ | ~14–15 | maintainer |
| **R9 (2026)** | Galaxy S25 Ultra / Android 16 | ✅ | ~15, smooth | @rf7719. |
| **MT-09 (2025)** | Galaxy S23 / Android 16 | ✅ | ~11–12 | @mobiusixi6246 |
| **MT-09 (2024)** | Galaxy A52 / Android 14 | ✅ | ~12 | @mxtt |
| **MT-09 SP (2026)** | Android | ✅ | — | @raccoon_builds |
| **XSR900 (2025)** | Pixel 9 Pro / Android 16 | ✅ | ~11–12 | @Turbobrallan |
| **XMAX (2025)** | Galaxy Z Flip5 / Android 15 | ✅ | ~14 | contributor |
| **MT-09 Y-AMT (2025)** | Pixel 11 Pro XL / Android | ✅ | ~9–11 | @tanto7 |
| **MT-09 SP (2024)** | iPhone / iOS 26.6 (sideload) | ✅ | — | @dami32_38348 |
| **R7 (2026)** | Android | ✅ | — | @dpm_sarang, @tigear |
| **XMAX 300 (2024)** | Android | ✅ (Waze) | — | @stencell4613 |
| **TechMAX 125 (2025)** | Android | ✅ | — | @aggravated.avocado |
| **Tracer 7 GT (2026)** | Galaxy Z Fold6 / Android | ✅ (drops ~1 h) | — | @vaatho |
| **Tracer 9 GT (2026)** — SDL over Wi-Fi, 640×360 H.264 | Galaxy A54 / Android 16 | ✅ | — | community tester |
| **Tracer 9 GT+ (2024)** — Motorize over USB | Galaxy S23 / Android | ✅ | low lag | @bender0875. |

> **Scooter CCU note.** The XMAX / NMAX scooter CCU (part number `006-B3952-xx`) renders navigation
> at **480×234**, not the 480×240 the MT-class dashes use. Pillion now detects this from the CCU
> part number during the handshake and sizes frames automatically — sending 480×240 was what made
> these dashes show "Connection Error" on entering navigation (issue #7).

## Very likely compatible

Other models on the same CCU platform almost certainly work, since the protocol is shared — for
example:

- XSR900 GP
- Niken GT
- TMAX (Motorize/USB path; a community dev reports a TMAX 560 streaming over USB)
- NMAX (same scooter CCU as the XMAX)

**More reports welcome.** If you've tried Pillion on your bike — working or not — please open an
[issue](../../issues) or post in the [Discord](https://discord.gg/mxNV97QUnB) with your model, phone
/ OS, and result (and fps if it streams). It helps everyone.
