# OpenAI Realtime API: reference for the Android client

Verified against the live OpenAI docs on **2026-11-13**. Source URLs are listed at the end.
The docs moved from `platform.openai.com/docs/...` to `developers.openai.com/api/docs/...`, and the old URLs return 301. Add `.md` to any docs URL to get its raw Markdown.

> **Naming: "GPT-Live" and "Realtime API" are now two different products.**
> * **Realtime API**: `wss://api.openai.com/v1/realtime?model=...`. A single speech-to-speech model (`gpt-realtime-2.1`) interprets audio, calls *your* function tools directly, and speaks. It is billed per token. **This doc covers the Realtime API.** It is the simplest fit for `control_phone(task)`.
> * **GPT-Live**: `wss://api.openai.com/v1/live/sessions`, model `gpt-live-1`. It is a full-duplex voice model that *delegates* reasoning and tools to a separate backend (an OpenAI Responses model, or your own agent). It uses a different event schema: `session.start`, `session.input_audio.append`, `session.output_audio.delta`, `session.closed`. It is billed **$0.05/min** (per second) plus backend usage. None of the event names below apply to it. See "Getting started with GPT-Live" if you want full duplex later.

---

## 1. Models and pricing

| Model id | Notes | Audio in / cached / out (per 1M tok) | Text in / cached / out |
|---|---|---|---|
| **`gpt-realtime-2.1`** (recommended) | Reasoning and tool use. Better alphanumerics, noise and silence handling, and interruptions than `-2`. Context 128k, max output 32k. | $32 / $0.40 / $64 | $4 / $0.40 / $24 |
| **`gpt-realtime-2.1-mini`** (cheap) | Distilled. Still supports function calling and reasoning. | $10 / $0.30 / $20 | $0.60 / $0.06 / $2.40 |
| `gpt-realtime-2` | Previous reasoning model | $32 / $0.40 / $64 | $4 / $0.40 / $24 |
| `gpt-realtime-1.5` | Non-reasoning, fast | $32 / $0.40 / $64 | $4 / $0.40 / $16 |
| `gpt-realtime`, `gpt-realtime-mini` | Legacy GA (2025) ids, still accepted | same as 1.5 / mini | |

The `session.model` enum also still accepts dated snapshots (`gpt-realtime-2025-08-28`, `gpt-realtime-mini-2025-12-15`) and the `gpt-4o-*-realtime-preview` ids. Don't use them in new code.

**Per-minute estimate** (from the cost guide: user audio = 1 token per 100 ms = 600 tok/min; assistant audio = 1 token per 50 ms = 1,200 tok/min):
* `gpt-realtime-2.1`: about $0.019 per minute of user speech plus about $0.077 per minute of model speech, before context re-reads.
* `gpt-realtime-2.1-mini`: about $0.006/min in and about $0.024/min out.
* **Every response re-sends the whole conversation as input.** Later turns cost more. Prompt caching is automatic: cached audio input is $0.30–0.40/1M. Keep the instructions and tools stable mid-session, because changing them busts the cache. You can cap context with `session.truncation` (see §3).
* Input transcription, if enabled, is billed separately by the transcription model (for example `gpt-transcribe` $0.0045/min, `gpt-4o-mini-transcribe` $0.003/min est.).
* Per-response usage arrives in `response.done.response.usage` (`input_token_details.audio_tokens`, `cached_tokens`, and so on).
* **Reasoning:** `session.reasoning.effort` takes `minimal|low|medium|high|xhigh` and defaults to `low`. Higher values add latency and output tokens. For simple phone commands, the prompting guide suggests `minimal` or `low`.

Default rate limits from the model pages: Build tier 400 RPM / 200k TPM, Launch 10k RPM / 4M TPM, Grow 20k RPM / 15M TPM. The server pushes `rate_limits.updated` after responses.

## 2. Connecting (WebSocket, OkHttp)

