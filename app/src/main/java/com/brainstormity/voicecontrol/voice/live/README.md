# voice/live: GPT-Live transports

`VoiceController` runs one GPT-Live call at a time through the `LiveSession` interface. Client delegations
go to the phone agent through `LiveTaskBridge`. Two transports implement `LiveSession`:

| Backend id | Class | Auth | Audio | Control events |
|---|---|---|---|---|
| `chatgpt` | `CodexLiveTransport` | Sign in with ChatGPT (`LiveAuth.provider`) | WebRTC (`RtcAudioPeer`, Opus) | `wss://api.openai.com/v1/live/<callId>` sideband (unofficial Codex route, model `gpt-live-1-codex`) |
| `openai_key` | `OpenAiLiveWsTransport` | OpenAI API key (`AppSettings.openAiApiKey`) | PCM16 24 kHz base64 over the same WebSocket (`AndroidPcmAudio`) | public `wss://api.openai.com/v1/live/sessions`, model `gpt-live-1` |

Both transports send every server event through `LiveProtocol.parseEvent`, so `VoiceController` sees the same
`LiveEvent`s from either one.

## Chooser (`voice/VoiceBackends.kt`)

- `AppSettings.voiceBackend` can be `auto` (the default), `chatgpt` or `openai_key`.
- `resolve(ctx)` returns:
  - `auto`: `chatgpt` if signed in, else `openai_key` if a key is set, else `""`.
  - `chatgpt` or `openai_key`: that backend if it is usable, else `""`.
- In debug builds, a readable pushed Codex `auth.json` counts as signed in (`debugChatGptAvailable`).
- `VoiceController.sessionFactory` defaults to `VoiceBackends.createSession`, which resolves again on every
  `start()`. When it returns `""`, `start()` emits
  `VoiceState.Error("Sign in with ChatGPT or enter an OpenAI API key in settings")`.
- `describe(ctx)` gives a one-line summary for the settings screen.
- `install(ctx)` should be called from `Application.onCreate`. It wires ChatGptAuth into `LiveAuth.provider`
  and keeps any earlier provider as a fallback, for example the debug `CodexFileAuthProvider`.

## Public GPT-Live API schema (verified 2026-11-13)

Sources:
- https://developers.openai.com/api/reference/resources/live/primary-websocket
- https://developers.openai.com/api/docs/guides/voice-websockets?api=live
- https://developers.openai.com/api/docs/guides/live-delegation
- https://developers.openai.com/api/docs/guides/live

Add `.md` to any of these URLs to get raw markdown.

- **Connect:** `wss://api.openai.com/v1/live/sessions` with no query parameters and
  `Authorization: Bearer <key>`. A bad key is rejected at the handshake with HTTP 401 and a JSON body
  `{"error":{"code":"invalid_api_key","message":"Incorrect API key provided: …"}}`.
- **First message:**
  ```json
  {"type":"session.start","event_id":"…","session":{"model":"gpt-live-1","instructions":"…",
   "audio":{"format":{"type":"audio/pcm","rate":24000},"output":{"voice":"marin"}},
   "delegation":{"type":"client"}}}
  ```
  - PCM rates are 24000 (the default) or 16000. One format covers input and output and is fixed for the
    session.
  - The voice must be one of the 38 public voices (default `marin`). The Codex voices (`cove`, …) are not
    valid here, so they map to `marin`.
  - `delegation.ack_filler` is a Codex-only field and is not sent. The public field
    `acknowledgement_delay_ms` (0–800) exists but is not used.
  - Wait for `session.started` (`session.id`) before sending anything else. `connect()` returns at that point.
- **Mic:** `{"type":"session.input_audio.append","audio":"<base64 raw PCM16LE mono>"}` with an even byte count
  and no acknowledgment. We send 40 ms chunks.
- **Speaker:** `{"type":"session.output_audio.delta","delta":"<base64>"}`. Play the chunks in order. The
  primary WebSocket has no output-audio-done event and no "output cleared" event.
