#!/bin/sh
# Copies the on-device Laya model files (T17) into a DEBUG build of Voice Control on a connected phone/emulator:
#   tools/laya/push_to_device.sh [out-dir]          (default: tools/laya/out next to this script)
# adb push -> /data/local/tmp/laya (world-readable), then `run-as` (debuggable builds only) copies them into the
# app's private files/models/laya-android/. ~703 MB; needs ~1.4 GB free while it runs. Then check with
#   adb shell am broadcast -a com.brainstormity.voicecontrol.LAYA_STATUS ; adb logcat -s VC/Laya
# Release builds: use "Import" in the Next-step model card (or Download) instead.
set -e
OUT="${1:-$(dirname "$0")/out}"
PKG=com.brainstormity.voicecontrol
DEST=files/models/laya-android
ADB="${ADB:-adb}"
FILES="laya_android_multi_embeds_wfp16.tflite token_embeddings_fp16.bin token_embeddings.json tokenizer.json rl_agent_config.json"
"$ADB" shell mkdir -p /data/local/tmp/laya
"$ADB" shell run-as "$PKG" mkdir -p "$DEST"
for f in $FILES; do
  [ -f "$OUT/$f" ] || { echo "missing $OUT/$f" >&2; exit 1; }
  "$ADB" push "$OUT/$f" /data/local/tmp/laya/ >/dev/null
  "$ADB" shell chmod 644 "/data/local/tmp/laya/$f"
  "$ADB" shell run-as "$PKG" cp "/data/local/tmp/laya/$f" "$DEST/$f.part"
  "$ADB" shell run-as "$PKG" mv "$DEST/$f.part" "$DEST/$f"
  "$ADB" shell rm "/data/local/tmp/laya/$f"
  echo "installed $f"
done
"$ADB" shell rmdir /data/local/tmp/laya 2>/dev/null || true
"$ADB" shell run-as "$PKG" ls -l "$DEST"
