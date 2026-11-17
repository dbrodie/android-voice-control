"""Reference HOST for the exported graph: no torch, no laya -- exactly what the Kotlin engine (T17) must do.

  tools/laya/.venv-litert/bin/python -I tools/laya/host.py --tree tools/laya/trees/settings_main.json --goal "..."

Uses only: out/tokenizer.json (via the `tokenizers` library, truncation disabled), out/token_embeddings_fp16.bin,
out/rl_agent_config.json, out/laya_android_s{N}_embeds_{storage}.tflite (+ act head). Spec: HOST_CONTRACT.md.
"""
import argparse
import json
import math
import os
import sys
import time

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import candidates as C  # noqa: E402

OUT = os.path.join(HERE, "out")
PAD, SEP, CLS, MASK = 0, 1, 2, 4
MASK_TEXT = "<mask>"
QTYPES = {"choice": 0, "score": 1, "noul": 2}
OPTION_TOKENS = 48
TEMP_MIN, TEMP_MAX = 0.5, 5.0


class Tokenizer:
    def __init__(self, path):
        from tokenizers import Tokenizer as T
        self.t = T.from_file(path)
        self.t.no_truncation()  # tokenizer.json ships a stale truncation(max_length=48); laya never applies it
        self.t.no_padding()

    def encode(self, text):
        return self.t.encode(text, add_special_tokens=False).ids


def render_options(q):
    crit = q["criteria"]
    if isinstance(crit, list):
        crit = {c: None for c in crit}
    return list(crit.keys()), [str(k) if v is None or v == "" else "%s: %s" % (k, v if isinstance(v, str) else
                               json.dumps(v, ensure_ascii=False, separators=(", ", ": "), default=str))
                               for k, v in crit.items()]


