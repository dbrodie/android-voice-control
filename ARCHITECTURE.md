# Android Voice Control — Architecture Contract

Port of brainstormity/System-One-Computer-Use (see `upstream/System-One-Computer-Use/`, esp. `jev_agent.py`
and `phone.py`) to a native Android app. The phone controls **itself**: no adb, no Mac in the loop.

## What the app does
1. A floating overlay bubble (mic icon) sits on top of everything (SYSTEM_ALERT_WINDOW), hosted by a
   foreground service.
2. Tap the bubble → a live voice session with OpenAI's Realtime API ("GPT live") starts. The user talks.
   GPT acts as the front-desk: it understands the request, optionally asks a clarifying question by voice,
   then calls a tool `control_phone(task: string)` with a crisp imperative task description
   (e.g. `Open Settings and turn on Wi-Fi`, `Open Chrome and search for "weather in Berlin"`).
3. The task goes to the **JEV agent loop** (ported from `jev_agent.py`): screenshot → ask JEV what to do
   (action + target box + text choice) → confidence gating → zoom-in refinement when unsure → execute via
   AccessibilityService → wait for the screen to settle → repeat until `DONE`, `unsure`, stopped or MAX_STEPS.
4. The overlay shows live status (listening / thinking / step N: TAP at … / done / error) and GPT speaks
   a short summary back when the task ends.

## Package layout (Kotlin, single module `app`, package `com.brainstormity.voicecontrol`)
```
core/        Shared contracts + state. Owned by the skeleton; others only ADD, never change signatures
             without telling the orchestrator.
agent/       Task loop (TaskRunner, TaskParser) + the System One seam: SystemOne (interface), JevSystemOne
             (adapter over JevClient), SystemOneBackends (registry + "Next-step model" chooser). [agent-loop, T15]
control/     PhoneAccessibilityService: implements Screen + Hands.                   [Agent: accessibility]
overlay/     OverlayService (foreground) + bubble/status UI (WindowManager views).  [Agent: overlay-ui]
voice/       RealtimeVoiceClient: OpenAI Realtime WS, mic in, speaker out, tool call.[Agent: voice]
ui/          MainActivity: permission setup checklist, settings (API keys), test box.[Agent: overlay-ui]
             ui/runs/: Runs & costs screens (port of upstream dashboard) + RunStore (list/trash/restore). [T9]
debug/       DebugReceiver: adb broadcast hooks for emulator testing (debug builds). [skeleton]
```

## core contracts (exact signatures — everyone codes against these)
```kotlin
package com.brainstormity.voicecontrol.core

enum class SwipeDirection { UP, DOWN, LEFT, RIGHT }
data class ScreenSize(val width: Int, val height: Int)

/** The eyes. Full-resolution screenshot of the whole display. */
interface Screen {
    suspend fun screenshot(): android.graphics.Bitmap
    fun screenSize(): ScreenSize
    suspend fun uiTree(): UiTree? = null   // (Phase 2, additive) raw accessibility tree, see core/UiTree.kt
}

/** The hands. Coordinates are real screen pixels. Each call returns when the gesture has been dispatched. */
interface Hands {
    suspend fun tap(x: Int, y: Int)
    suspend fun longPress(x: Int, y: Int)
    suspend fun swipe(direction: SwipeDirection)        // UP = finger moves up (scrolls content down)
    suspend fun allApps()                               // home, then swipe up from bottom edge
    suspend fun back()
    suspend fun home()
    suspend fun enter()
    suspend fun typeText(text: String)                  // into the focused field
    suspend fun replaceText(text: String) = typeText(text) // (T5, additive) replace the focused field's content
    suspend fun openApp(packageName: String)
    suspend fun openUrl(url: String)
    fun installedApps(): Set<String>                    // launchable package names
}

/** Where control/ registers itself so agent/ can find the live service. */
object Device {
    val screen: kotlinx.coroutines.flow.MutableStateFlow<Screen?>
    val hands:  kotlinx.coroutines.flow.MutableStateFlow<Hands?>
    val isReady: Boolean get() = screen.value != null && hands.value != null
}

sealed class AgentState {
    object Idle : AgentState()
    data class Running(val task: String, val step: Int, val action: String, val detail: String,
                       val confidence: Float) : AgentState()
    data class Finished(val task: String, val status: String /* done|unsure|stopped|gave_up|error */,
                        val message: String, val steps: Int, val costUsd: Double) : AgentState()
}

/** agent/ implements this as a singleton `TaskController`. */
interface TaskControllerApi {
    val state: kotlinx.coroutines.flow.StateFlow<AgentState>
    fun runTask(task: String)             // no-op if already running
    fun stop()
    val isRunning: Boolean
}

sealed class VoiceState {
    object Idle : VoiceState()
    object Connecting : VoiceState()
    object Listening : VoiceState()      // session open, waiting for user speech
    data class UserSaid(val transcript: String) : VoiceState()
    data class AssistantSaying(val transcript: String) : VoiceState()
    data class Error(val message: String) : VoiceState()
}

/** voice/ implements this as a singleton `VoiceController`. */
interface VoiceControllerApi {
    val state: kotlinx.coroutines.flow.StateFlow<VoiceState>
    fun start(context: android.content.Context)   // open realtime session, start mic
    fun stop()                                    // close session, stop mic
    fun sendText(text: String)                    // debug: inject a user text turn instead of speech
    fun tellUser(text: String)                    // make the assistant speak this (e.g. task result)
    val isActive: Boolean
}

object AppSettings {       // SharedPreferences-backed; defaults from BuildConfig (read from .env at build time)
    fun jevApiKey(ctx): String;    fun setJevApiKey(ctx, v)
    fun openAiApiKey(ctx): String; fun setOpenAiApiKey(ctx, v)
    fun realtimeModel(ctx): String // default from BuildConfig.OPENAI_REALTIME_MODEL
}
```
Also in core (additive): `OverlayInfo.regions` (overlay windows in screen pixels, published by overlay/) and
`OverlayInfo.visibilityProbe` (set by control/: is the overlay really on screen, or hidden by Settings?).
agent/ uses both to paint the overlay out of every screenshot (`agent/OverlayMask`) and to never tap it.
Also in core (Phase 2): `UiTree` / `UiWindow` / `UiNode` (raw accessibility snapshot; JSON schema in its KDoc) and
`UiTreeJson` (encode/decode). control/`UiTreeCapture` implements `Screen.uiTree()` (all windows, pre-order nodes,
3000-node / 300 ms budget, our overlay windows dropped); agent/laya/`LayaCandidates` is the byte-exact port of
Byun11/laya-android's candidate extraction + `[i] label (class) flags` serialization (golden-tested against the Python).

