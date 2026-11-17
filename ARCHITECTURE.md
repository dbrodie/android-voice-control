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
             agent/laya/: on-device Laya-Android backend (candidates, tokenizer, sequence, LiteRT engine,
             LayaSystemOne, model files / download / import).                       [T16, T17]
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
- `SystemOneBackends`: registry (`register(backend)` replaces by id; JEV + `PlaceholderLayaSystemOne` built in, the
  placeholder replaced by `LayaSystemOne` in `App.onCreate`, T17)
  and `current(ctx)`. Setting `AppSettings.systemOneBackend`: `auto` (default; Laya if Ready, else JEV) | `jev` |
  `laya`; an explicit choice wins even when not available. `TaskController.runTask` refuses a backend whose
  availability isn't Ready with status "error" and the availability message (JEV without key: NO_KEY_MESSAGE).
- The runner zooms in (`refine`) only for CLICKS with low target confidence or a repeated miss, and skips the zoom
  when `refine` returns null. User-facing sentences use `displayName` ("JEV was only 40% sure ...").
- Reports: run-level `backend`, `backend_name`, `ui_tree_nodes`; per step `latency_ms` (alongside the kept
  `jev_seconds`), `backend`, `backend_latency_ms`, `target_node_id`, `backend_extras`. Runs screens show a backend
  chip and per-step latency; MainActivity has the "Next-step model" card. Debug: `RUN_TASK --es system_one laya`.

