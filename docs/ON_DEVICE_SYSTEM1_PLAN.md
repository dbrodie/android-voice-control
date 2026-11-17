# On-device alternatives to JEV (System One) — plan

Date: 2026-11-15. Status: proposal, nothing implemented yet. Revision 2: adds Laya, which becomes the primary.

## 0. What JEV gives us today, and where it plugs in

Per step the loop in `agent/TaskRunner.kt` asks JEV one question and gets back three things:

| Slot | JEV answer | Used for |
|---|---|---|
| `action` | choice + confidence + full probability map over the offered actions | confidence gating (`MIN_CONFIDENCE`), `CHAIN` splitting, "unsure" stop |
| `target` | box in 0..1 image coordinates + location confidence | where to tap; zoom-in `refine` when `< CLOSE_UP_BELOW` |
| `text` / `app` / `website` | index into the options the task string offers | what to type / open |

The whole cloud dependency is one class, `JevClient` (`ask`, `refine`, `hasKey`), called in two places in
`TaskRunner`. Everything else (settle detection, overlay masking, reports, Runs screen, costs) is model-agnostic.
Cost today: ~$0.0004 per task, ~0.4 s per `ask`. JEV never generates text: it only picks among options we offer.

So the engineering task is: put a `SystemOne` interface in front of `JevClient`, and add on-device
implementations that fill the same three slots.

## 1. Candidates examined (research 2026-11-15; six briefs, sources in Section 5)

### A. Laya (Convai Innovations) and Laya-Android  ← recommended primary
- **What it is.** An open-weights (Apache-2.0) non-autoregressive "System One" decision model released
  September 2026 and explicitly positioned as a Jev alternative. Encoder (ModernBERT-large, 421M, English; or
  mmBERT-base, 322M, multilingual) plus a decision head. It never generates text. Input: a `state` and typed
  `questions` (`choice` over caller-supplied criteria, `score`, `noul` yes/no). Output: the chosen option **and a
  calibrated probability over all options**, for every question, in one forward pass. Its `laya-serve` even exposes
  `POST /v1/systemone` with the same request shape as JEV.