Wiring: `App` (Application) calls `TaskController.init`, `ChatGptAuth.init`, `VoiceBackends.install`.
`OverlayService` tap → `VoiceController.start()` (and adds the `microphone` FGS type for the session). `VoiceController` on tool call
`control_phone(task)` → `TaskController.runTask(task)` and, when `TaskController.state` becomes `Finished`,
sends the tool result back and lets GPT speak a one-sentence summary. Overlay observes both state flows.

## System One backends (T15) — `agent/SystemOne.kt`
The model that decides each step sits behind one interface; `TaskRunner` knows nothing else.
```kotlin
interface SystemOne {
    val id: String; val displayName: String; val pricePerMillionInput: Double   // 0.0 on-device
    val wantsUiTree: Boolean get() = false        // true: StepInput.uiTree is captured in every grab()
    fun availability(ctx: Context): Availability  // Ready | NeedsSetup(msg) | NeedsDownload(bytes) | Unsupported(reason)
    fun preflight(): String? = null               // Context-free last check; non-null = run ends "error" with it
    fun ask(input: StepInput): StepDecision       // blocking, background thread
    fun refine(input: StepInput, closeUp: CloseUp, closeupJpeg: ByteArray): Refinement? = null  // pixel backends
}
class StepInput(task, screenshotJpeg, screenshotW, screenshotH, uiTree: UiTree?, history, texts, urls, apps)
data class StepDecision(action, target, text, app, website: JevChoice/JevTarget?, usage, latencyMs, backend,
                        targetNodeId: Int?, extras: Map<String, Any?>)
open class SystemOneException(message, cause, httpCode)   // JevException extends it
```
- `JevChoice`/`JevTarget`/`JevUsage`/`Box` are the shared answer vocabulary (choice keys = PHONE_ACTIONS names or
  option indexes; boxes 0..1 of the screenshot). `JevSystemOne` wraps the unchanged `JevClient` (golden bodies).
- `SystemOneBackends`: registry (`register(backend)` replaces by id; JEV + `PlaceholderLayaSystemOne` built in)
  and `current(ctx)`. Setting `AppSettings.systemOneBackend`: `auto` (default; Laya if Ready, else JEV) | `jev` |
  `laya`; an explicit choice wins even when not available. `TaskController.runTask` refuses a backend whose
  availability isn't Ready with status "error" and the availability message (JEV without key: NO_KEY_MESSAGE).
- The runner zooms in (`refine`) only for CLICKS with low target confidence or a repeated miss, and skips the zoom
  when `refine` returns null. User-facing sentences use `displayName` ("JEV was only 40% sure ...").
