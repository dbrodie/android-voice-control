"""Latency probe for the exported main graphs on this machine (ai-edge-litert Interpreter, XNNPACK CPU).

  tools/laya/.venv-litert/bin/python -I tools/laya/bench.py [--threads 4,8] [--reps 5]

One invocation = one question at the full fixed window (cost does not depend on how many tokens are real).
"""
import argparse
import os
import time

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")


def t_graph(path, n, threads, reps):
    from ai_edge_litert.interpreter import Interpreter
    t0 = time.perf_counter()
    it = Interpreter(model_path=path, num_threads=threads)
    r = it.get_signature_runner("serving_default")
    load_ms = (time.perf_counter() - t0) * 1000
    emb = np.random.RandomState(0).randn(1, n, 768).astype(np.float32) * 0.05
    att = np.ones((1, n), np.float32)
    oh = np.array([[1, 0, 0]], np.float32)
    t0 = time.perf_counter()
    r(inputs_embeds=emb, attention_mask=att, qtype_onehot=oh)
    first_ms = (time.perf_counter() - t0) * 1000
    ts = []
    for _ in range(reps):
        t0 = time.perf_counter()
        r(inputs_embeds=emb, attention_mask=att, qtype_onehot=oh)
        ts.append((time.perf_counter() - t0) * 1000)
    return load_ms, first_ms, float(np.median(ts))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--threads", default="4,8")
    ap.add_argument("--reps", type=int, default=5)
    ap.add_argument("--windows", default="512,1024")
    ap.add_argument("--storages", default="wfp16,fp32")
    a = ap.parse_args()
    for n in [int(x) for x in a.windows.split(",")]:
        for st in a.storages.split(","):
            for th in [int(x) for x in a.threads.split(",")]:
                p = os.path.join(OUT, "laya_android_s%d_embeds_%s.tflite" % (n, st))
                load, first, med = t_graph(p, n, th, a.reps)
                print("s%-5d %-6s threads=%d  load %.0f ms  first %.0f ms  median %.0f ms/question" % (
                    n, st, th, load, first, med), flush=True)


if __name__ == "__main__":
    main()