def build_sequence(tok, state_ids, q, max_len, head_max_len):
    """laya.common.build_head + build_sequence (choice questions, canonical option order)."""
    _, opts = render_options(q)
    ins = q["instructions"].replace(MASK_TEXT, " ")
    head_ids = tok.encode("%s question: %s" % (q["type"], ins))
    opt_ids = [[MASK] + tok.encode(" " + o.replace(MASK_TEXT, " "))[:OPTION_TOKENS] for o in opts]
    opt_budget = head_max_len - sum(len(o) for o in opt_ids)
    if opt_budget < 16:
        per = max(4, (head_max_len - 16) // max(1, len(opt_ids)))
        opt_ids = [o[:per] for o in opt_ids]
        opt_budget = head_max_len - sum(len(o) for o in opt_ids)
    head_ids = head_ids[:max(8, opt_budget)]
    ids = [CLS] + head_ids + [SEP]
    markers = []
    for o in opt_ids:
        markers.append(len(ids))
        ids.extend(o)
    ids.append(SEP)
    room = max(0, max_len - len(ids) - 1)
    st = state_ids[:room]
    ids = ids + st + [SEP]
    ids, markers = ids[:max_len], [m for m in markers if m < max_len]
    if len(markers) != len(opts):
        raise ValueError("only %d of %d option markers fit in %d tokens" % (len(markers), len(opts), max_len))
    return ids, markers, len(state_ids) - len(st)


def serialize_state(state):
    return state if isinstance(state, str) else json.dumps(state, ensure_ascii=False)


def softmax(z):
    z = np.asarray(z, dtype=np.float64)
    e = np.exp(z - z.max())
    return e / e.sum()


def temp_bucket(qtype, k):
    size = "2" if k <= 2 else "3-5" if k <= 5 else "6-10" if k <= 10 else "11+"
    return "%s:%s" % ({0: "choice", 1: "score", 2: "noul"}[qtype], size)


def clamp_t(t):
    try:
        t = float(t)
    except (TypeError, ValueError):
        return 1.0
    if t != t or math.isinf(t):
        return 1.0
    return min(TEMP_MAX, max(TEMP_MIN, t))


class Host:
    def __init__(self, window=1024, storage="wfp16", head_max_len=1536, threads=4, out_dir=OUT, act_head=True,
                 multi=False):
        """multi=True: use laya_android_multi_embeds_{storage}.tflite and its signature `s{window}`."""
        from ai_edge_litert.interpreter import Interpreter
        self.n, self.head_max_len = window, head_max_len
        self.tok = Tokenizer(os.path.join(out_dir, "tokenizer.json"))
        meta = json.load(open(os.path.join(out_dir, "token_embeddings.json")))
        self.table = np.memmap(os.path.join(out_dir, meta["filename"]), dtype="<f2", mode="r", shape=tuple(meta["shape"]))
        cfg = json.load(open(os.path.join(out_dir, "rl_agent_config.json")))
        self.temperature = [clamp_t(t) for t in cfg.get("temperature", [1, 1, 1])]
        self.temperature_by_options = {k: clamp_t(v) for k, v in (cfg.get("temperature_by_options") or {}).items()}
        if multi:
            path = os.path.join(out_dir, "laya_android_multi_embeds_%s.tflite" % storage)
            sig = "s%d" % window
        else:
            path = os.path.join(out_dir, "laya_android_s%d_embeds_%s.tflite" % (window, storage))
            sig = "serving_default"
        self.interp = Interpreter(model_path=path, num_threads=threads)
        self.main = self.interp.get_signature_runner(sig)
        self.act = None
        if act_head:
            ai = Interpreter(model_path=os.path.join(out_dir, "laya_android_act_head_fp32.tflite"), num_threads=1)
            self.act = ai.get_signature_runner("serving_default")
            self._act_interp = ai

    def encode(self, state, questions):
        state_ids = self.tok.encode(serialize_state(state).replace(MASK_TEXT, " "))
        rows = []
        for qid, q in questions.items():
            ids, markers, dropped = build_sequence(self.tok, state_ids, q, self.n, self.head_max_len)
            rows.append({"qid": qid, "ids": ids, "markers": markers, "qtype": QTYPES[q["type"]],
                         "state_tokens_dropped": dropped})
        return rows

    def run(self, ids, qtype):
        n = self.n
        padded = np.zeros(n, dtype=np.int64)
        padded[:len(ids)] = ids
        emb = np.asarray(self.table[padded], dtype=np.float32)[None]
        att = np.zeros((1, n), np.float32)
        att[0, :len(ids)] = 1.0
        oh = np.zeros((1, 3), np.float32)
        oh[0, qtype] = 1.0
        out = self.main(inputs_embeds=emb, attention_mask=att, qtype_onehot=oh)
        return out["token_logits"][0], out["pooled_cls"]

    def act_probability(self, pooled, raw):
        p = softmax(raw)
        k = max(len(raw), 2)
        top = np.sort(p)[::-1]
        t1, t2 = top[0], (top[1] if len(top) > 1 else 0.0)
        ent = -(p * np.log(np.maximum(p, 1e-9))).sum() / math.log(k)
        feats = np.array([[t1, t1 - t2, ent, k / 255.0]], np.float32)
        a = self.act(pooled_cls=pooled.astype(np.float32), feats=feats)["act_logits"][0]
        return float(softmax(a)[0])

    def predict(self, state, questions):
        rows = self.encode(state, questions)
        answers = {}
        for r in rows:
            q = questions[r["qid"]]
            logits, pooled = self.run(r["ids"], r["qtype"])
            raw = logits[r["markers"]].astype(np.float64)
            k = len(raw)
            t = self.temperature_by_options.get(temp_bucket(r["qtype"], k), self.temperature[r["qtype"]])
            p = softmax(raw / t)
            keys, _ = render_options(q)
            ent = -(p * np.log(np.clip(p, 1e-12, 1.0))).sum()
            answers[r["qid"]] = {
                "type": "choice", "choice": keys[int(p.argmax())],
                "probabilities": {kk: float(v) for kk, v in zip(keys, p)},
                "confidence": float(np.clip(1.0 - ent / math.log(k), 0, 1)) if k >= 2 else 1.0,
                "answer_confidence": float(p.max()),
                "raw_logits": raw.tolist(),
                "act_probability": self.act_probability(pooled, raw) if self.act else None,
            }
        return answers, rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tree", required=True)
    ap.add_argument("--goal", required=True)
    ap.add_argument("--history", action="append", default=[])
    ap.add_argument("--extra-ops", action="store_true")
    ap.add_argument("--text", action="append", default=[])
    ap.add_argument("--window", type=int, default=1024)
    ap.add_argument("--storage", default="wfp16", choices=["wfp16", "fp32"])
    a = ap.parse_args()
    tree = json.load(open(a.tree, encoding="utf8"))
    st, qs, _ = C.state_and_questions(tree, a.goal, a.history, C.EXTRA_OP_DESC if a.extra_ops else None, a.text or None)
    h = Host(a.window, a.storage)
    t0 = time.perf_counter()
    ans, rows = h.predict(st, qs)
    ms = (time.perf_counter() - t0) * 1000
    for line in st["ui"]:
        print(line)
    for qid, x in ans.items():
        top = sorted(x["probabilities"].items(), key=lambda kv: -kv[1])[:5]
        print("%s: %s conf=%.4f  top5=%s" % (qid, x["choice"], x["confidence"], ", ".join("%s=%.4f" % kv for kv in top)))
    print("%.0f ms for %d questions" % (ms, len(rows)))


if __name__ == "__main__":
    main()
