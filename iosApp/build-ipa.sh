#!/usr/bin/env bash
#
# Build a sideloadable, UNSIGNED .ipa of Pillion (app + broadcast extension) for distribution
# *without* the App Store. Users install it with AltStore / SideStore / Sideloadly / TrollStore,
# which re-sign it with their own (free) Apple ID on device — so we ship it unsigned on purpose.
#
# Usage:  ./build-ipa.sh [Debug|Release]   (default Release)
#
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

CONFIG="${1:-Release}"
DD="build/ipa"

xcodegen generate >/dev/null
rm -rf "$DD" Payload Pillion.ipa

xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration "$CONFIG" \
  -destination 'generic/platform=iOS' -derivedDataPath "$DD" \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="" \
  build

APP=$(find "$DD/Build/Products" -maxdepth 2 -name 'iosApp.app' | head -1)
[ -n "$APP" ] || { echo "error: build produced no .app" >&2; exit 1; }

mkdir -p Payload
cp -R "$APP" Payload/

# Ad-hoc sign inside-out (extension first, then app) so the IPA ships with a COMPLETE, well-formed
# nested signature structure: the app's CodeResources seals the appex's cdhash, and every Mach-O has a
# real signature slot. Re-signers (Sideloadly/AltStore) then just *replace* that structure with the
# user's Apple ID — a `codesign --force` over an existing signature is reliable. Shipping fully unsigned
# instead forces the re-signer to *synthesize* the nested appex seal from nothing, which Sideloadly does
# wrong → the appex's on-disk pages don't match its seal → the kernel SIGKILLs it at launch with
# "CODESIGNING / Invalid Page" (the broadcast extension dies instantly, dash stays blank).
# Embed the App Group entitlement in the ad-hoc signature (CODE_SIGNING_ALLOWED=NO strips it otherwise).
# The re-signer (SideStore/AltStore) reads these to know which capabilities/groups to register on the
# user's free Apple ID — without them the extension + app share NO container and the SDL flag / log never
# cross. SideStore may still rewrite the group id; both sides read the granted id dynamically at runtime.
PAYAPP="Payload/iosApp.app"
find "$PAYAPP/PlugIns" -name '*.appex' -print0 2>/dev/null | while IFS= read -r -d '' appex; do
  codesign --force --sign - --timestamp=none --entitlements Extension/PillionBroadcast.entitlements "$appex"
done
codesign --force --sign - --timestamp=none --entitlements iosApp/iosApp.entitlements "$PAYAPP"
echo "ad-hoc signed (inside-out): $(codesign -dv "$PAYAPP" 2>&1 | grep -c 'Signature=adhoc') adhoc seal on app"

zip -qry Pillion.ipa Payload
rm -rf Payload

echo "built: $(pwd)/Pillion.ipa  ($(du -h Pillion.ipa | cut -f1))"
echo "embedded extension: $(unzip -l Pillion.ipa | grep -c '\.appex/') files"