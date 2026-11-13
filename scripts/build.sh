#!/usr/bin/env bash
# Build the debug APK: app/build/outputs/apk/debug/app-debug.apk
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$VC_ROOT"
./gradlew :app:assembleDebug "$@"
