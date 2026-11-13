# Skeleton notes

The skeleton follows the contract in `ARCHITECTURE.md`. Everything outside `core/` and `debug/` is a stub
that one of the other agents replaces.

## Versions
| Thing | Version | Why |
|---|---|---|
| Gradle (wrapper) | 8.13 (`gradle/wrapper/gradle-wrapper.properties`) | Already cached in `~/.gradle/wrapper/dists` |
| Android Gradle Plugin | 8.13.2 | Cached; supports compileSdk 36 and runs on JDK 21 |
| Kotlin | 2.1.0 (`org.jetbrains.kotlin.android`) | Cached; jvmTarget 17 |
| JDK running Gradle | Android Studio JBR 21 (`JAVA_HOME` from `.env`) | |
| compileSdk / targetSdk / minSdk | 36 / 36 / 30 | |
| kotlinx-coroutines-android | 1.9.0 | |
| OkHttp | 4.12.0 | For the JEV HTTP client and the Realtime WebSocket |
| androidx.core:core-ktx | 1.15.0 | |
| androidx.appcompat | 1.7.0 | Theme `Theme.AppCompat.DayNight.DarkActionBar`, no Material lib, no Compose |
| JSON | **`org.json`** (built into Android) | No extra dependency or compiler plugin. Use `JSONObject` / `JSONArray` |

Versions are in `gradle/libs.versions.toml`. Java/Kotlin bytecode target is 17.

## Build / install / test
```bash
source scripts/env.sh        # loads .env (JAVA_HOME, ANDROID_HOME, keys) and puts adb + emulator on PATH
scripts/emulator.sh          # boots Medium_Phone_API_36.1 unless a device is attached, waits for boot (idempotent)
scripts/build.sh             # ./gradlew :app:assembleDebug  -> app/build/outputs/apk/debug/app-debug.apk
scripts/install.sh           # adb install -r -g, overlay appop, RECORD_AUDIO, POST_NOTIFICATIONS, accessibility on
scripts/logs.sh [-c]         # logcat filtered to VC/* tags + crashes (-c clears the buffer first)
adb shell am start -n com.brainstormity.voicecontrol/.ui.MainActivity
adb shell am broadcast -a com.brainstormity.voicecontrol.RUN_TASK --es task "'Open Settings'"
```
Expected logcat after install: `I/VC/Hands: onServiceConnected: PhoneAccessibilityService connected`
and `D/VC/Debug: runtime DebugReceiver registered`.

## BuildConfig / .env
`app/build.gradle.kts` parses the project-root `.env`: `KEY=VALUE`, optional `export `, single or
double quotes, `#` comment lines, and ` #` inline comments on unquoted values. It exposes:
- `BuildConfig.JEV_KEY` (`""` if absent)
- `BuildConfig.OPENAI_API_KEY` (`""` if absent)
- `BuildConfig.OPENAI_REALTIME_MODEL` (default `"gpt-live-1"`)

**Keys are embedded in debug builds only.** The `release` build type overrides `JEV_KEY` and
`OPENAI_API_KEY` with `""` whatever `.env` says, so a release APK never contains a key: users enter their
own JEV key in the app (`ui/JevKeyActivity`) and voice uses ChatGPT sign-in or an OpenAI key from Settings.
Debug builds embed the `.env` keys for convenience unless `EMBED_KEYS=false` (in `.env` or as an
environment variable, e.g. `EMBED_KEYS=false ./gradlew :app:assembleDebug`). Use that to test the
"no key yet" first-run path. To check an APK (prints counts, never the key):
```bash
d=$(mktemp -d) && unzip -q app/build/outputs/apk/release/app-release.apk -d $d && \
  grep -r -l -a apikey_ $d | wc -l   # expect 0 (all JEV keys start with apikey_)
```

`AppSettings` (SharedPreferences `vc_settings`) returns the stored value, or the BuildConfig default when
nothing is stored or the stored value is blank. It also has `setRealtimeModel(ctx, v)` in addition to the
contract. If you edit `.env`, rebuild so BuildConfig picks up the change.

The JEV key is special (T7):
- Stored **encrypted** under `jev_api_key_enc` with `core/SecurePrefs` (AES-256-GCM, Android Keystore alias
  `vc_jev_key`; the same scheme as `voice/auth/TokenStore`). A plain `jev_api_key` left by older builds is
  migrated (encrypted, plain value removed) on first read. `AppSettings.jevApiKey()` = stored key, else
  `BuildConfig.JEV_KEY`, else `""`. Extra helpers: `storedJevApiKey`, `jevKeyCheck`/`setJevKeyCheck` (last
  verification text + time), `jevKeyPrompted`/`setJevKeyPrompted` (first-run routing done).
