#!/usr/bin/env bash
# Logcat for this app's VC/* tags (plus crashes). Pass -c to clear the buffer first.
set -euo pipefail
source "$(dirname "$0")/env.sh"
if [ "${1:-}" = "-c" ]; then adb logcat -c; shift; fi
adb logcat -v time "$@" | grep --line-buffered -E " [VDIWEF]/VC/|AndroidRuntime|FATAL"
