# tools/laya: Laya-Android -> LiteRT, reference, host contract, parity fixtures (T14)

Everything the Kotlin on-device engine (T17) needs to run [ByunByun/laya-android](https://huggingface.co/ByunByun/laya-android)
(322M, mmBERT-base + Laya decision head, Apache-2.0, `laya==0.3.28`) on a phone, without training:

| Deliverable | Where |
|---|---|
| Candidate extraction + serialization for OUR `UiTree` JSON (faithful port of Byun11 `src/candidates.py` / `src/serialization.py`) | `candidates.py` (rules in its docstring), `test_candidates.py` |
| PyTorch ground truth through the official `laya` package | `reference.py` |
| Mobile graph: LiteRT `.tflite`, embeddings-input form, windows 256/512/1024/2048 | `export.py` -> `out/` (binaries gitignored, `out/MANIFEST.json` committed) |
| Torch-free reference host (what Kotlin must do) | `host.py` |
| Host contract for Kotlin | `HOST_CONTRACT.md` |
| 24 parity rows over 10 realistic hand-built trees + parity report | `make_trees.py` -> `trees/`, `make_fixtures.py` + `parity.py` -> `fixtures/` |
| Extra (untrained) operation criteria and `text` question study | `extra_ops_study.py` -> `fixtures/extra_ops_study.json` |
| Latency on this Mac | `parity.py`, `bench.py` |

## Setup (macOS arm64; Linux works the same)

```bash
cd tools/laya
uv venv --python 3.12 .venv        && uv pip install --python .venv/bin/python laya==0.3.28 torch transformers safetensors onnx onnxruntime huggingface_hub
uv venv --python 3.12 .venv-litert && uv pip install --python .venv-litert/bin/python litert-torch==0.9.3 laya==0.3.28 onnx onnxruntime
# litert-torch 0.9.3 pins torch 2.12.1 and brings ai-edge-litert 2.1.6 + ai-edge-quantizer 0.8.0 (the community recipe's versions)
.venv/bin/python -I -c "from huggingface_hub import snapshot_download as s; s('ByunByun/laya-android', local_dir='work/hf/laya-android', allow_patterns=['*.json','model.safetensors','tokenizer/*','encoder/*'])"
```
`work/` holds downloads (untrusted data: never run code from it). Run every script with its explicit path and `-I`.

## Run

```bash
.venv/bin/python -I make_trees.py                     # trees/*.json (10 screens)
.venv/bin/python -I candidates.py trees/clock_alarms.json
.venv/bin/python -I reference.py --tree trees/settings_main.json --goal "Turn on Bluetooth" --history "OPEN_APP Settings" [--extra-ops] [--text "..."]
.venv-litert/bin/python -I export.py --windows 1024,512 --multi 256,512,1024,2048     # ~2 min on an M5
.venv/bin/python -I make_fixtures.py                  # PyTorch ground truth -> fixtures/*.json
.venv-litert/bin/python -I parity.py                  # graphs vs fixtures -> fixtures/parity_report.json (+ latency)
.venv-litert/bin/python -I host.py --tree trees/app_drawer.json --goal "Open WhatsApp"
.venv-litert/bin/python -I bench.py                   # raw graph latency per window
.venv/bin/python -I extra_ops_study.py
.venv/bin/python -I manifest.py                       # out/MANIFEST.json
.venv/bin/python -I test_candidates.py
```

## Path taken: LiteRT, converted on this Mac

litert-torch 0.9.3 installs and converts natively on macOS arm64 (no Docker needed), so the community recipe
(`litert-community/Laya-Multilingual-LiteRT`) was reproduced for the fine-tuned checkpoint; ONNX was not needed
(`export.py --onnx` can still write one). The conversion script of the community package is not published, only its
description, so `export.py` re-implements it: the laya `DecisionModel` is rewritten as one explicit fixed-shape
module (host-side token lookup, embedding LayerNorm, 22 ModernBERT layers with RoPE baked per position, padding and
`|i-j|<=64` sliding-window masks as additive -1e4 constants, rank-4 attention, exact GELU, GeGLU; `+ qtype_onehot @
type_emb`; the two pre-norm ReLU head layers written out; scorer at every position). It matches laya's own forward to
<= 1.7e-5 in logits before conversion. FC weights are then stored fp16 with ai-edge-quantizer `float_casting`
(fp32 compute), as in the community `wfp16` files. The token table is exported from the checkpoint, which is
natively fp16, so the table is bit-exact.

Not done (and why): the community package's three fp16-overflow rewrites (LayerNorm pre-scaling etc.) for GPU-FP16 /
NPU. T17 should run the GPU with explicit FP32 precision, as the community recommends. NPU or GPU-FP16 would need
those rewrites added to `export.py` first. Not validated on a phone: this task had no device step. GPU compilation
of the multi-signature file is untested. If the GPU delegate rejects it, use the single-window files.

**New vs the community export:** one `.tflite` with four signatures (`s256`, `s512`, `s1024`, `s2048`) sharing a
single copy of the weights: 275 MB instead of ~250 MB per window. T17 runs the smallest signature that holds the step
(cost scales with the window, not the real tokens), and `s2048` covers p99 screens (65 candidates) without cutting
the state. Laya-Android was trained with `max_len` 3072, so 2048 is still inside its training distribution.

## What was verified

- **laya internals** (read from the installed 0.3.28 source): one sequence per question
  (`[CLS] choice question: <ins> [SEP] [MASK] key: desc ... [SEP] <json state> [SEP]`), options capped at 48 tokens,
  `head_max_len` semantics, right-truncation of the state, `<mask>` scrubbing, temperature lookup + clamp, both
  confidence definitions, act head features. Laya-Android: `temperature=[1.1609,1,1]`, no per-bucket temperatures,
  `head_max_len` 1536, `max_len` 3072. All in `HOST_CONTRACT.md`.
- **Reference sanity**: the Byun11 README quickstart reproduces on CPU fp32 (CLICK 0.9338, target 4 0.9339 vs the
  README's bf16-GPU 0.9347 / 0.9313).
- **Tokenizer**: `tokenizers` + `tokenizer.json` (with the stale `truncation: 48` disabled) reproduces laya's ids and
  marker positions exactly on all rows, including Hebrew, emoji, `<mask>` in screen text, German goal.
- **Parity** (`fixtures/parity_report.json`, 24 rows / 50 questions; ai-edge-litert 2.1.6 Interpreter, CPU):

| Graph | Rows / questions | Token ids identical | Argmax identical | Max abs prob diff |
|---|---|---|---|---|
| s1024 wfp16 / fp32 | 24 / 50 | yes | yes | 0.00042 / 0.00000 |
| s512 wfp16 / fp32 | 22 / 46 (2 rows do not fit 512) | yes | yes | 0.00042 / 0.00000 |
| s256 wfp16 / fp32 | 17 / 35 | yes | yes | 0.00030 / 0.00000 |
| multi wfp16, signatures s256 / s512 / s1024 / s2048 | 17 / 22 / 24 / 24 rows | yes | yes | 0.00030 / 0.00042 / 0.00042 / 0.00042 |
| adaptive (smallest multi signature that fits, vs the 2048 reference) | 24 / 50 | yes | yes | 0.00042 |

Target was argmax identical on every row and max diff < 0.01. The references at each window include that window's state
truncation (`expected_by_window`).

- **Act head**: act_probability is 1.0 on every question (reference and graph), so it is ignored. See the contract.

## Latency on this Mac (Apple M5, 4P+6E cores, ai-edge-litert XNNPACK CPU, 4 threads)

The machine was shared with other agents (load average ~10), so treat these as upper bounds.

| Window | Graph ms per question (`bench.py`, multi wfp16, load ~7) | Step ms, both questions, fixture rows (`parity.py`, single wfp16, load ~8-10) |
|---|---:|---:|
| 256 | 94 | 175 median (p90 258) |
| 512 | 194 | 374 median (p90 563) |
| 1024 | 478 | 960 median (p90 1400) |
| 2048 | 1320 | - |
| adaptive (smallest window that fits, per step) | - | **394 median** (p90 998) |

Host prep (tokenize + build + fp16 table gather) is about 1 ms per step. In earlier, quieter runs 512/1024 came in at
139-158 / 343-430 ms per question (`bench.py`, fp32 and wfp16, 4-8 threads). Cost is compute-bound, at about 1 TFLOP/s of fp32 on 4 P-cores.

Phone expectations (not measured here): the community measured the same architecture on a Galaxy S26 at 52 ms per
question at 256 tokens on the GPU (FP32), 163 ms on the CPU, and 138 ms at 512 on the GPU. Cost grows a bit faster
than linearly with the window. Use the GPU, and the smallest window that fits.

## Hit rate on the hand-built screens (qualitative; T18 must measure real ones)

Of 23 scenarios with a sensible target (fixtures, 1024 window), the model's operation + target were right or
defensible in 18: Bluetooth row / switch, Display for dark theme, Storage, Device name, URL bar for search,
YouTube tile, feed article, Carrington link (50 candidates), 7:15 switch, Add alarm, Timer tab, WhatsApp / Zoom in
the drawer (53 candidates), 2-minute radio, compose field for reply, OPEN_APP from home. Misses: Cancel in a dialog
(picked the row underneath), "Call Dana" (picked the text field, not Voice call), "Go back to the home screen" on an
empty screen (CLICK instead of HOME), "Show all apps" (OPEN_APP; ALL_APPS is not a trained op), "find the
photography section" (CLICK a link instead of SCROLL_DOWN). German goal works (same choice as English, lower
confidence). These trees are synthetic; T16 should dump real ones from the phone and re-run `reference.py` on them.

## Extra criteria (TYPE_ENTER, ENTER, OPEN_WEBSITE, ALL_APPS, SWIPE_LEFT/RIGHT) and the `text` question

Laya scores any criteria list: each option is a `[MASK] KEY: description` span, so unknown labels get a score
instead of an error. But Laya-Android never saw them in training (`fixtures/extra_ops_study.json`):
- Appending the 6 extras never changed the argmax among the 12 trained ops (24 scenarios). They absorbed 0.3-22% of
  the mass (most when semantically close, e.g. TYPE_ENTER 0.20 on a reply), and the renormalised 12-op distribution
  moved by a total-variation distance of 0.003-0.14. So the main cost is lower, less calibrated confidence: steps
  near `MIN_CONFIDENCE` flip to "unsure".
- When an extra op was the correct answer, it won only once: OPEN_WEBSITE (0.64) for "Go to the website
  wikipedia.org". ENTER after typing, ALL_APPS, SWIPE_LEFT and TYPE_ENTER ranked 2nd-4th with 0.02-0.13.
- The `text` choice question is untrained and unreliable: "see you at 7:30" lost to "7:30" (0.61) and "Daniel's
  Pixel" lost to "Pixel" (0.72). Good only when one option is clearly the query ("weather in Berlin" 0.91).
Recommendation: ask the 12 trained ops only. Map them to our actions (contract section 8), and keep
OPEN_APP/OPEN_WEBSITE pre-loop handling, ENTER-after-search (CHAIN) and the text choice in TaskRunner logic until T19
fine-tunes these ops in.

## Notes for T16/T17

- Candidate rules are Byun11's exactly, including two quirks: no "dialog on top" filter (AndroidControl trees
  contain every window, so content under a dialog stays in the list; the plan's dialog rule would be out of
  distribution, and the dialog miss above suggests evaluating both in T18), and a whitespace-only `text` hides
  `desc`. Status-bar (`system` window) nodes are kept if actionable.
- Labels come from `text` first. For an empty EditText, check whether our capture puts the hint in `text`
  (AndroidControl's `text` for an empty field is usually empty, with the hint in `hint`).
- JSON for the state must be byte-identical to Python's `json.dumps(ensure_ascii=False)`. Write the serializer by
  hand (contract section 3).
- `fixtures/*.json` carry `ui_tree` -> `candidates` -> `state` -> `token_ids` -> `expected` per row: golden data for
  JVM tests of every stage.