- **Fit with our loop.** Our three slots are all `choice` questions (`action` over the offered actions, `text`/
  `app`/`website` over the task's options), and the only non-choice slot, the target box, can itself become a
  `choice` over the on-screen elements from the accessibility tree, whose exact bounds give the tap point. That maps
  one-to-one onto `JevResponse`, including the `probabilities` map that `splitChain` and `MIN_CONFIDENCE` need.
  No voting or self-reported confidence needed; Laya's calibration (ECE ~0.08 after temperature fit) is better than
  JEV's published 0.25.
- **Laya-Android (Byun11/laya-android, ByunByun/laya-android on HF).** A 322M fine-tune of the multilingual
  checkpoint on AndroidControl (74k actions), accessibility tree only, no screenshots. State is
  `{goal, history (last 3), ui: ["[i] <label> (<class>) <flags>", ...]}`; candidates are the visible clickable /
  editable / scrollable / checkable nodes (median 15, p99 65 per screen). Two questions per step: operation (12 ops:
  CLICK, LONG_PRESS, SCROLL_{UP,DOWN,LEFT,RIGHT}, INPUT_TEXT, OPEN_APP, BACK, HOME, WAIT, DONE) and target element.
  Self-reported on the AndroidControl test split, same 1,000 steps: **Type 76.7 / Grounding 61.7 vs JEV 1.13.0
  67.2 / 56.7**, median 40 ms on an RTX 4090 vs JEV's 236 ms over the network. Base Laya zero-shot is near chance
  (29.0 / 11.8), so the fine-tune is mandatory. 644 MB fp16 safetensors. Weak ops: WAIT, BACK, horizontal scroll,
  LONG_PRESS. 6% of gold targets are not in its candidate set. Step-level benchmark with gold history, not
  autonomous task success. Apache-2.0.
- **On-device runtime already exists.** Community LiteRT exports of the base multilingual checkpoint
  (`litert-community/Laya-Multilingual-LiteRT`, same mmBERT-base + head architecture as Laya-Android) run on a Galaxy
  S26 at **52 ms per question on GPU, 37 ms NPU, 163 ms CPU** (256-token window; 512-token GPU 138 ms), with
  argmax parity to the PyTorch reference on 201 of 201 rows and probability drift < 0.002. Recommended file set is
  680 MB (250 MB fp16 encoder + 393 MB memory-mapped token table + tokenizer). A Kotlin sample app with a BPE
  tokenizer port, LiteRT 2.2.0 `CompiledModel`, GPU and Qualcomm NPU paths lives in Google's
  `google-ai-edge/litert-samples` (`samples/litert/zero_shot_classification`). Conversion recipe: litert-torch
  0.9.3 + a Python host that builds `[CLS] <type> question: <instructions> [SEP] [MASK] opt0 [MASK] opt1 … [SEP]
  <state> [SEP]`. Only the S26 is validated; fp16-in-graph does not compile on GPU in LiteRT 2.2.0 (hence the
  embeddings-input form).
- **What we must build ourselves.** (1) Convert the Laya-Android fine-tune with the same recipe, at a longer
  window (it was trained with `max_len` 3072, `head_max_len` 1536; the exports stop at 512). (2) Reproduce its
  candidate extraction and `[i] label (class) flags` serialization exactly, because other formats are out of
  distribution. (3) Map its 12 ops onto our `PHONE_ACTIONS` (no TYPE_ENTER/ENTER/OPEN_WEBSITE/ALL_APPS/DOUBLE_CLICK;
  keep those as extra criteria or supply them via a second question and accept lower accuracy until we fine-tune).
  (4) A further fine-tune on our own data is cheap: the authors' notebook runs on Kaggle's free 2×T4 in 4–5 h, and
  our run reports already store per-step state and action.
- **Gaps.** Text only: games, Unity, canvas and WebViews before Chrome serializes expose nothing (OCR fallback,
  Section D). No pixel grounding. ~700 MB download. The only on-phone measurements are on one Samsung flagship;
  Pixel GPU path untested (LiteRT CPU at 163 ms per question is still fine). Fine-tunes and exports are community
  work with self-reported numbers.
- Verdict: **primary on-device backend.** Same contract as JEV, orders of magnitude smaller and faster than a
  generative LLM, and with measured Android-control numbers that beat JEV on its own benchmark.

### B. Gemini Nano via ML Kit GenAI Prompt API (AICore)
- `com.google.mlkit:genai-prompt:1.0.0-beta5`: image + text input, typed structured output (alpha), function
  calling preview. Nano 4 (= Gemma 4 E2B/E4B) on Pixel 10/11, Galaxy S25/S26; Nano 3 on Pixel 9. ~4k token context.
- **Blocker: AICore refuses inference unless the calling app is the top foreground app**
  (`ErrorCode.BACKGROUND_USE_BLOCKED`; foreground services do not count). Our agent runs from a foreground service
  while *another* app is on screen. A transparent activity flashed during inference is unverified and would corrupt
  our own screenshots and settle detection. No logprobs, per-app quotas, narrow device list, no evidence of
  coordinate output.
- Verdict: **not viable as primary.** Cheap flagged experiment later, mainly to test the foreground rule.

### C. LiteRT-LM + Gemma 4 E2B (generative, text or image)
- LiteRT-LM 0.19.1 is Google's current on-device LLM runtime (MediaPipe LLM Inference is maintenance-only): GPU/
  NPU, image input, grammar-constrained JSON output, LoRA. Gemma 4 E2B is 2.6 GB, Apache-2.0, needs 8 GB RAM.
  Samsung GPU: 3.8k tok/s prefill; Pixel: CPU only (~10 tok/s decode, image first-token several seconds).
- Pixel grounding is weak (third-party: base E4B 1.8% ScreenSpot-Pro; 22% after a 200k-sample LoRA). As a
  text-only tree agent it is plausible but ~1.5k-token prompts cost seconds per step on Pixel, there are no logprobs
  (confidence must be synthesized by sampling), and published zero-shot results for small text models on tree tasks
  are poor without fine-tuning.
- Verdict: **secondary.** Worth keeping as a generative fallback for screens where Laya's candidate set misses
  (free-form reasoning, text-poor trees with OCR), and as the only Google-model path that could consume
  screenshots. Not first.

### D. ML Kit Text Recognition v2 (on-device OCR) — supplement
- `com.google.mlkit:text-recognition:16.0.1` (bundled Latin, +4 MB). Lines/words with bounding boxes, roughly
  100–400 ms on a half-resolution screenshot (unmeasured). Use when the tree is empty or text-poor: OCR lines become
  extra candidates with boxes. Out of distribution for Laya-Android's fine-tune, so expect lower accuracy there.

### E. Dedicated open GUI-grounding VLMs (GUI-Owl-1.5-2B, MAI-UI-2B, UI-Venus-1.5-2B)
- ~58% ScreenSpot-Pro, far better than Gemma at pixels, but llama.cpp/MNN only and ~40 s per step on a Galaxy
  S24. Not for this app today.

### F. Android platform agent APIs (AppFunctions, Gemini "UI automation")
- Third-party apps cannot call other apps' AppFunctions; the UI-automation framework is Gemini-only. Re-check at
  Android 17.

## 2. Target design

### 2.1 Backend interface (agent/)
```kotlin
interface SystemOne {
    val id: String                       // "jev", "laya", "gemma", "nano"
    val pricePerMillionInput: Double     // 0.0 for on-device; Runs screen shows latency instead
    fun isAvailable(ctx): Availability   // Ready | NeedsDownload(bytes) | Unsupported(reason) | NoKey
    fun ask(input: StepInput): StepDecision
    fun refine(input: StepInput, closeUp: CloseUp): JevTarget? = null   // only pixel backends implement it
}
data class StepInput(task, screenshotJpeg, screenshotSize, uiTree: UiTree?, history, texts, urls, apps)
```
`StepDecision` is today's `JevResponse` plus `latencyMs`, `backend` and the chosen `UiElement?`. `JevClient`
becomes `JevSystemOne` (thin adapter; byte-identical bodies, golden tests unchanged). `TaskRunner` calls `refine`
only when a backend returns a low-confidence box; tree backends return the element's bounds with
`targetConfidence = 1.0`, so the gating code needs no special-casing.

### 2.2 Screen tree capture (control/ → core/)
- Additive `Screen.uiTree(): UiTree?` (`core/UiTree.kt`: flat `UiElement(id, kind, text, desc, hint, resId, flags,
  boundsPx)` list plus `packageName`, `windowKind`, `keyboardShown`).
- Service config adds `flagRetrieveInteractiveWindows` and `flagReportViewIds`. No `flagIncludeNotImportantViews`
  by default (noise).
- Capture = Laya-Android's candidate rule, reproduced exactly: visible nodes outside the keyboard window that are
  clickable, long-clickable, editable, scrollable or checkable; bounds clipped to the screen; label = own text /
  contentDescription / hint, else descendant text (≤ 60 chars); dedupe on (bounds, label) keeping the deepest node
  and OR-ing flags; order top-to-bottom, left-to-right. Keep the raw tree too for the Gemma backend and the Runs
  screen. If a dialog window is on top, only its nodes. Mask our overlay via `OverlayInfo.regions`.
- Capture happens where `grab()` takes the screenshot, so tree and image describe the same moment.

### 2.3 Laya backend (agent/laya/)
- `LayaEngine`: port of the sample's Kotlin host. LiteRT 2.2.x `CompiledModel`, GPU → CPU fallback, Qualcomm NPU
  opt-in; BPE tokenizer port; builds the `[CLS] choice question: … [SEP] [MASK] opt … [SEP] state [SEP]` sequence;
  reads logits at mask positions; applies the shipped temperatures. One `prepare` per step covers all questions.
- `LayaSystemOne.ask`: state = `{goal: task, history: last 3, ui: serialized candidates}`; questions = `operation`
  (criteria = offered actions mapped to Laya-Android's op names, extra PHONE_ACTIONS appended as criteria),
  `target` (criteria = candidate ids), plus `text` / `app` / `website` exactly as `askBody` offers them today.
  Returns `probabilities` from the softmax, box from the chosen candidate's bounds. TYPE on a non-editable target
  → re-ask with editable candidates only. Prompt-injection guard: candidate labels are data; the `instructions`
  carry our existing NOTE.
- Window: export at 1024 tokens (p99 65 candidates × ~10 tokens + goal + history fits); fall back to truncating the
  candidate list with a "scroll to see more" note.
- Model files (~700 MB: encoder fp16, token table, tokenizer, calibration): downloaded on Wi-Fi from HF (or a
  release asset we host), SHA-256 verified, under `files/models/laya-android/`. Not bundled in the APK.
- Gating: arm64-v8a; RAM is not a concern (peak well under 1 GB). Works on the emulator's x86_64 only via CPU if
  LiteRT ships that ABI; otherwise the emulator keeps JEV and Laya is verified on a real phone.

### 2.4 Gemma backend (agent/gemma/, phase 6)
- LiteRT-LM + Gemma 4 E2B text-only on the AutoDroid-style serialization, grammar-constrained
  `{action, id, text, app, website}`, confidence by 4-sample voting. Same `SystemOne` seam. Optional image mode with
  `box_2d` for tree-less screens.

### 2.5 Settings, UI, reports
- `AppSettings.systemOneBackend`: `auto | jev | laya | gemma` (`auto` = Laya when Ready, else JEV when a key exists).
- Setup checklist row "Next-step model": JEV key status / model downloaded / download with progress.
- Run reports: `backend`, per-step `latency_ms`, `candidates`, `target_id`, `ocr_used`; cost 0 for local. Runs
  screen shows backend chip and p50 step latency; the existing "alternatives" view shows Laya's distribution.
- `Finished.message` strings stop saying "JEV" when the backend is local.

## 3. Phases and tasks

| # | Task | Exit criteria |
|---|---|---|
| T14 | **Spike (first, ~1–2 days).** (a) Run Laya-Android in Python on 10 real trees dumped from the user's phone via a debug broadcast (Settings, Chrome, WhatsApp, a game) and record hit rate; (b) convert the Laya-Android checkpoint with the litert-torch recipe at a 1024 window, verify parity vs PyTorch on the 10 trees; (c) build the Google `zero_shot_classification` sample, swap in our graph, measure ms/question GPU vs CPU on the user's phone; (d) in parallel, Gemma 4 E2B text benchmark in AI Edge Gallery for the secondary path | Table: hit rate on 10 screens, parity, ms/question per backend, model size. Go/no-go and window size decided |
| T15 | `SystemOne` interface, `JevSystemOne` adapter, `StepInput/StepDecision`, `TaskRunner` refactor, backend setting + report fields | 105 JVM tests pass; JEV bodies byte-identical; emulator regression of the 4 reference tasks |
| T16 | Tree capture in control/ + Laya candidate serializer + raw-tree serializer, JVM golden tests from the T14 dumps; OCR fallback behind a flag | Serializer matches Laya-Android's `src/candidates.py` on the dumps; dialog rule; overlay masked; Chrome page nodes present |
| T17 | `LayaEngine` + `LayaSystemOne`: LiteRT dependency, tokenizer port, model download/verify UI, op mapping, GPU→CPU fallback, warmup, gating | On the user's phone: Bluetooth on, Chrome search, Clock→Alarms, Dark theme complete with `backend=laya`; latency recorded |
| T18 | Evaluation + calibration: ~20-task suite via `RUN_TASK` broadcasts on a real phone, JEV vs Laya; tune op mapping, temperatures, candidate cap; choose `auto` policy | Success rate, steps, p50/p95 latency per backend in `docs/EVAL.md`; `auto` default chosen from data |
| T19 | Fine-tune Laya-Android on our own data: export (tree, action) pairs from run reports (+ AndroidControl), extend the op set to PHONE_ACTIONS, Kaggle 2×T4 notebook, re-export to LiteRT | Step accuracy before/after on the T18 suite; TYPE_ENTER/OPEN_WEBSITE handled natively |
| T20 | Optional: Gemma 4 E2B backend (text + image) via LiteRT-LM for tree-less screens; Nano/AICore experiment to confirm or refute the foreground block | Measured hit rate on 30 of our screenshots; written verdict |

T15 and T16 are independent after T14; T17 depends on both; T18 needs T17; T19 needs T18 data.

## 4. Risks and open questions

- **Community provenance.** Laya-Android's numbers, the LiteRT exports and the Kotlin host are self-reported
  community work (the exports sit under the `litert-community` HF org but were made by an individual). T14 checks
  parity and latency ourselves before anything is built on them.
- **Serialization lock-in.** Laya-Android only works on its exact candidate format; our capture must mirror
  `src/candidates.py` and stay in sync if we fine-tune. Golden tests in T16 enforce this.
- **Op-set mismatch.** 12 ops vs our 16 actions. Until T19, TYPE_ENTER is approximated as INPUT_TEXT + ENTER
  (our `CHAIN` logic already handles the split), OPEN_WEBSITE and OPEN_APP keep their pre-loop handling in
  `TaskRunner`, DOUBLE_CLICK is dropped.
- **Candidate misses.** 6% of gold targets were not clickable nodes in AndroidControl; third-party apps are worse.
  OCR candidates and the Gemma fallback cover part of this; T18 measures it on real tasks.
- **Device coverage.** Only the S26 is validated for the LiteRT graph; CPU should work everywhere at ~160 ms per
  question, GPU compile may fail on other SoCs. T14 runs on the user's phone; emulator keeps JEV.
- **Download size** ~700 MB (vs 2.6 GB for Gemma). Still needs a Wi-Fi download step and user consent.
- **Privacy win**: no screenshots or tree text leave the device in local mode, a reason for `auto` to prefer it.

## 5. Sources (selection)
- Laya cards: https://huggingface.co/convaiinnovations/laya , https://huggingface.co/convaiinnovations/laya-multilingual ,
  https://huggingface.co/convaiinnovations/laya-typed-decisions ; SDK repo github.com/NandhaKishorM/laya (not opened)
- Laya-Android: https://github.com/Byun11/laya-android (docs/training.md, docs/results.md, docs/eval_protocol.md),
  https://huggingface.co/ByunByun/laya-android ; other fine-tune https://huggingface.co/arnabwithab/laya-android-control ;
  adb-driven skill + benchmarks https://github.com/surapat-codediva/laya-android (issue #2 plans an AccessibilityService)
- Laya LiteRT exports: https://huggingface.co/litert-community/Laya-Multilingual-LiteRT (incl. `android/`),
  https://huggingface.co/litert-community/laya-LiteRT ; Google sample
  https://github.com/google-ai-edge/litert-samples/tree/main/samples/litert/zero_shot_classification
- ML Kit GenAI Prompt API + quotas/foreground rule: https://developers.google.com/ml-kit/genai ,
  https://developers.google.com/ml-kit/genai/prompt/android ; AICore dev preview: https://developers.google.com/ml-kit/genai/aicore-dev-preview
- LiteRT-LM: https://github.com/google-ai-edge/LiteRT-LM , https://developers.google.com/edge/litert-lm/android
- Gemma 4: https://ai.google.dev/gemma/docs/core/model_card_4 , https://ai.google.dev/gemma/docs/capabilities/vision/image ,
  GUI grounding measurement https://huggingface.co/docling-project/DeskForge-Gemma4-E4B ; Gallery https://github.com/google-ai-edge/gallery
- Tree-vs-screenshot evidence: AndroidWorld https://arxiv.org/abs/2405.14573 ; AndroidControl https://arxiv.org/abs/2406.03679 ;
  AutoDroid https://arxiv.org/abs/2308.15272 ; android_world `representation_utils.py` / `t3a.py`
- Chromium Android accessibility: https://github.com/chromium/chromium/blob/main/docs/accessibility/browser/android.md
- ML Kit Text Recognition v2: https://developers.google.com/ml-kit/vision/text-recognition/v2/android
- AppFunctions: https://developer.android.com/ai/appfunctions ; UI automation:
  https://android-developers.googleblog.com/2026/02/the-intelligent-os-making-ai-agents.html
- GUI VLMs: https://huggingface.co/mPLUG/GUI-Owl-1.5-2B-Instruct , https://github.com/Tongyi-MAI/MAI-UI ,
  https://huggingface.co/inclusionAI/UI-Venus-1.5-2B