- `ui/JevKeyActivity` verifies a pasted key with `agent/JevKeyProbe`: one text-only `choice` question
  (~315 input tokens, ≈ $0.00001) to `/v1/systemone`. 200 (or 400/422: the server checks auth before the
  body) = valid; 401/403 = invalid; anything else / network error = inconclusive ("Save without
  verifying"). Logs `VC/JevKey: probe: HTTP <code>: <first 200 chars of the body>`, never the key; the UI
  shows only `JevKeyProbe.mask()` (first 12 chars + "…").
- No key → `TaskController.runTask` finishes at once with `error` / "Add your JEV API key in Voice Control".
  JEV 401/403 mid-run → `error` / "JEV rejected the API key — check it in Voice Control" (the HTTP body is
  in the exception's cause, logged by `VC/Agent`). Both reach the overlay pill and GPT through
  `Finished.message`.
- MainActivity: Setup row "JEV API key" (also mirrored in Settings, replacing the old plain-text field);
  on first run, once ChatGPT sign-in is done or an OpenAI key is set, it opens JevKeyActivity once.
- Debug: `adb exec-out run-as com.brainstormity.voicecontrol cat shared_prefs/vc_settings.xml` must show
  only the encrypted blob.

## Gotchas
- **Multi-word broadcast extras need nested quotes.** `adb shell` joins its arguments and the device shell
  splits them again, so `--es task "Open Settings"` arrives as just `Open`. Use
  `--es task "'Open Settings'"` (or pass the whole command as one quoted string:
  `adb shell 'am broadcast -a ... --es task "Open Settings"'`).
- **Implicit broadcasts don't reach manifest receivers (Android 8+).** The ARCHITECTURE commands send
  `am broadcast -a ...` with no `-p`. To make them work, `src/debug/` also has a `DebugReceiverInstaller`
  ContentProvider that registers a runtime copy of `DebugReceiver` (`RECEIVER_EXPORTED`) when the
  process starts. The process stays alive while the accessibility service is enabled, so plain
  `-a` broadcasts work. The manifest receiver handles explicit broadcasts (`-p com.brainstormity.voicecontrol`),
  which also cold-start the process. The runtime copy ignores explicit intents, so nothing is handled twice.
- **This AVD already has another accessibility service enabled** (`com.artemis.helper/.ArtemisAccessibilityService`).
  Instead of overwriting `enabled_accessibility_services` as ARCHITECTURE.md's one-liner does,
  `install.sh` appends our service to the colon-separated list. It removes ours and re-adds it so the
  service binds again after reinstalling. Also, `settings put ... ""` fails with "Bad arguments", so use
  `settings delete`.
- `install.sh` uses `adb install -r -g`, which grants runtime permissions at install time.
- The system may log `ForegroundServiceTypeLoggerModule: ... does not have any types` when OverlayService
  starts. You can ignore it: `dumpsys activity services` shows `isForeground=true types=0x40000000` (specialUse).
- With targetSdk 36, edge-to-edge is enforced. The stub layout uses `fitsSystemWindows`. The real UI should
  handle insets itself.
- The emulator runs with `-no-snapshot-load -no-boot-anim -no-audio` (log: `/tmp/vc-emulator.log`). Leave
  out `-no-audio` if the voice agent needs emulator audio, or start the emulator manually.
  Note: `emulator.sh` does nothing if *any* device is attached.
- `local.properties` (`sdk.dir`) is gitignored. `scripts/env.sh` creates it if it's missing.

## Stubs (to be replaced)
- `agent/TaskController` (object : TaskControllerApi). `runTask` logs and sets
  `Finished(status="error", message="agent not implemented")`.
- `voice/VoiceController` (object : VoiceControllerApi). Logs only. `start()` sets `VoiceState.Error`.
- `control/PhoneAccessibilityService`: `onServiceConnected` puts a stub Screen+Hands into
  `Device.screen/hands` (logs every call; `screenshot()` returns a blank bitmap). `onUnbind`/`onDestroy` clear them.
- `overlay/OverlayService`: foreground service (specialUse, channel `overlay`, notification id 1). Helpers
  `OverlayService.start(ctx)` / `stop(ctx)`. It draws nothing yet.
- `ui/MainActivity` + `res/layout/activity_main.xml`: a title and a "Start overlay" button that asks for
  POST_NOTIFICATIONS and starts OverlayService.

Logging: use `VcLog.d/i/w/e(tag, msg[, throwable])` from `core/Log.kt`. It adds the `VC/` prefix, so use
`VcLog.d("Agent", ...)` for the `VC/Agent` tag.

## Files created
```
settings.gradle.kts
build.gradle.kts
gradle.properties
gradle/libs.versions.toml
gradle/wrapper/gradle-wrapper.jar
gradle/wrapper/gradle-wrapper.properties
gradlew
gradlew.bat
local.properties                      (gitignored)
app/build.gradle.kts
app/proguard-rules.pro                (empty)
app/src/main/AndroidManifest.xml
app/src/main/java/com/brainstormity/voicecontrol/core/Device.kt         (SwipeDirection, ScreenSize, Screen, Hands, Device)
app/src/main/java/com/brainstormity/voicecontrol/core/AgentState.kt     (AgentState, TaskControllerApi)
app/src/main/java/com/brainstormity/voicecontrol/core/VoiceState.kt     (VoiceState, VoiceControllerApi)
app/src/main/java/com/brainstormity/voicecontrol/core/AppSettings.kt
app/src/main/java/com/brainstormity/voicecontrol/core/Log.kt            (VcLog)
app/src/main/java/com/brainstormity/voicecontrol/agent/TaskController.kt         (stub)
app/src/main/java/com/brainstormity/voicecontrol/voice/VoiceController.kt        (stub)
app/src/main/java/com/brainstormity/voicecontrol/control/PhoneAccessibilityService.kt (stub)
app/src/main/java/com/brainstormity/voicecontrol/overlay/OverlayService.kt       (stub)
app/src/main/java/com/brainstormity/voicecontrol/ui/MainActivity.kt              (stub)
app/src/main/res/xml/accessibility_service_config.xml
app/src/main/res/values/strings.xml
app/src/main/res/values/themes.xml
app/src/main/res/layout/activity_main.xml
app/src/main/res/drawable/ic_mic.xml   (also used as the launcher icon)
app/src/debug/AndroidManifest.xml
app/src/debug/java/com/brainstormity/voicecontrol/debug/DebugReceiver.kt  (DebugReceiver + DebugReceiverInstaller)
scripts/env.sh scripts/emulator.sh scripts/build.sh scripts/install.sh scripts/logs.sh
docs/SKELETON.md
```
