#!/usr/bin/env bash
# Build the signed, R8-minified release APKs (one per ABI + universal), verify them and copy them to dist/:
#   dist/voice-control-<version>-{arm64-v8a,armeabi-v7a,x86_64,x86,universal}-release.apk
#   dist/mapping-<version>.txt   (R8 mapping: keep it to decode stack traces from this release)
# Needs keystore.properties + keystore/ (see app/build.gradle.kts). Extra args go to Gradle.
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$VC_ROOT"

[ -f keystore.properties ] || { echo "keystore.properties missing: the APKs would be unsigned"; exit 1; }

./gradlew :app:assembleRelease -PabiSplits=true "$@"

OUT="$VC_ROOT/app/build/outputs/apk/release"
MAPPING="$VC_ROOT/app/build/outputs/mapping/release/mapping.txt"
VERSION="$(sed -n 's/.*"versionName": *"\([^"]*\)".*/\1/p' "$OUT/output-metadata.json" | head -1)"
[ -n "$VERSION" ] || { echo "can't read versionName from $OUT/output-metadata.json"; exit 1; }

BT="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)"
[ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p "$VC_ROOT/dist"
for abi in arm64-v8a armeabi-v7a x86_64 x86 universal; do
  src="$OUT/app-$abi-release.apk"
  [ -f "$src" ] || { echo "missing $src"; exit 1; }
  "$BT/apksigner" verify "$src" || { echo "signature check FAILED: $src"; exit 1; }
  # No API key may ship in a release APK (all JEV keys start with apikey_). Prints a count, never the key.
  hits="$(unzip -p "$src" | grep -a -c apikey_ || true)"
  [ "$hits" = "0" ] || { echo "$src contains 'apikey_' ($hits matches): not copying"; exit 1; }
  dst="$VC_ROOT/dist/voice-control-$VERSION-$abi-release.apk"
  cp "$src" "$dst"
  printf '%-52s %6.1f MB  signed, no keys\n' "$(basename "$dst")" "$(echo "$(stat -f%z "$dst" 2>/dev/null || stat -c%s "$dst") / 1048576" | bc -l)"
done
cp "$MAPPING" "$VC_ROOT/dist/mapping-$VERSION.txt"
echo "mapping -> dist/mapping-$VERSION.txt"
echo "Phones: install the arm64-v8a APK. Anything else (old 32-bit phones, x86 emulators, unsure): universal."