```
URL:     wss://api.openai.com/v1/realtime?model=gpt-realtime-2.1
Headers: Authorization: Bearer <OPENAI_API_KEY or ephemeral ek_... secret>
         OpenAI-Safety-Identifier: <hashed user id>      (optional, recommended)
```
* **Do NOT send `OpenAI-Beta: realtime=v1`.** The migration guide says: "Remove the `OpenAI-Beta: realtime=v1` header when calling the GA interface."
* The model is chosen by the URL query param. `session.update` **cannot change `model`**, and `voice` can only change before the first audio output.
* The first server event is `session.created`, which carries the full default session config. Then send `session.update` and wait for `session.updated`.
* **Max session duration: 60 minutes** ("The maximum duration of a Realtime session is 60 minutes"). `session.created.session.expires_at` gives the exact time.
* **API key directly from the phone.** It works. A standard key authenticates any WS connection. OpenAI recommends this only for trusted servers, and recommends WebRTC or ephemeral tokens for browser and mobile clients. For a personal-use app, embedding your own key is an acceptable risk (keep it out of git, `.env` and BuildConfig only). **Alternative:** a tiny backend calls `POST https://api.openai.com/v1/realtime/client_secrets` with the real key and returns `{"value":"ek_...","expires_at":...,"session":{...}}`. The app then uses `Authorization: Bearer ek_...`. Request body: `{"expires_after":{"anchor":"created_at","seconds":600},"session":{"type":"realtime","model":"gpt-realtime-2.1",...}}`. `seconds` ranges from 10 to 7200 and defaults to 600. Expiry only limits *starting* sessions: a running session continues.
* OkHttp: `OkHttpClient.Builder().pingInterval(20, SECONDS).readTimeout(0, MILLISECONDS)`. `WebSocket.send()` returns `false` once the outgoing queue passes 16 MiB, so watch `queueSize()` on poor networks.

```kotlin
val req = Request.Builder()
    .url("wss://api.openai.com/v1/realtime?model=gpt-realtime-2.1")
    .header("Authorization", "Bearer $apiKey")
    .build()
val ws = client.newWebSocket(req, listener)   // all messages are JSON text frames
```

## 3. `session.update` (full example for this app)

GA shape: `session.type: "realtime"` is required, and audio settings are nested under `audio.input` / `audio.output`. **Formats are objects, not strings.** Fields not present stay unchanged. To clear: `instructions` → `""`, `tools` → `[]`, `turn_detection` → `null`.

```json
{
  "type": "session.update",
  "session": {
    "type": "realtime",
    "instructions": "You are a hands-free phone assistant. When the user asks you to do something on the phone, call control_phone with a clear, complete task description. Keep spoken replies short. Only say an action is done after control_phone returns success.",
    "output_modalities": ["audio"],
    "reasoning": { "effort": "low" },
    "audio": {
      "input": {
        "format": { "type": "audio/pcm", "rate": 24000 },
        "noise_reduction": { "type": "near_field" },
        "transcription": { "model": "gpt-4o-mini-transcribe", "language": "en" },
        "turn_detection": {
          "type": "semantic_vad",
          "eagerness": "auto",
          "create_response": true,
          "interrupt_response": true
        }
      },
      "output": {
        "format": { "type": "audio/pcm", "rate": 24000 },
        "voice": "marin",
        "speed": 1.0
      }
    },
    "tools": [
      {
        "type": "function",
        "name": "control_phone",
        "description": "Perform an action on the user's Android phone (open apps, tap, type, send messages, change settings). Call when the user asks you to do something on the phone. Tell the user briefly what you're doing.",
        "parameters": {
          "type": "object",
          "properties": {
            "task": {
              "type": "string",
              "description": "Complete natural-language description of the task to perform, including all names, numbers and message text."
            }
          },
          "required": ["task"]
        }
      }
    ],
    "tool_choice": "auto",
    "max_output_tokens": "inf",
    "truncation": "auto"
  }
}
```
Field notes (from the client-events reference):
* `output_modalities`: `["audio"]` (the default, audio plus transcript) **or** `["text"]`. You can't request both.
* `audio.*.format`: `{"type":"audio/pcm","rate":24000}` (**24 kHz is the only PCM rate**), `{"type":"audio/pcmu"}`, or `{"type":"audio/pcma"}`. PCM is 16-bit signed little-endian mono.
* `voice`: `alloy, ash, ballad, coral, echo, sage, shimmer, verse, marin, cedar`. OpenAI recommends **marin/cedar**.
* `noise_reduction`: `{"type":"near_field"|"far_field","strength":0..1}` or `null`. Phone held to face is `near_field`; speakerphone on a desk is `far_field`.
* `transcription.model`: `whisper-1, gpt-transcribe, gpt-live-transcribe, gpt-4o-mini-transcribe, gpt-4o-transcribe, gpt-4o-transcribe-diarize, gpt-realtime-whisper`. Transcription is off by default and runs asynchronously, separate from what the model "heard".
* **`server_vad`** params: `threshold` (0–1, default 0.5), `prefix_padding_ms` (default 300), `silence_duration_ms` (default 500), `idle_timeout_ms` (5000–30000; auto-prompts the model after silence and emits `input_audio_buffer.timeout_triggered`), `create_response`, `interrupt_response` (both default true).
* **`semantic_vad`** params: `eagerness` takes `low|medium|high|auto` (auto = medium; max waits 8 s / 4 s / 2 s), plus `create_response` and `interrupt_response`.
* `create_response:false` + `interrupt_response:false`: VAD events still fire, but you decide when to call `response.create`.
* `turn_detection: null`: push-to-talk. You send `input_audio_buffer.commit` + `response.create` yourself.
* `tool_choice`: `"auto" | "none" | "required" | {"type":"function","name":"control_phone"}`. `parallel_tool_calls` (bool) applies only to reasoning models.
* Cost control: `"truncation": {"type":"retention_ratio","retention_ratio":0.8,"token_limits":{"post_instructions":8000}}`.

