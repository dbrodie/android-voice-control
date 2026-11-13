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

enable_service() {
  adb logcat -c
  adb shell settings put secure enabled_accessibility_services "${others:+$others:}$SVC"
  adb shell settings put secure accessibility_enabled 1
  for _ in $(seq 1 20); do
    sleep 1
    adb logcat -d -s VC/Hands | grep -q onServiceConnected && return 0
  done
  echo "WARNING: no onServiceConnected within 20 s"
  return 1
}

# Give the package manager a moment to finish with the new APK before binding (enabling the service right
# after `adb install` sometimes produced a binding whose takeScreenshot always failed with INTERNAL_ERROR).
sleep 2
enable_service || true
# Debug builds: verify the new binding really works (HANDS screenshot); re-bind once if it doesn't.
for attempt in 1 2; do
  adb logcat -c
  adb shell am broadcast -a "$VC_PKG.HANDS" --es op screenshot >/dev/null
  ok=""
  for _ in $(seq 1 8); do
    sleep 1
    if adb logcat -d -s VC/HandsTest | grep -q "op=screenshot n=.* OK"; then ok=1; break; fi
    if adb logcat -d -s VC/HandsTest | grep -q "op=screenshot FAILED"; then break; fi
  done
  [ -n "$ok" ] && { echo "accessibility service connected; screenshot OK"; break; }
  echo "screenshot check failed (attempt $attempt); re-binding the accessibility service"
  if [ -n "$others" ]; then
    adb shell settings put secure enabled_accessibility_services "$others"
  else
    adb shell settings delete secure enabled_accessibility_services >/dev/null
  fi
  sleep 2
  enable_service || true
done
echo "enabled_accessibility_services = $(adb shell settings get secure enabled_accessibility_services | tr -d '\r')"
