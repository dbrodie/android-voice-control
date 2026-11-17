"""Ground truth: run Laya-Android through the official `laya` package (PyTorch, CPU, fp32) on one UiTree.

  tools/laya/.venv/bin/python -I tools/laya/reference.py --tree tools/laya/trees/settings_main.json \
      --goal "Turn on Bluetooth" [--history 'OPEN_APP Settings'] [--extra-ops] [--text "hello" --text "world"] \
      [--max-len 1024] [--head-max-len 1536] [--json]

Prints the candidate list, then the full operation / target (/ text) probability distributions.
`--max-len` defaults to 1024 = the exported window, so the reference sees exactly what the graph sees; pass 3072 to
get the training-time budget (identical unless the state is truncated at 1024, which `usage.truncated` reports).
"""
import argparse
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import candidates as C  # noqa: E402

DEFAULT_MODEL = os.path.join(HERE, "work", "hf", "laya-android")


def load_agent(model_dir=DEFAULT_MODEL):
    import laya
    import torch
    torch.manual_seed(0)
    return laya.load(model_dir, device="cpu")  # CPU => fp32 forward (no autocast unless LAYA_CPU_AMP is set)


def encode(agent, state, questions, max_len, head_max_len):
    """laya's own per-question encoding: [{qid, ids, markers, qtype}] (what the host must reproduce)."""
    ids = list(questions)
    for q in ids:
        agent._check_question(q, questions[q])
    internal = {q: agent._to_internal(questions[q]) for q in ids}
    items = agent._encode_state(state, ids, internal, max_len=max_len, head_max_len=head_max_len)
    return [{"qid": q, "ids": it["ids"], "markers": it["markers"], "qtype": it["qtype"],
             "state_tokens_dropped": it["state_stats"]["state_tokens_dropped"]} for q, it in zip(ids, items)]


def raw_logits(agent, enc):
    """Unscaled marker logits + act softmax per question, from laya's model on laya's collate (batch = all questions)."""
    import torch
    from laya.common import collate_items
    items = [{"ids": e["ids"], "markers": e["markers"], "qtype": e["qtype"]} for e in enc]
    b = collate_items([items], agent.tok.pad_token_id)
    with torch.no_grad():
        logits, act = agent.model(b["input_ids"], b["attention_mask"], b["marker_pos"], b["marker_mask"], b["qtype"])
    act = torch.softmax(act.float(), -1)
    return [(logits[j, :len(e["markers"])].float().tolist(), act[j].tolist()) for j, e in enumerate(enc)]


def run(agent, tree, goal, history, extra_ops=None, text_options=None, max_len=1024, head_max_len=1536):
    state, questions, cands = C.state_and_questions(tree, goal, history, extra_ops, text_options)
    out = agent.system_one(state, questions, max_len=max_len, head_max_len=head_max_len)
    enc = encode(agent, state, questions, max_len, head_max_len)
    raws = raw_logits(agent, enc)
    return {"state": state, "questions": questions, "candidates": cands, "answers": out["answers"],
            "usage": out["usage"], "encoded": enc, "raw": raws}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tree", required=True)
    ap.add_argument("--goal", required=True)
    ap.add_argument("--history", action="append", default=[], help="repeatable, oldest first; last 3 are used")
    ap.add_argument("--extra-ops", action="store_true", help="append TYPE_ENTER, ENTER, OPEN_WEBSITE, ALL_APPS, "
                    "SWIPE_LEFT, SWIPE_RIGHT as extra (untrained) operation criteria")
    ap.add_argument("--text", action="append", default=[], help="text option (repeatable) -> extra `text` question")
    ap.add_argument("--max-len", type=int, default=1024)
    ap.add_argument("--head-max-len", type=int, default=1536)
    ap.add_argument("--model", default=DEFAULT_MODEL)
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args()
    with open(a.tree, encoding="utf8") as f:
        tree = json.load(f)
    agent = load_agent(a.model)
    r = run(agent, tree, a.goal, a.history, C.EXTRA_OP_DESC if a.extra_ops else None, a.text or None,
            a.max_len, a.head_max_len)
    if a.json:
        print(json.dumps({k: r[k] for k in ("state", "questions", "answers", "usage")}, ensure_ascii=False, indent=1))
        return
    for line in r["state"]["ui"]:
        print(line)
    print("usage:", json.dumps(r["usage"]))
    for qid, ans in r["answers"].items():
        print("\n== %s: choice=%s confidence=%.4f answer_confidence=%.4f act_probability=%.4f"
              % (qid, ans["choice"], ans["confidence"], ans["answer_confidence"], ans["action"]["act_probability"]))
        for k, p in sorted(ans["probabilities"].items(), key=lambda kv: -kv[1]):
            extra = ""
            if qid == "target":
                extra = "  " + r["state"]["ui"][int(k)]
            elif qid == "text":
                extra = "  " + a.text[int(k)]
            print("  %-14s %.4f%s" % (k, p, extra))


if __name__ == "__main__":
    main()
