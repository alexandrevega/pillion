# Pillion head-unit architecture (multi-bike, multi-protocol, pluggable)

Pillion started as one path: mirror the phone screen to a Yamaha NaviLite dash over Bluetooth.
This document is the target architecture for supporting **many bikes, many protocols, and many kinds
of dash content** — without rewriting the core each time, and (eventually) via downloadable plugins.

It is a design blueprint. Today only the NaviLite path exists; SDL is being added next (see
`SDL_INTEGRATION.md`). Everything here is built additively behind interfaces so nothing breaks.

## The model: a bike is a *composition*, not a subclass

A `HeadUnitProfile` doesn't inherit behaviour — it **composes** four independent, separately-evolving
concerns. Adding a bike means adding a profile (and only the genuinely-new pieces), never editing
existing code (OCP).

```
HeadUnitProfile  =  LinkKind (transport)
                 +  ProtocolEngine (how to talk)
                 +  VideoPreference → NegotiatedVideo (how video is encoded; resolution NEGOTIATED)
                 +  DashContentSource (WHAT is drawn — mirror / web / wasm-canvas / native)
                 +  AppIdentity (the companion app it stands in for)
```

The existing types already fit: `MirrorController` + `MirrorState` are the UI-facing session; the
new layer sits above them. `MirrorEngine` becomes the NaviLite `ProtocolEngine`'s internals.

## SOLID mapping
- **SRP** — transport, protocol, codec, content, identity are each one type.
- **OCP** — new bike / codec / protocol / content = register a factory; engine + UI untouched.
- **LSP** — any `MirrorController` (NaviLite, SDL, …) runs in the same Compose UI.
- **ISP** — small interfaces; a bike doesn't implement a codec, a session doesn't implement capture.
- **DIP** — UI depends on `HeadUnitProfile`/`MirrorController` abstractions; concretes injected via registries.

## Everything is an open registry (so plugins are just "who calls register()")