## 4. Streaming microphone audio in

```json
{ "type": "input_audio_buffer.append", "audio": "<base64 of raw PCM16 LE mono 24 kHz bytes>" }
```
* There is no ack. The maximum is 15 MiB per event. Smaller chunks make VAD more responsive. **20–100 ms per event** is sensible (24 kHz × 2 B = 48,000 B/s, so 40 ms = 1,920 B). Send raw samples with no WAV header, and always use an even byte count.
* With VAD on, the **server commits automatically**. You see `input_audio_buffer.speech_started` → `input_audio_buffer.speech_stopped` → `input_audio_buffer.committed`, then a response is auto-created if `create_response` is true.
* `input_audio_buffer.commit` turns the buffer into a user message item and triggers transcription. It does **not** create a response. It errors if the buffer is empty (users report "expected at least 100ms of audio"). Use it only with `turn_detection: null`.
* `input_audio_buffer.clear` discards uncommitted audio. The server answers with `input_audio_buffer.cleared`.

Server VAD events:
```json
{"type":"input_audio_buffer.speech_started","event_id":"event_1516","audio_start_ms":1000,"item_id":"msg_003"}
{"type":"input_audio_buffer.speech_stopped","event_id":"event_1718","audio_end_ms":2000,"item_id":"msg_003"}
{"type":"input_audio_buffer.committed","event_id":"event_1121","previous_item_id":"msg_001","item_id":"msg_002"}
```

## 5. Server events to handle

Typical order for one turn: `input_audio_buffer.speech_started/stopped/committed` → `conversation.item.added` / `conversation.item.done` (user item) → `response.created` → `response.output_item.added` → `response.content_part.added` → many `response.output_audio.delta` + `response.output_audio_transcript.delta` → `response.output_audio.done` → `response.output_audio_transcript.done` → `response.content_part.done` → `response.output_item.done` → (more items, for example a function_call) → `response.done` → `rate_limits.updated`. The user transcript (`conversation.item.input_audio_transcription.*`) arrives **asynchronously**, often after the response has started. Insert it into the UI by `item_id`.

GA renames to watch for: `response.audio.delta` → **`response.output_audio.delta`**, `response.audio_transcript.*` → **`response.output_audio_transcript.*`**, `response.text.*` → **`response.output_text.*`**, `conversation.item.created` → **`conversation.item.added` / `conversation.item.done`** (the reference still lists `conversation.item.created` as legacy).

