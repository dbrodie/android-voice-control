# Laya-Android on-device host contract (for T17 `LayaEngine` / `LayaSystemOne`)

Everything here was verified against the installed `laya==0.3.28` package source (`laya/agent.py`, `laya/common.py`)
and Byun11/laya-android (`src/candidates.py`, `src/serialization.py`, `scripts/build_androidcontrol_dataset.py`,
`scripts/eval_laya_android.py`), and executed end to end by `host.py` (torch-free) against the exported graphs:
token ids and marker positions are identical to laya's on all 24 fixture rows / 50 questions, at 256, 512 and 1024.
`host.py` is the executable form of this document; when in doubt, it wins.

## 0. Files (from `tools/laya/out/`, sizes and sha256 in `out/MANIFEST.json`)

| File | Use |
|---|---|
| `laya_android_multi_embeds_wfp16.tflite` | **recommended**: one file, signatures `s256`, `s512`, `s1024`, `s2048` sharing one copy of the weights (FC weights fp16 + DEQUANTIZE, fp32 compute) |
| `laya_android_s1024_embeds_wfp16.tflite`, `..._s512_...`, `..._s256_...` | single-window fallbacks (signature `serving_default`), if the GPU delegate dislikes multi-signature files |
| `laya_android_s{N}_embeds_fp32.tflite` | fp32-weight references (parity 0.00000), 2x size |
| `token_embeddings_fp16.bin` + `token_embeddings.json` | `[256000,768]` float16 little-endian row-major token table (393,216,000 bytes), memory-map it |
| `tokenizer.json` (+ `tokenizer_config.json`) | exact checkpoint tokenizer |
| `rl_agent_config.json` | temperatures (and training budget) |
| `laya_android_act_head_fp32.tflite` | **not needed** (see section 7) |

Recommended phone set: multi wfp16 graph (275 MB) + token table (393 MB) + tokenizer.json (34 MB) + rl_agent_config.json = 702,944,470 bytes. Mac CPU cost per question: s256 94 ms, s512 194 ms, s1024 478 ms, s2048 1320 ms (README).

## 1. Request = Laya-Android's training format

Per step, build candidates from the UiTree exactly as `tools/laya/candidates.py` documents (rules 1-11 in its
docstring; golden data in `fixtures/*.json` -> `candidates`, `state`). Then:

```
state = {"goal": <task>, "history": [<last 3 action strings, oldest first>], "ui": ["[0] <option label>", ...]}
questions (in this order):
  "operation": choice, instructions "Which operation should be performed next?",
               criteria = the 12 ops below, in this order, with these descriptions (byte-for-byte)
  "target":    choice, instructions "Which UI element should be targeted?",
               criteria = {"0": <option label 0>, "1": <option label 1>, ...}   (only if >= 1 candidate)
```

| idx | op key | description |
|---:|---|---|
| 0 | CLICK | tap a UI element |
| 1 | LONG_PRESS | long-press a UI element |
| 2 | SCROLL_UP | scroll up |
| 3 | SCROLL_DOWN | scroll down |
| 4 | SCROLL_LEFT | scroll left |
| 5 | SCROLL_RIGHT | scroll right |
| 6 | OPEN_APP | launch an app by name |
| 7 | INPUT_TEXT | type text into the focused field |
| 8 | BACK | press the system back button |
| 9 | HOME | go to the home screen |
| 10 | WAIT | wait for the screen to update |
| 11 | DONE | the goal is complete; stop |

History strings (`candidates.history_str`): `CLICK <full 60-char label, or short class if empty>`,
`LONG_PRESS <same>`, `CLICK (unlabeled)` when the tap hit no candidate, `INPUT_TEXT "<text>"`, `OPEN_APP <app name>`,
otherwise the bare op (`SCROLL_DOWN`, `BACK`, `HOME`, `WAIT`). Map our executed actions into these strings
(TYPE/TYPE_ENTER -> `INPUT_TEXT "<text>"`, TAP -> `CLICK <label>`, ...).

Our optional additions (untrained, see README "Extra criteria"): extra operation criteria are appended after the 12
(`candidates.EXTRA_OP_DESC`), and a `text` choice question `{"0": text0, ...}` with instructions
`Which text should be typed into the focused field?`. Recommendation: do not add them (section 8).

## 2. Tokenizer

- `tokenizer.json`: BPE (byte_fallback=true, fuse_unk=true), normalizer `Replace(" " -> "▁")`, pre-tokenizer
  `Metaspace(replacement "▁", prepend_scheme "always", split true)`, 249 added tokens matched before normalization
  (special: `<pad>`=0, `<eos>`=1, `<bos>`=2, `<unk>`=3, `<mask>`=4 with lstrip=true). No extra Unicode normalization.
  The litert-samples / Laya-Multilingual-LiteRT Kotlin `LayaTokenizer` implements exactly this (same tokenizer
  family, same file format) and is the port to reuse.