- Reports: run-level `backend`, `backend_name`, `ui_tree_nodes`; per step `latency_ms` (alongside the kept
  `jev_seconds`), `backend`, `backend_latency_ms`, `target_node_id`, `backend_extras`. Runs screens show a backend
  chip and per-step latency; MainActivity has the "Next-step model" card. Debug: `RUN_TASK --es system_one laya`.

## JEV API (TypeSafe System One) — port exactly from `jev_agent.py`
- `POST https://api.typesafe.ai/v1/systemone`, headers `Authorization: Bearer <JEV_KEY>`,
  `TypeSafe-Danger-Zone-Images: true`, JSON body `{model:"jev-1.14.0", state:{...}, questions:{...}, assets:{...}}`.
- Response: `{"answers": {"action": {"choice","confidence","probabilities"}, "target": {"box":{left,top,right,bottom (0..1)}, "confidence"}, "text": {"choice"}, ...}, "usage": {"input_tokens","output_tokens"}}`.
- Use `PHONE_ACTIONS`, confidence thresholds, `CHAIN` logic, `refine_target` zoom, `wait_for_screen`
  settle logic. Screenshot shrunk so max side ≤ 1568 px, JPEG q85, base64.
- Replace `OPEN_WEBSITE` with an ACTION_VIEW intent; `OPEN_APP` with launch intent; `ALL_APPS` = home + swipe.
- The upstream's "text options" come only from the task string (quoted text + 1–3 word phrases). Keep that:
  JEV never writes free text. GPT's tool call produces the task string, so GPT quotes what should be typed.

## Keys / config
- `.env` in project root (gitignored) holds `JAVA_HOME`, `ANDROID_HOME`, `JEV_KEY`, and `OPENAI_API_KEY`
  (when provided). `app/build.gradle.kts` reads `.env` and exposes `BuildConfig.JEV_KEY`,
  `BuildConfig.OPENAI_API_KEY`, `BuildConfig.OPENAI_REALTIME_MODEL`. Settings screen can override at runtime.
- Keys are embedded in **debug builds only** (and not with `EMBED_KEYS=false`); release builds always have
  `""`. Users enter their JEV key in `ui/JevKeyActivity` (verified live, stored encrypted via
  `core/SecurePrefs`). See docs/SKELETON.md "BuildConfig / .env".

## Emulator / testing conventions
- AVD: `Medium_Phone_API_36.1` (Android 16, API 36.1). Start with
  `$ANDROID_HOME/emulator/emulator -avd Medium_Phone_API_36.1 -no-snapshot-load -no-boot-anim &` and wait for
  `adb shell getprop sys.boot_completed` = 1. Only ONE emulator instance: check `adb devices` first.
- Build: `./gradlew :app:assembleDebug` from project root with env from `.env`. Install: `adb install -r`.
- Grant without UI (debug): overlay `adb shell appops set com.brainstormity.voicecontrol SYSTEM_ALERT_WINDOW allow`;
  accessibility `adb shell settings put secure enabled_accessibility_services com.brainstormity.voicecontrol/.control.PhoneAccessibilityService && adb shell settings put secure accessibility_enabled 1`;
  mic `adb shell pm grant com.brainstormity.voicecontrol android.permission.RECORD_AUDIO`.
- Debug broadcasts (debug builds only, `debug/DebugReceiver`):
  `adb shell am broadcast -a com.brainstormity.voicecontrol.RUN_TASK --es task "Open Settings"`
  `adb shell am broadcast -a com.brainstormity.voicecontrol.STOP`
  `adb shell am broadcast -a com.brainstormity.voicecontrol.VOICE_START` / `VOICE_STOP`
  `adb shell am broadcast -a com.brainstormity.voicecontrol.VOICE_TEXT --es text "open settings and turn on wifi"`
  `adb shell am broadcast -a com.brainstormity.voicecontrol.DUMP_TREE [--es name settings]` (`debug/TreeDebugReceiver`):
  writes the current `UiTree` JSON to `files/trees/<yyyyMMdd-HHmmss>[-name].json` and logs path, counts, capture ms
  and the Laya-Android candidate lines under `VC/Tree`; pull with
  `adb exec-out run-as com.brainstormity.voicecontrol cat files/trees/<file>.json > tree.json`
- Log tag prefix: `VC/` (e.g. `VC/Agent`, `VC/Voice`, `VC/Hands`, `VC/Overlay`). `adb logcat -s VC/Agent:V ...`
- Toolchain: minSdk 30 (takeScreenshot needs API 30), targetSdk 36, compileSdk 36, Kotlin, no Compose
  (plain Views for the overlay; Activity may use Views too). Gradle wrapper; cached distributions available:
  gradle-8.11.1, 8.13, 9.3.0. JDK = Android Studio JBR 21.