**(a) Audio to play.** Base64-decode `delta` and queue it in order. `response.output_audio.done` and `response.done` contain **no** audio bytes.
```json
{"type":"response.output_audio.delta","event_id":"event_4950","response_id":"resp_001","item_id":"msg_008","output_index":0,"content_index":0,"delta":"Base64EncodedAudioDelta"}
{"type":"response.output_audio.done","event_id":"event_5152","response_id":"resp_001","item_id":"msg_008","output_index":0,"content_index":0}
```
**(b) Assistant transcript**
```json
{"type":"response.output_audio_transcript.delta","response_id":"resp_001","item_id":"msg_008","output_index":0,"content_index":0,"delta":"Hello, how can I a"}
{"type":"response.output_audio_transcript.done","response_id":"resp_001","item_id":"msg_008","output_index":0,"content_index":0,"transcript":"Hello, how can I assist you today?"}
```
**(c) User transcript.** Only arrives if `audio.input.transcription` is set.
```json
{"type":"conversation.item.input_audio_transcription.delta","item_id":"item_CCX...","content_index":0,"delta":"Hey"}
{"type":"conversation.item.input_audio_transcription.completed","item_id":"item_CCX...","content_index":0,"transcript":"Hey, can you hear me?","usage":{"type":"tokens","total_tokens":22,"input_tokens":13,"input_token_details":{"text_tokens":0,"audio_tokens":13},"output_tokens":9}}
{"type":"conversation.item.input_audio_transcription.failed","item_id":"msg_003","content_index":0,"error":{"type":"transcription_error","code":"audio_unintelligible","message":"The audio could not be transcribed.","param":null}}
```
**(d) Function calls.** Use either event. Do **not** rely on `output[0]`: with reasoning models, a response can contain a spoken preamble message item *and* a function_call item.
```json
{"type":"response.function_call_arguments.done","response_id":"resp_002","item_id":"fc_001","output_index":0,"call_id":"call_001","name":"control_phone","arguments":"{\"task\":\"Open WhatsApp and text Mom 'on my way'\"}"}
{"type":"response.output_item.done","response_id":"resp_002","output_index":1,"item":{"id":"fc_001","object":"realtime.item","type":"function_call","status":"completed","name":"control_phone","call_id":"call_001","arguments":"{\"task\":\"...\"}"}}
```
Recommended approach: collect function calls from `response.output_item.done` (`item.type == "function_call"`) or from `response.function_call_arguments.done`. Start executing right away if you like, but **send the follow-up `response.create` only after `response.done`** for that response. Otherwise you get an error like "Conversation already has an active response" (`conversation_already_has_active_response`, reported on the community forum).

**(e) Response finished**
```json
{"type":"response.done","response":{"object":"realtime.response","id":"resp_...","status":"completed","status_details":null,"output":[ ...items... ],"usage":{ ... }}}
```
`response.status` is one of `completed | cancelled | failed | incomplete`. `status_details.reason` is `turn_detected` (VAD barge-in) or `client_cancelled` for cancelled responses, and `max_output_tokens` or `content_filter` for incomplete ones. For `failed`, `status_details.error` is populated.

**(f) Errors.** Most errors are recoverable and the session stays open. Set your own `event_id` on client events to correlate them, since it is echoed back in `error.event_id`.
```json
{"type":"error","event_id":"event_890","error":{"type":"invalid_request_error","code":"invalid_event","message":"The 'type' field is missing.","param":null,"event_id":"event_567"}}
```
Codes you'll see: `invalid_value` / `invalid_event` (bad JSON shape), `conversation_already_has_active_response`, `input_audio_buffer_commit_empty`, `response_cancel_not_active` (cancel with nothing to cancel, harmless), `rate_limit_exceeded`, `session_expired` (60-min cap, so reconnect). The official reference documents only the shape and the `invalid_*` examples. The other codes are community-reported strings. Match on `type`/`message` defensively. Auth failures (bad key, no quota) appear as an HTTP 401/403/429 on the WS upgrade (OkHttp `onFailure` with a `Response`), not as an `error` event.

Other events worth logging: `session.created`, `session.updated`, `conversation.item.truncated`, `input_audio_buffer.timeout_triggered`, `rate_limits.updated`. The `output_audio_buffer.started/stopped/cleared` events are **WebRTC/SIP only**.

## 6. Returning a tool result and making the model speak