`LinkKind` is an **open value class**, not an enum (an enum can't be extended by a plugin):

```kotlin
@JvmInline value class LinkKind(val id: String) {
    companion object { val BluetoothRfcomm=LinkKind("bt-rfcomm"); val UsbAoa=LinkKind("usb-aoa")
                       val UsbIap2=LinkKind("usb-iap2"); val MfiAccessory=LinkKind("mfi"); val TcpDebug=LinkKind("tcp") }
}
```

Transports, codecs, content-sources, and profiles all go through registries. First-party paths
(NaviLite, SDL) register through the *same* API third-party plugins will — so the plugin surface is
dogfooded and always real.

## Protocols: native engines for the giants, data for the rest

Most bikes speak **different** protocols, so this can't be compile-time-only. But protocols split:

| Protocol kind | Engine | Form | Cross-platform? |
|---|---|---|---|
| Huge/stateful (**SDL** — a whole framework library) | built-in native `SdlEngine` | code, shipped | yes (sdl_android / sdl_ios) |
| Structured framed (**NaviLite**-class: magic·ver·type·len·payload·CRC + handshake state machine) | declarative spec interpreter (future) | **data** | yes |
| Arbitrary logic | **WASM** module run by a host (future) | **data** | yes |

The leverage: **transports and codecs are finite** (BT, USB-AOA, USB-iAP2, TCP; JPEG, H264, H265) —
ship them built-in once. The **diversity is in protocols**, which become data (spec/WASM). So:
**finite native engines · unlimited bikes & protocols as data.**

NaviLite proves the declarative tier is real; SDL proves you also need a native tier (you can't
data-ify a framework that big). Most new bikes are one or the other.

### Control plane vs data plane (the performance rule)
WASM/plugins handle the **control plane** only — handshake, framing decisions, capability
negotiation. The **data plane** (H.264/JPEG bytes, transport I/O) stays **native**. The plugin
computes headers/decisions; the host moves the pixels. Heavy per-byte work (CRC) is a native host
import. Video never crosses the WASM boundary at frame rate → plugin cost is effectively unmeasurable.
The only anti-pattern to forbid: copying frames *into* WASM.

## Video resolution is negotiated — never hardcode it

Both protocols negotiate the picture at connect time:
- **SDL** advertises a `VideoStreamingCapability` (e.g. the Tracer: `640×360 / 512 kbps / H264-RAW`).
- **NaviLite** carries display params in its setup/handshake burst.

So a profile declares a `VideoPreference` (codec family + constraints), and the session resolves the
actual `NegotiatedVideo(w,h,bitrate,codec)` from what the head unit reports. A different-resolution
bike on the same protocol therefore needs **zero new code**.

## Dash content is its own pluggable layer (not just "mirror")

`DashContentSource` decouples *what is drawn* from protocol and codec — it renders to an **off-screen
surface sized to the dash**, which the host encodes. This is the "dedicated display" approach (what
Garmin does) and it’s what makes content **sharp** (native render at dash res), **screen-off-capable**
(independent of the phone display), and **interactive** (the bike's joystick/buttons route in).

| Source | What people build | Plugin tier |
|---|---|---|
| `PhoneMirrorSource` | today's screen mirror | built-in |
| `WebContentSource` | HERE Maps (JS SDK), custom start screens, dashboards (HTML/JS/CSS) | data (great on Android; iOS WKWebView video-rate is the hard part) |
| `WasmCanvasSource` | gauges/widgets/start-screens via a host canvas API | data, sandboxed, fully cross-platform |
| `NativeSurfaceSource` | max-fidelity native UI (Compose/UIKit on VirtualDisplay/CarWindow) | code (Android runtime / iOS compiled-in) |

Bike inputs (`OnTouchEvent`/`OnButtonPress` on SDL) become a `DashInput` flow fed into the content,
which is what makes joystick zoom/scroll work.

## Distribution & the iOS reality (we target sideload, not the App Store)

App-Store *policy* doesn't apply (sideloaded among developers/users). But iOS *technical* enforcement
partly remains regardless of install method:
- **Downloaded native code (.dylib) won't load** — code-signing is enforced at runtime (only a
  jailbreak changes this). So native plugins on iOS must be compiled in.
- **JIT is off by default** but can be enabled (AltStore/AltJIT, dev build).
- **Interpreted code as *data* always works** (the interpreter is signed; the script is data).

→ The plugin format is **WASM**: one artifact, both platforms, loaded as data, sandboxed. Android
gets full native runtime plugins too; iOS gets WASM/data plugins + compiled-in native ones.

## Plugin tiers (summary)

| Tier | Adds | Distribution | iOS | Android |
|---|---|---|---|---|
| Data: `ProfileSpec` | a bike recombining existing engines (new params/identity) | JSON | ✅ | ✅ |
| Data: WASM protocol / wasm-canvas content | new protocol logic / custom dash UI | `.wasm` | ✅ (interp; JIT optional) | ✅ |
| Data: web content | rich dash UIs / maps | web bundle/URL | ⚠️ video-rate capture hard | ✅ |
| Code | new transport/codec/native content | compiled-in (both) or runtime APK | compile-in | ✅ runtime |

## Identity & copyright
- **Clean-room**: open SmartDeviceLink + our own code; no vendor code/assets bundled.
- **`AppIdentity` is data** (a compatibility key the bike checks), not marketed impersonation.
- **Bring-your-own-identity**: don't ship a third-party `fullAppId` in the binary — the user supplies
  it (or imports a profile) at first run. The app provides the mechanism; the user the identifier.
- The onboarding selector is framed by **bike / connection type**, never "free clone of X".

## Where it lives (KMP)
- `composeApp/src/commonMain/.../core/headunit/` — `LinkKind`, `AppIdentity`, `VideoPreference`,
  `HeadUnitProfile`, `HeadUnitRegistry`, `SessionFactory` (interfaces + registry; pure Kotlin).
- `androidMain` / `iosMain` — `SessionFactory` actuals; SDL session (Android = sdlprobe port,
  iOS = sdl_ios); `DashContentSource` actuals.
- `commonMain/.../ui/` — onboarding bike-selection screen + settings "change bike".

## Migration order (additive, each step ships working)
0. Land the `core/headunit/` interfaces + registry (no behaviour change).
1. Wrap today's NaviLite flow as `NaviLiteProfile` behind `MirrorController` (no behaviour change).
2. Add `SdlProfile` — Android (port sdlprobe) then iOS (sdl_ios). ← **the current launch goal**
3. Onboarding bike-selection UI (first run + settings).
4. Later: `DashContentSource` (web/wasm-canvas), the WASM protocol host, downloadable `ProfileSpec`s.
