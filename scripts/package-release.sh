#!/usr/bin/env bash
# Verify the release APKs Gradle just built and copy them, renamed, to <out-dir> (default dist/):
#   <out-dir>/voice-control-<version>-{arm64-v8a,armeabi-v7a,x86_64,x86,universal}-release.apk
#   <out-dir>/mapping-<version>.txt   (R8 mapping: keep it to decode stack traces from this release)
# Every APK must pass `apksigner verify` and must not contain "apikey_" (prints a count, never a key).
# Shared by scripts/release.sh (local key) and .github/workflows/release.yml (CI key).
# Needs ANDROID_HOME (for build-tools/apksigner); JAVA_HOME is used when set. Prints the version last.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST="${1:-$ROOT/dist}"

OUT="$ROOT/app/build/outputs/apk/release"
MAPPING="$ROOT/app/build/outputs/mapping/release/mapping.txt"
VERSION="$(sed -n 's/.*"versionName": *"\([^"]*\)".*/\1/p' "$OUT/output-metadata.json" | head -1)"
[ -n "$VERSION" ] || { echo "can't read versionName from $OUT/output-metadata.json" >&2; exit 1; }

[ -n "${ANDROID_HOME:-}" ] || { echo "ANDROID_HOME is not set" >&2; exit 1; }
BT="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)"
[ -x "$BT/apksigner" ] || { echo "no apksigner in $BT" >&2; exit 1; }
[ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p "$DIST"
for abi in arm64-v8a armeabi-v7a x86_64 x86 universal; do
  src="$OUT/app-$abi-release.apk"
  [ -f "$src" ] || { echo "missing $src" >&2; exit 1; }
  "$BT/apksigner" verify "$src" || { echo "signature check FAILED: $src" >&2; exit 1; }
  # No API key may ship in a release APK (all JEV keys start with apikey_).
  hits="$(unzip -p "$src" | grep -a -c apikey_ || true)"
  [ "$hits" = "0" ] || { echo "$src contains 'apikey_' ($hits matches): not copying" >&2; exit 1; }
  dst="$DIST/voice-control-$VERSION-$abi-release.apk"
  cp "$src" "$dst"
  bytes="$(stat -f%z "$dst" 2>/dev/null || stat -c%s "$dst")"
  awk -v n="$(basename "$dst")" -v b="$bytes" 'BEGIN { printf "%-52s %6.1f MB  signed, no keys\n", n, b / 1048576 }'
done
"$BT/apksigner" verify --print-certs "$DIST/voice-control-$VERSION-universal-release.apk" | grep -E "certificate (DN|SHA-256)" | head -2
[ -f "$MAPPING" ] || { echo "missing $MAPPING" >&2; exit 1; }
cp "$MAPPING" "$DIST/mapping-$VERSION.txt"
echo "mapping -> $DIST/mapping-$VERSION.txt"
echo "VERSION=$VERSION"