Tool result, then trigger a new response. VAD does **not** auto-respond to tool outputs, so you must send `response.create`:
```json
{"type":"conversation.item.create","item":{"type":"function_call_output","call_id":"call_001","output":"{\"ok\":true,\"result\":\"Message sent to Mom\"}"}}
{"type":"response.create"}
```
`output` is a string; JSON-encode your result. `conversation.item.create` returns `conversation.item.added`/`.done` (or `error`).

**Long-running `control_phone`.** You can return the function output right away (for example `{"status":"started"}`) and let the model say "on it". Later, inject the final result as a system message and speak it:
```json
{"type":"conversation.item.create","item":{"type":"message","role":"system","content":[{"type":"input_text","text":"control_phone finished: WhatsApp message to Mom was sent successfully."}]}}
{"type":"response.create","response":{"instructions":"Briefly tell the user the phone task finished and its result."}}
```
* `role` may be `system`, `user`, or `assistant`. System and user items use `input_text`. You **can't** create assistant *audio* items.
* `response.create.response` can override `instructions`, `tools`, `tool_choice`, `output_modalities`, `audio.output`, and `max_output_tokens` for that response only. `"conversation":"none"` + `"input":[...]` makes an out-of-band response that isn't written to history. `"input": []` gives a response with no context.
* Proactive greeting at session start: after `session.updated`, send `{"type":"response.create","response":{"instructions":"Greet the user in one short sentence."}}`.

## 7. Interruption / barge-in on WebSocket

On WebRTC/SIP the server tracks playback and truncates automatically. **On WebSocket, the client owns playback and must handle truncation itself.** With `interrupt_response: true`, the server already cancels the in-flight response when the user starts talking:
1. On `input_audio_buffer.speech_started`: **immediately** stop the AudioTrack (`pause()` + `flush()`) and drop queued deltas for the current assistant item. Compute how many ms of that item were actually played.
2. Send:
```json
{"type":"conversation.item.truncate","item_id":"msg_008","content_index":0,"audio_end_ms":1500}
```
   The server replies `conversation.item.truncated`. It deletes the unheard transcript too. Only assistant message items can be truncated, and `audio_end_ms` greater than the item's audio duration is an error. Skip the event if the item finished playing.
3. You then receive `response.done` with `status:"cancelled"`, `reason:"turn_detected"`. Don't wait for it before stopping playback.
* Manual cancel (UI stop button, push-to-talk): `{"type":"response.cancel"}` or `{"type":"response.cancel","response_id":"resp_12345"}`, followed by the same stop-playback and truncate steps. If nothing is active you get an error, which is harmless.
* `output_audio_buffer.clear` is **WebRTC/SIP only**. Don't send it on WS.
* Ignore any late `response.output_audio.delta` whose `response_id` was cancelled.

## 8. Injecting a text user turn (testing without a mic)

```json
{"type":"conversation.item.create","item":{"type":"message","role":"user","content":[{"type":"input_text","text":"Open the camera app"}]}}
{"type":"response.create"}
```
The response is still audio plus transcript, following the session's `output_modalities`. For text-only debugging add `"response":{"output_modalities":["text"]}` and read `response.output_text.delta/done`. You can also send a recorded clip as `{"type":"input_audio","audio":"<base64 pcm16 24k>"}` content in the same item shape.

## 9. Limits, timeouts, reconnection

* **Session cap: 60 min** hard limit (`session.expires_at`). Plan to reconnect proactively (for example at about 55 min, or when idle) and on any `onFailure` / `onClosing`.
* **There is no session resume.** A new WS means an empty conversation. To restore context, re-send `session.update`, then replay a compact history with `conversation.item.create` (user and assistant **text** items, or one `system` summary item). Assistant audio can't be replayed.
* The docs don't specify a WS idle timeout. Keep OkHttp `pingInterval` on. Each open session also re-bills context per response, not per idle minute. The Realtime API has no connection or bandwidth charge (cost guide).
* Ephemeral `ek_` secrets: 10 min by default (10–7200 s). They only need to be valid at connect time.
* Rate limits are per tier RPM/TPM (see §1). Watch `rate_limits.updated`. On HTTP 429 at upgrade, back off exponentially.
* Context: 128k tokens on `gpt-realtime-2.x`. With `truncation:"auto"` the oldest items are dropped, which busts the cache. `"disabled"` gives an error instead.

