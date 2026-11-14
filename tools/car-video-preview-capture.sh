#!/usr/bin/env bash
# Debug APK only. Geometry/headless screenshot check, not Android Auto video playback.
set -euo pipefail

ADB="${ADB:-adb}"
OUT="${1:-car-video-preview.png}"
PKG="com.aayoutube.app"
COMPONENT="$PKG/.MainActivity"
EXTRA="$PKG.EXTRA_CAR_PREVIEW"

command -v "$ADB" >/dev/null || {
  echo "adb not found. Install Android platform-tools." >&2
  exit 1
}
"$ADB" get-state >/dev/null

echo "Opening the debug-only car viewport preview on the connected phone..."
# -S kills an existing phone activity, so never run during active playback.
"$ADB" shell am start -S -n "$COMPONENT" --ez "$EXTRA" true >/dev/null
sleep 3
"$ADB" shell screencap -p /sdcard/aay-car-preview.png
"$ADB" pull /sdcard/aay-car-preview.png "$OUT" >/dev/null
"$ADB" shell rm /sdcard/aay-car-preview.png >/dev/null
echo "Phone preview screenshot: $OUT"
echo "This screenshot does NOT establish Android Auto host or video decoder compatibility."