- **Transcripts:** `session.input_transcript.delta` and `session.output_transcript.delta` carry
  `{delta,start_ms,end_ms}`. They are fragments only, with no "done" event.
- **Delegation:** `session.delegation.created` carries
  `{offset_ms, delegation:{id,type:"delegation",target:"client"|"responses"|"decisions"}}`. It has **no task
  text**: the docs say to reconstruct the request from transcripts. `VoiceController.delegationText()` does
  this from the user's transcript since the last delegation:
  - the in-progress turn counts, because the delegation usually arrives before GPT's acknowledgement ends the
    turn;
  - answers to a clarifying question (an assistant turn ending in `?`) are joined with the request they
    complete.
- **Context out:** `session.commentary.append` (spoken, paraphrased) and `session.thinking.append` (silent),
  both shaped `{event_id, delegation_id: <id>|null, content}`:
  - `delegation_id` is required but may be null.
  - `content` is limited to 500 tokens. We chunk at 500 UTF-8 bytes, which can never exceed that.
  - The server acknowledges with `session.commentary.appended` and `session.thinking.appended`.
  - Mapping: `LiveChannel.SPEAKABLE` → commentary, `LiveChannel.COMMENTARY` → thinking.
- **Close:** send `{"type":"session.close","event_id":"…"}`, wait for
  `session.closed {reason, usage:{seconds}}` (we wait up to 5 s), then close the socket. `reason` is one of
  `close_requested`, `expired`, `content`, `remote_hangup` or `connection_lost`.
- **Errors:** `{"type":"error","error":{type,code,message,param,client_event_id}}`.
  - An error before `session.started` fails `connect()`.
  - `invalid_api_key` and 401 errors are fatal, and the user sees "Invalid OpenAI API key (401)…".

## Barge-in on the WebSocket transport

The server stops generating when the user interrupts, but audio we have already queued would keep playing.
`OpenAiLiveWsTransport` flushes the local queue and emits `LiveEvent.SpeechCleared` when all of these hold:
- the user transcript has at least 2 words since the last output chunk, so "mm" does not count;
- the server has sent no audio for at least 300 ms;
- audio is still queued.

## Testing

- Unit tests (`app/src/test/.../voice/live/OpenAiLiveWsTransportTest.kt`) run a MockWebServer as the GPT-Live
  WebSocket. They cover:
  - `session.start` shape and headers;
  - mic append base64;
  - output delta → PCM;
  - commentary/thinking mapping and chunking;
  - delegation and transcript parsing;
  - the close handshake;
  - a 401 handshake;
  - an error before start;
  - barge-in, the resampler, and the chooser table.
- Debug hooks (`src/debug/.../BackendDebugReceiver.kt`):
  ```
  adb shell am broadcast -p com.brainstormity.voicecontrol -a com.brainstormity.voicecontrol.BACKEND_STATUS
  adb shell am broadcast -p com.brainstormity.voicecontrol -a com.brainstormity.voicecontrol.BACKEND_SET --es backend openai_key --es key sk-...
  adb shell am broadcast -p com.brainstormity.voicecontrol -a com.brainstormity.voicecontrol.BACKEND_SET --es backend auto --ez clear_key true
  adb shell am broadcast -p com.brainstormity.voicecontrol -a com.brainstormity.voicecontrol.LIVE_START
  ```
- **With a real key later:** either put `OPENAI_API_KEY=sk-...` in the project-root `.env` and rebuild (it
  becomes `BuildConfig.OPENAI_API_KEY`), or enter the key in the Settings field (`AppSettings.setOpenAiApiKey`)
  or set it with `BACKEND_SET --es key`. Then select `openai_key` (or use `auto` while signed out of ChatGPT)
  and start a call.
  - Expect `VC/Live: session.started id=...`, then `VC/Voice: GPT-Live call up`.
  - Things still unverified without a key: real `session.output_audio.delta` pacing, the barge-in thresholds,
    and the delegation-text heuristic on live transcripts.