## 10. Android audio specifics

**Capture (AudioRecord)**
```kotlin
val rate = 24_000
val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
val rec = AudioRecord.Builder()
    .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
    .setBufferSizeInBytes(maxOf(minBuf * 2, 9_600))     // ≥ 200 ms
    .build()
check(rec.state == AudioRecord.STATE_INITIALIZED)        // otherwise fall back (below)
// read loop on a dedicated thread: 1,920-byte (40 ms) reads → Base64.NO_WRAP → input_audio_buffer.append
```
* **Source:** use `VOICE_COMMUNICATION` ("tuned for voice communications such as VoIP… will take advantage of echo cancellation or automatic gain control if available"). `MIC` gets no AEC, so the model hears itself through the speaker and barges in on its own voice. `VOICE_RECOGNITION` is tuned for ASR but has no AEC guarantee.
* **Sample rate:** the AudioRecord docs say "44100Hz is currently the only rate that is guaranteed to work on all devices". The Android CDD (5.4.1, C-1-1/C-1-2) says compliant devices MUST capture 16-bit PCM at 8000, 11025, 16000, 22050, **24000**, 32000, 44100, and 48000 Hz "without up-sampling", so real certified devices accept 24 kHz. **Always check `STATE_INITIALIZED`.** Fallback: capture at 48 kHz and decimate by 2 (with a low-pass), or capture at 16 kHz and upsample 2:3.
* **AEC:** `if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true }`. Then check `getEnabled()`, because some devices insert AEC by default for `VOICE_COMMUNICATION`. Optionally add `NoiseSuppressor` and `AutomaticGainControl` the same way. The CDD only says devices SHOULD apply AEC for `VOICE_COMMUNICATION` (and are STRONGLY_RECOMMENDED to expose it through this API), so quality varies by OEM. Server-side `noise_reduction` plus `semantic_vad` help. If self-interruption persists, raise `threshold` or use headphones or push-to-talk.
* Set `AudioManager.mode = MODE_IN_COMMUNICATION` while a session is active, so platform AEC has the playback reference. Restore `MODE_NORMAL` afterwards. In communication mode output defaults to the **earpiece**: route to the speaker with `audioManager.setCommunicationDevice(<TYPE_BUILTIN_SPEAKER device>)` (API 31+) or `isSpeakerphoneOn = true` on older APIs. Request audio focus (`AUDIOFOCUS_GAIN_TRANSIENT`).
* Concurrency (CDD 5.4.x): while you capture with `VOICE_COMMUNICATION`, other apps' capture is silenced, except accessibility services. If `control_phone` launches another app that needs the mic, pause your capture first.
* Permissions: `RECORD_AUDIO` (runtime) and `INTERNET`. For background or locked-screen use, a foreground service with `foregroundServiceType="microphone"` (`FOREGROUND_SERVICE_MICROPHONE`).

**Playback (AudioTrack)**
```kotlin
val outMin = AudioTrack.getMinBufferSize(24_000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
val track = AudioTrack.Builder()
    .setAudioAttributes(AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)   // pairs with VOICE_COMMUNICATION capture for AEC
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
    .setAudioFormat(AudioFormat.Builder().setSampleRate(24_000)
        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
    .setTransferMode(AudioTrack.MODE_STREAM)
    .setBufferSizeInBytes(outMin * 4)
    .build().also { it.play() }
```
* The server sends audio **faster than real time**. Write decoded deltas from a dedicated playback thread fed by a queue, because a blocking `write()` must never run on the OkHttp callback thread.
* For truncation, track per-`item_id` frame offsets. Frames played = `track.playbackHeadPosition` (it resets on `flush()`/`stop()`, so re-baseline afterwards). `audio_end_ms = (headPos - itemStartFrame) / 24`.
* Barge-in: `track.pause(); track.flush(); queue.clear(); track.play()` when the next response starts.