## On-device Laya (T17) — `agent/laya/`
`App.onCreate` registers `LayaSystemOne(this)` under id `laya` (replacing T15's placeholder). Spec of the model
input: `tools/laya/HOST_CONTRACT.md` (executable reference `tools/laya/host.py`).
- `LayaCandidates` (T16): Byun11 candidate rules + `[i] label (class) flags` lines; `ground`, `historyEntry`.
- `LayaTokenizer`: HF `tokenizer.json` (mmBERT BPE, byte fallback, Metaspace, 249 added tokens), streamed by
  `LayaJsonReader`; ignores the file's stale `truncation: 48`. `LayaSequence`: Python-identical state JSON
  (hand-written `json.dumps(ensure_ascii=False)`), one sequence per question
  (`[CLS] choice question: … [SEP] [MASK] opt … [SEP] state [SEP]`, head_max_len 1536, options ≤ 48 tokens,
  state right-truncated), smallest window of 256/512/1024/2048 that holds every question untruncated.
  `LayaDecoder`: softmax(logit/T), T = 1.1609 from `rl_agent_config.json`; gate on max(p).
- `LayaEngine` (one per process, lazy, single "Laya-LiteRT" thread; `score()` blocks): LiteRT 2.3.2
  `CompiledModel` on GPU (FP32) → CPU (XNNPACK); a GPU with non-finite output falls back to CPU; a backend that
  crashed the process while compiling (marker `compiling.txt`) is skipped next time. `CPU_REF` = classic
  `Interpreter` with XNNPACK off, auto-selected on emulators whose CPU advertises SME (Apple M4/M5 hosts: XNNPACK
  executes `rdsvl` and dies with SIGILL); it runs one signature only (s512), since each signature it touches
  materialises ~0.45 GB of fp32 weights, and if `laya_android_s512_embeds_fp32.tflite` is present (dev/emulator
  only) it uses that instead (weights stay mmapped: ~650 MB PSS instead of > 1.2 GB, which lmkd killed on the 2 GB
  AVD). `auto` never tries the GPU on an emulator: the failed gfxstream GL delegate init corrupted the process's GL
  state and HWUI then crashed in screenshot readback. Multi-signature graph `laya_android_multi_embeds_wfp16.tflite`
  (`s256`…`s2048`); single-window files are a fallback if a signature can't be bound. Inputs: mmapped fp16 token
  table gathered to fp32 `inputs_embeds` (padding = row 0), `attention_mask`, `qtype_onehot`; output
  `token_logits` read at the `[MASK]` markers. The act head is not used. Logs: `VC/Laya`.
- `LayaSystemOne.ask`: no tree → `SystemOneException(NO_TREE)`; 0 candidates → operation only, tap ops dropped;
  state `{goal, history: last 3 via LayaHistory, ui}`; questions `operation` (Byun11's 12 ops) + `target`;
  candidates dropped from the end while a question doesn't fit. `LayaPolicy`: ops → PHONE_ACTIONS
  (SCROLL_UP/DOWN same, SCROLL_RIGHT → SWIPE_LEFT, SCROLL_LEFT → SWIPE_RIGHT: AndroidControl names the content
  revealed, our SwipeDirection is the finger), INPUT_TEXT → TYPE_ENTER for search-looking fields else TYPE, typed
  into Laya's target if editable else the focused field; probabilities = mapped mass over offered actions;
  target box = element bounds / screen, confidence 1.0, `targetNodeId` = UiNode.id; text from the task (single
  option, quoted text, "search for X"), else Laya's untrained `text` question. `extras`: candidates, dropped,
  laya_op(+p), target_id/p/label, window, question_ms, engine, load_ms, history.
- `LayaHistory`: TaskRunner's pixel history → Laya's (`CLICK <label>` grounded on that step's candidates,
  `INPUT_TEXT "…"`, swipes inverted, "(nothing changed on screen)" dropped).
- Model files (`LayaModelFiles`): `files/models/laya-android/` = `laya_android_multi_embeds_wfp16.tflite`,
  `token_embeddings_fp16.bin`, `tokenizer.json`, `rl_agent_config.json` (702,944,470 bytes; sizes + sha256 from
  `assets/laya/MANIFEST.json` = `tools/laya/out/MANIFEST.json`). Sizes checked on every availability call, sha256
  once per file version (`verified.txt`). Availability: Unsupported if not arm64-v8a/x86_64 or < 3 GB RAM (debug
  override `LAYA_CONFIG --ez allow_low_ram true`), NeedsDownload(missing bytes), Ready. `LayaDownload`: resumable
  (HTTP Range) OkHttp download from `AppSettings.layaModelBaseUrl` (default the `models-v1` GitHub release), sha256
  checked before the rename. Import: ACTION_OPEN_DOCUMENT of the files or a .zip. UI: `ui/LayaModelCard` in the
  Next-step model card. `AppSettings.layaAccelerator`: auto | gpu | cpu | cpu_ref.
- APK: LiteRT needs `-Xskip-metadata-version-check` (its Kotlin API is built with Kotlin 2.4 metadata), GPU
  `uses-native-library` entries (optional), armeabi-v7a LiteRT libs excluded (Laya is 64-bit only).

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
  On-device Laya (`debug/LayaDebugReceiver`, logs `VC/Laya`): model files into a debug build with
  `tools/laya/push_to_device.sh` (adb push + `run-as cp`), or push to the app's external dir and
  `IMPORT_LAYA [--es dir <dir>] [--ez delete true]`; `LAYA_CONFIG [--es accelerator auto|gpu|cpu|cpu_ref]
  [--ez allow_low_ram true] [--es base_url …]`; `LAYA_STATUS`; `LAYA_RELEASE`; `LAYA_PARITY [--ei window N]
  [--es rows a,b]` runs the 24 T14 fixture rows (debug asset `laya/parity.json`, from
  `tools/laya/make_kotlin_assets.py`) through the real engine and logs `PARITY … argmax_ok … max_diff …`.
- Log tag prefix: `VC/` (e.g. `VC/Agent`, `VC/Voice`, `VC/Hands`, `VC/Overlay`). `adb logcat -s VC/Agent:V ...`
- Toolchain: minSdk 30 (takeScreenshot needs API 30), targetSdk 36, compileSdk 36, Kotlin, no Compose
  (plain Views for the overlay; Activity may use Views too). Gradle wrapper; cached distributions available:
  gradle-8.11.1, 8.13, 9.3.0. JDK = Android Studio JBR 21.
