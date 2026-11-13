#!/usr/bin/env bash
# Start the AVD unless a device/emulator is already attached; wait until boot completes. Idempotent.
set -euo pipefail
source "$(dirname "$0")/env.sh"

if adb devices | awk 'NR>1 && $2=="device"' | grep -q .; then
  echo "Device already attached: $(adb devices | awk 'NR>1 && $2=="device"{print $1}' | tr '\n' ' ')"
elif pgrep -f "qemu.*-avd $VC_AVD" >/dev/null 2>&1 || pgrep -f "emulator.*-avd $VC_AVD" >/dev/null 2>&1; then
  echo "Emulator $VC_AVD process already running; waiting for it to come online..."
else
  echo "Starting emulator $VC_AVD ..."
  nohup emulator -avd "$VC_AVD" -no-snapshot-load -no-boot-anim -no-audio >/tmp/vc-emulator.log 2>&1 &
  disown || true
fi

echo "Waiting for device..."
timeout_s=300; t=0
until adb wait-for-device >/dev/null 2>&1 && [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
  sleep 2; t=$((t + 2))
  if [ $t -ge $timeout_s ]; then echo "Timed out waiting for boot (see /tmp/vc-emulator.log)"; exit 1; fi
done
adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
echo "Boot completed: $(adb devices | awk 'NR>1 && $2=="device"{print $1}' | tr '\n' ' ')"