**Emulator.** Mic input is **off by default**. Enable it under Extended controls → Microphone → "Virtual microphone uses host audio input". It may reset on emulator restart, and macOS asks for mic permission. The emulator's goldfish HAL normally accepts 24 kHz AudioRecord/AudioTrack (the host side resamples), but this isn't documented. Rely on the `STATE_INITIALIZED` check and the fallback path. `AcousticEchoCanceler.isAvailable()` is typically `false` on the emulator, so use headphones on the host, or test with text turns (§8).

## 11. "Sign in with ChatGPT" instead of an API key?

**No, not for the Realtime API.** Sign in with ChatGPT exists (developers.openai.com/siwc), but:
* "ChatGPT plan usage" covers **eligible Responses API requests only**. The preview-limitations page says: "Audio/video input, the Files upload API, and the transcription API are not supported by this flow". It says nothing about `/v1/realtime` or `/v1/live`.
* Availability: sign-in is "available to selected commercial partners through a limited trial"; plan usage is "available to all open-source partners and selected private clients". Paid or remotely hosted apps must fill in an interest form.
* Identity-only scopes "don't grant access to ChatGPT conversations or OpenAI API resources."

So the app needs an OpenAI **API key** (directly, or via ephemeral `ek_` secrets minted by your own server), billed to the API account.

---

## Minimal client event cheat-sheet
| Purpose | Client event |
|---|---|
| Configure | `session.update` |
| Mic audio | `input_audio_buffer.append` / `.commit` / `.clear` |
| Add text / tool result / system note | `conversation.item.create` (`message` / `function_call_output`) |
| Ask model to respond | `response.create` |
| Stop model | `response.cancel` |
| Sync interrupted audio | `conversation.item.truncate` |
| Housekeeping | `conversation.item.delete`, `conversation.item.retrieve` |

## Sources (fetched 2026-11-13)
* Realtime getting started and Beta→GA migration: https://developers.openai.com/api/docs/guides/realtime
* WebSocket connection (Realtime + GPT-Live): https://developers.openai.com/api/docs/guides/voice-websockets?api=realtime
* Realtime conversations (events, audio, function calling, truncation, push-to-talk, 60-min cap): https://developers.openai.com/api/docs/guides/realtime-conversations
* VAD: https://developers.openai.com/api/docs/guides/realtime-vad
* Tools: https://developers.openai.com/api/docs/guides/realtime-mcp
* Prompting / reasoning effort / preambles: https://developers.openai.com/api/docs/guides/voice-prompting
* Client events reference: https://developers.openai.com/api/reference/resources/realtime/client-events
* Server events reference: https://developers.openai.com/api/reference/resources/realtime/server-events
* Client secrets (ephemeral tokens): https://developers.openai.com/api/reference/resources/realtime/subresources/client_secrets/methods/create
* Models: https://developers.openai.com/api/docs/models , https://developers.openai.com/api/docs/models/gpt-realtime-2.1 , https://developers.openai.com/api/docs/models/gpt-realtime-2.1-mini
* Pricing: https://developers.openai.com/api/docs/pricing ; cost guide: https://developers.openai.com/api/docs/guides/voice-latency-cost?api=realtime
* Realtime transcription: https://developers.openai.com/api/docs/guides/realtime-transcription
* GPT-Live: https://developers.openai.com/api/docs/guides/live ; voice architectures: https://developers.openai.com/api/docs/guides/voice-agents
* Sign in with ChatGPT: https://developers.openai.com/siwc/quickstart , https://developers.openai.com/siwc/token-sharing-open-source , https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations
* Community (error codes): https://community.openai.com/t/realtime-api-server-response-error-message-conversation-already-has-an-active-response/1005582 , https://community.openai.com/t/realtime-api-error-committing-input-audio-buffer-the-buffer-is-empty/975381
* Android: https://developer.android.com/reference/android/media/AudioRecord , https://developer.android.com/reference/android/media/MediaRecorder.AudioSource , https://developer.android.com/reference/android/media/audiofx/AcousticEchoCanceler , https://developer.android.com/reference/android/media/AudioTrack , https://developer.android.com/reference/android/media/AudioManager , https://source.android.com/docs/core/audio/implement-pre-processing , CDD 5.4: https://android.googlesource.com/platform/compatibility/cdd/+/refs/heads/main/5_multimedia/5_4_audio-recording.md , https://developer.android.com/studio/run/emulator-extended-controls
