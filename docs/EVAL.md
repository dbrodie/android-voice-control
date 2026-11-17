# System One backends: JEV vs Laya (on-device) — evaluation log

## 2026-11-17 — emulator, first end-to-end runs (T17/T18)

Device: AVD `Medium_Phone_API_36.1`, arm64-v8a, **2 GB RAM**, no GPU usable by LiteRT (gfxstream crash in HWUI
readback → GPU delegate disabled on emulators). Laya therefore ran on the **CPU reference kernels, 1 thread**
(`accelerator=cpu_ref`, `allow_low_ram=true`), which is the slowest possible path: ~19 s per question, ~40 s per
step. A phone with XNNPACK/GPU is expected at 50–200 ms per question (see tools/laya/README.md for Mac numbers:
194 ms per question at the 512 window on CPU). Numbers below are therefore about *correctness*, not speed.

### Parity (`LAYA_PARITY`, 24 T14 fixture rows through the real on-device engine)
rows=22 with questions, questions=46, state_json_ok=24/24, token ids ok 46/46, **argmax identical 46/46,
max probability diff 0.00000** (fp32 s512 graph). Engine load 7.0 s.

### Reference tasks (`RUN_TASK --es system_one laya|jev`)

| Task | Backend | Outcome | Steps | Median step latency | Notes |
|---|---|---|---:|---:|---|
| Open Settings and turn on Bluetooth | laya | done, Bluetooth on | 5 | 23 s | Connected devices → Connection preferences → Bluetooth → switch → DONE 87% |
| Please open the Clock app and show me my alarms | laya | done | 4 | 25 s | element ids #22 → #29 → #39 → DONE |
| Open Chrome and search for "weather in Berlin" | laya | done | 5 | 40 s | WAIT → CLICK search box → **TYPE_ENTER** (INPUT_TEXT on a search field → Enter heuristic) → CLICK result → DONE |
| Open Settings and turn on Dark theme (already on) | laya | done | 2 | 34 s | correct: recognised the switch was already on |
| Open Settings and turn on Dark theme (**off**) | laya | **done, but theme still off** | 2 | 47 s | step 1 CLICK "Display & touch" 97% ✓; step 2 **DONE 67%** while its target question pointed at the "Dark theme" Switch (94%). False DONE: model quality, not integration |
| Open Settings and turn on Dark theme | jev | done | 3 | 0.32 s | regression run after the seam refactor: same steps and taps as before T15 |
| Open Settings and turn on Bluetooth | jev | unsure (43–44%) | 4 | 0.4–0.5 s | pre-existing flakiness of this task with JEV (8 of 11 runs ended "unsure" before T15 too) |
| "Open" (truncated task by a quoting mistake) | laya | unsure 2% | 1 | 37 s | correct refusal: Laya wanted OPEN_APP (98%), which wasn't offered, so the top offered action had 2% |

### Findings
- Integration works end to end: tree capture → Laya candidates → two questions → exact tap on the chosen node;
  Runs screen shows "Laya", element ids and per-step latency; cost $0.
- **False DONE** is the first real quality issue: Laya-Android says DONE too early on the Display screen when the
  Dark theme switch is visible but off. Candidate fixes for T18: a per-backend higher `MIN_CONFIDENCE` for DONE
  (67% would then end as "unsure" instead of a wrong "done"), or a rule "DONE while the target question strongly
  points at a checkable element that is off → ask again / click it". Not changed yet (needs more real screens).
- The emulator cannot measure speed. Validation on a real arm64 phone (GPU/XNNPACK path) is still open.
- Byun11's candidate rules keep content under a dialog in the list (no "dialog on top" filter), as the
  fine-tune was trained; T14 saw one dialog miss on synthetic trees.
