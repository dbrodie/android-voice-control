#!/usr/bin/env bash
# Build the signed, R8-minified release APKs (one per ABI + universal), verify them and copy them to dist/:
#   dist/voice-control-<version>-{arm64-v8a,armeabi-v7a,x86_64,x86,universal}-release.apk
#   dist/mapping-<version>.txt   (R8 mapping: keep it to decode stack traces from this release)
# Needs keystore.properties + keystore/ (see app/build.gradle.kts). Extra args go to Gradle.
# The verify-and-copy step is scripts/package-release.sh, shared with the GitHub release workflow.
# Note: these APKs are signed with your local key, GitHub Releases APKs with the CI key (see docs/SKELETON.md).
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$VC_ROOT"

[ -f keystore.properties ] || { echo "keystore.properties missing: the APKs would be unsigned"; exit 1; }

./gradlew :app:assembleRelease -PabiSplits=true "$@"
"$VC_ROOT/scripts/package-release.sh" "$VC_ROOT/dist"
echo "Phones: install the arm64-v8a APK. Anything else (old 32-bit phones, x86 emulators, unsure): universal."
