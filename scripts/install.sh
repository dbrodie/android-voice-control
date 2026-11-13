#!/usr/bin/env bash
# Install the debug APK and grant overlay / accessibility / mic / notifications without UI.
set -euo pipefail
source "$(dirname "$0")/env.sh"
APK="$VC_ROOT/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || { echo "No APK at $APK; run scripts/build.sh first"; exit 1; }

# Our accessibility service, keeping any other services already enabled on the device (the list is
# ':'-separated). Disable ours BEFORE installing and re-enable it after: if the install kills the process
# while the service is bound, the system marks it "crashed" and (on a slow/loaded emulator) can end up
# with a bound-but-dead connection: onServiceConnected runs, but dispatchGesture/performGlobalAction then
# fail. Disable -> install -> enable always gives a clean bind to the new APK.
SVC="$VC_PKG/.control.PhoneAccessibilityService"
current="$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')"
others="$(printf '%s' "$current" | tr ':' '\n' | grep -v -e '^null$' -e '^$' -e "^$VC_PKG/" | paste -sd: - || true)"
if [ -n "$others" ]; then
  adb shell settings put secure enabled_accessibility_services "$others"
else
  adb shell settings delete secure enabled_accessibility_services >/dev/null
fi
sleep 1

adb install -r -g "$APK"
adb shell appops set "$VC_PKG" SYSTEM_ALERT_WINDOW allow
adb shell pm grant "$VC_PKG" android.permission.RECORD_AUDIO
adb shell pm grant "$VC_PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true

adb shell settings put secure enabled_accessibility_services "${others:+$others:}$SVC"
adb shell settings put secure accessibility_enabled 1
echo "enabled_accessibility_services = $(adb shell settings get secure enabled_accessibility_services | tr -d '\r')"