- **Trap: the file contains `"truncation": {"max_length": 48, ...}`** (state saved by laya's option encoding call).
  laya never applies it to the instruction or the state. A loader that honours it (HF `tokenizers`, a naive port)
  cuts every fragment to 48 tokens. Ignore that field (`host.py` calls `no_truncation()`).
- Every fragment is encoded with **no special tokens added** (the post-processor's TemplateProcessing must not run).
- Added-token text that appears in screen text (e.g. a label containing `<eos>` or `<start_of_turn>`) is encoded as
  that token id, as HF does (`split_special_tokens=False`). The literal string `<mask>` is replaced by one ASCII space
  everywhere before tokenizing (instructions, options, state), so id 4 only ever appears as a marker.
- Special ids used by the builder: CLS = 2 (`<bos>`), SEP = 1 (`<eos>`), MASK = 4, PAD = 0.

## 3. Sequence construction: ONE sequence per question

laya encodes each question as its own row (batch of rows in one forward on desktop; on the phone run them one after
another through the same signature). Per question, with `max_len = N` (the window you run) and
`head_max_len = 1536` (the value Laya-Android was trained and evaluated with; keep it):

```
head_ids  = encode(type + " question: " + instructions.replace("<mask>", " "))          # type = "choice"
options   = [key if desc is null/"" else key + ": " + desc  for key, desc in criteria]  # insertion order
opt_ids_i = [4] + encode(" " + options_i.replace("<mask>", " "))[:48]                   # leading ASCII space; cap 48 text tokens
opt_budget = 1536 - sum(len(opt_ids_i))
if opt_budget < 16:                                   # never happens below ~110 candidates
    per = max(4, (1536 - 16) // K);  opt_ids_i = opt_ids_i[:per];  opt_budget = 1536 - sum(len(opt_ids_i))
head_ids = head_ids[:max(8, opt_budget)]
ids = [2] + head_ids + [1];  markers = []
for each option: markers.append(len(ids)); ids += opt_ids_i
ids += [1]
state_ids = encode(json(state).replace("<mask>", " "))      # tokenized ONCE per step, shared by all questions
room = max(0, N - len(ids) - 1)
ids = ids + state_ids[:room] + [1]                           # state is RIGHT-truncated (end of `ui` is lost first)
ids = ids[:N];  markers = [m for m in markers if m < N]
if len(markers) != K: the question does not fit -> laya raises; we must drop candidates (section 5)
```

`json(state)` = Python `json.dumps(state, ensure_ascii=False)`: keys in the order goal, history, ui; separators
`", "` and `": "`; non-ASCII emitted raw (UTF-8, incl. emoji / astral chars); escapes only `"` -> `\"`,
`\` -> `\\`, `\n`, `\r`, `\t`, `\b`, `\f`, other U+0000-U+001F -> `\u00xx` (lowercase hex). **`/` is NOT escaped and
U+2028/U+2029 are NOT escaped** (org.json / Gson / kotlinx defaults differ: write this serializer by hand).
Example: `{"goal": "Turn on Bluetooth", "history": ["OPEN_APP Settings"], "ui": ["[0] Search Settings (LinearLayout)", ...]}`.

`head_max_len` semantics: it is a budget for the question HEAD (instruction + all options), not a window. Options are
only shortened when they would leave < 16 tokens of it, and the instruction is cut to whatever is left (>= 8). The
window N is the hard cap on the whole sequence; options that land past N lose their marker (the request is invalid).

## 4. Running the graph

Signature inputs/outputs (all float32; map by NAME, never by index):

| name | shape | content |
|---|---|---|
| in `inputs_embeds` | [1,N,768] | `table[id]` (fp16 -> fp32) for every position after right-padding ids with PAD=0 to N; **padding rows use the real row 0, not zeros** |
| in `attention_mask` | [1,N] | 1.0 for the `len(ids)` real positions (incl. CLS/SEP/MASK), 0.0 for padding |
| in `qtype_onehot` | [1,3] | choice = [1,0,0] (all our questions) |
| out `token_logits` | [1,N] | score of every position; read `token_logits[0, markers[i]]` for option i |
| out `pooled_cls` | [1,768] | only for the act head; ignore |

Window choice per step (identical math, only padding differs, verified bit-for-bit in fp32): build every question
with the largest window you are willing to run (N = 2048 or 1024); if nothing was truncated
(`room >= len(state_ids)` for every question) run the smallest signature >= the longest sequence (`s256`, `s512`,
`s1024`, `s2048`). Cost is proportional to the window, not to the real token count.

## 5. When it does not fit

Typical cost: target sequence ≈ 2 x K x (9-16) tokens + ~60 (each candidate is both an option and a `ui` line);
operation sequence ≈ 116 + state. So K ≲ 18 fits s512, K ≲ 40 fits s1024 untruncated, K ≲ 75 fits s2048.
Order of degradation, as laya does it: (1) the state's tail (`ui` lines at the bottom of the screen, then the closing
`"]}`) is cut silently and identically for every question; this is legal and is what the 1024-window fixtures
`article_carrington` show; (2) if markers would be cut, laya refuses; our policy must drop candidates from the END of
the ordered list (bottom-right of the screen), renumbering nothing (ids stay 0..K'-1 because they are positions), and
remove them from both `ui` and the target criteria, then rebuild. Prefer running `s2048` over (2).

## 6. Decoding (exactly laya `_decode_answers`)

```
raw = [token_logits[m] for m in markers]                       # K values, option order
T   = temperature_by_options.get(bucket(qtype, K), temperature[qtype]), clamped to [0.5, 5.0] (non-numbers -> 1.0)
      bucket = "choice:2" (K<=2) | "choice:3-5" | "choice:6-10" | "choice:11+"
p   = softmax(raw / T)        (subtract max; float64 or float32 both fine)
choice            = key of argmax(p) (first max)
probabilities     = {key: p}  in criteria order (laya rounds to 4 decimals for display only)
confidence        = clip(1 - H(p)/ln K, 0, 1) with H = -sum p ln max(p, 1e-12); 1.0 if K < 2   ("confidence" in laya's answer)
answer_confidence = max(p)                                                                     ("answer_confidence")
```

Laya-Android's `rl_agent_config.json`: `temperature = [1.1609286069869995, 1.0, 1.0]`, `temperature_by_options = {}`.
So every choice question (any K) uses **T = 1.1609286069869995**. (The `laya_ml_calibration.json` temperatures of the
community LiteRT package belong to the base model; do not use them.)

Which number to gate on: `answer_confidence = max(p)` is the calibrated quantity (Laya-Android reports operation
ECE 0.037 / target ECE 0.061 with this temperature on AndroidControl). `confidence` (normalised entropy) is what
laya labels "confidence" but it is not calibrated; it is lower than max(p) whenever mass is spread. For
`MIN_CONFIDENCE` and `splitChain`, use `probabilities` / `answer_confidence`; keep `confidence` only for display.

## 7. Act head: ignore it

`act_probability = softmax(act_head([pooled_cls, top1, top1-top2, entropy/ln max(K,2), max(K,2)/255]))[0]` uses the
UNscaled marker probabilities. It is exactly 1.0 on all 50 fixture questions (reference and graph), as the community
found for the base model. Laya-Android's training never used it. Do not ship or run `laya_android_act_head_fp32.tflite`.

## 8. Mapping to our app (suggested, verify in T18)

| Laya op | our action | notes |
|---|---|---|
| CLICK | CLICK at the target candidate's bounds centre | |
| LONG_PRESS | LONG_PRESS at the target | rare in training (157 examples) |
| SCROLL_DOWN / SCROLL_UP | SCROLL_DOWN (swipe UP) / SCROLL_UP (swipe DOWN) | AndroidControl "scroll down" = reveal content below |
| SCROLL_RIGHT / SCROLL_LEFT | SWIPE_LEFT / SWIPE_RIGHT | AndroidControl/AndroidWorld scroll direction = content revealed; finger moves the other way. Verify on device |
| INPUT_TEXT | TYPE into the target if editable (else the focused field); TYPE_ENTER when the task is a search | text comes from our task options, not from Laya |
| OPEN_APP | OPEN_APP (our pre-loop app resolution) | Laya picks this from the home screen even when the icon is visible |
| BACK / HOME / WAIT / DONE | same | WAIT/BACK are weak ops |

Do NOT append TYPE_ENTER / ENTER / OPEN_WEBSITE / ALL_APPS / SWIPE_* as extra criteria by default: they rarely win
(only OPEN_WEBSITE did, when the goal literally said "website"), and they move 1-22% of the mass away from the trained
ops (README, `fixtures/extra_ops_study.json`). Handle them with TaskRunner rules (OPEN_WEBSITE/OPEN_APP pre-loop,
ENTER after INPUT_TEXT for searches via CHAIN). The `text` question is also untrained and unreliable
(it preferred "7:30" over "see you at 7:30"); keep picking text from the task string with the existing logic, or ask
it only as a tie-breaker.

## 9. Parity targets for the Kotlin port (T17)

Use `fixtures/*.json`: for each row, `ui_tree` + `goal` + `history` -> your candidates must equal `candidates`, your
state must equal `state`, your ids/markers must equal `token_ids[qid]`, and your probabilities must match
`expected[qid].probabilities` within 0.01 with the same argmax (this Mac: wfp16 max 0.00042, fp32 0.00000; see
`fixtures/parity_report.json`). `expected_by_window["512"|"256"]` hold the references when running a smaller window
(including truncation effects).
