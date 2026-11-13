#!/usr/bin/env bash
# Source me: `source scripts/env.sh`. Loads the project-root .env, exports its vars, puts adb/emulator on PATH.
_VC_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)"
export VC_ROOT="$_VC_ROOT"
if [ -f "$VC_ROOT/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$VC_ROOT/.env"
  set +a
fi
: "${ANDROID_HOME:=$HOME/Library/Android/sdk}"
export ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
[ -f "$VC_ROOT/local.properties" ] || echo "sdk.dir=$ANDROID_HOME" > "$VC_ROOT/local.properties"
export VC_PKG="com.brainstormity.voicecontrol"
export VC_AVD="${VC_AVD:-Medium_Phone_API_36.1}"
unset _VC_ROOT
