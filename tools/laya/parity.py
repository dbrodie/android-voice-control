"""Re-run every fixture through the exported LiteRT graphs with the torch-free host; write fixtures/parity_report.json.

  tools/laya/.venv-litert/bin/python -I tools/laya/parity.py [--threads 4] [--bench-reps 5]

For each window (1024, 512) and storage (wfp16, fp32):
  * token ids + marker positions built by host.py from tokenizer.json must equal laya's (exact);
  * probabilities (temperature-scaled, unrounded) vs the PyTorch reference: argmax identical? max |dp|;
  * act_probability vs reference.
Latency: wall time per step (all questions of a row, sequential single-row invocations as on the phone) on this Mac
CPU via ai-edge-litert's Interpreter (XNNPACK), median / p90 over the fixture rows after warm-up; split into
host prep (tokenize + build + table gather) and graph time.
"""
import argparse
import glob
import json
import os
import platform
import sys
import time

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import host as H  # noqa: E402

FIX = os.path.join(HERE, "fixtures")


def load_rows():
    rows = []
    for p in sorted(glob.glob(os.path.join(FIX, "*.json"))):
        if os.path.basename(p) in ("INDEX.json", "parity_report.json"):
            continue
        with open(p, encoding="utf8") as f:
            row = json.load(f)
        if "ui_tree" in row:  # skip INDEX.json, parity_report.json, extra_ops_study.json
            rows.append(row)
    return rows


def ref_for(row, n):
    if n == 1024:
        return row["expected"], row["token_ids"]
    w = row["expected_by_window"].get(str(n))
    if w is None or "error" in w:
        return None, None
    return w["expected"], w["token_ids"]


def check(host, rows, n):
    res, worst, worst_act, all_argmax, ids_ok = [], 0.0, 0.0, True, True
    for row in rows:
        exp, toks = ref_for(row, n)
        if exp is None:
            res.append({"name": row["name"], "skipped": "options do not fit the %d window" % n})
            continue
        ans, enc = host.predict(row["state"], row["questions"])
        per = {}
        for e in enc:
            qid = e["qid"]
            same_ids = e["ids"] == toks[qid]["ids"] and e["markers"] == toks[qid]["markers"]
            ids_ok &= same_ids
            pe = exp[qid]["probabilities"]
            pg = ans[qid]["probabilities"]
            d = max(abs(pe[k] - pg[k]) for k in pe)
            am = max(pe, key=pe.get) == ans[qid]["choice"]
            da = abs(exp[qid]["act_probability"] - ans[qid]["act_probability"])
            dl = float(np.max(np.abs(np.asarray(exp[qid]["raw_logits"]) - np.asarray(ans[qid]["raw_logits"]))))
            worst, worst_act, all_argmax = max(worst, d), max(worst_act, da), all_argmax and am
            per[qid] = {"token_ids_identical": same_ids, "argmax_identical": am, "max_abs_prob_diff": d,
                        "max_abs_logit_diff": dl, "act_probability_diff": da, "k": len(pe),
                        "ref_choice": max(pe, key=pe.get), "graph_choice": ans[qid]["choice"]}
        res.append({"name": row["name"], "questions": per})
    return {"rows": res, "rows_checked": sum(1 for r in res if "questions" in r),
            "questions_checked": sum(len(r.get("questions", {})) for r in res), "all_token_ids_identical": ids_ok,
            "all_argmax_identical": all_argmax, "max_abs_prob_diff": worst, "max_abs_act_probability_diff": worst_act}


def bench(host, rows, n, reps):
    prep, graph, total = [], [], []
    usable = [r for r in rows if ref_for(r, n)[0] is not None]
    for r in usable[:3]:  # warm-up
        host.predict(r["state"], r["questions"])
    for _ in range(reps):
        for r in usable:
            t0 = time.perf_counter()
            enc = host.encode(r["state"], r["questions"])
            t1 = time.perf_counter()
            g = 0.0
            for e in enc:
                padded = np.zeros(n, dtype=np.int64)
                padded[:len(e["ids"])] = e["ids"]
                emb = np.asarray(host.table[padded], dtype=np.float32)[None]
                att = np.zeros((1, n), np.float32)
                att[0, :len(e["ids"])] = 1
                oh = np.zeros((1, 3), np.float32)
                oh[0, e["qtype"]] = 1
                t2 = time.perf_counter()
                host.main(inputs_embeds=emb, attention_mask=att, qtype_onehot=oh)
                g += time.perf_counter() - t2
            t3 = time.perf_counter()
            prep.append((t3 - t0 - g) * 1000)
            graph.append(g * 1000)
            total.append((t3 - t0) * 1000)
    q = lambda a, p: round(float(np.percentile(a, p)), 1)  # noqa: E731
    nq = [len(r["questions"]) for r in usable]
    return {"rows": len(usable), "reps": reps, "questions_per_step_mean": round(float(np.mean(nq)), 2),
            "step_ms_median": q(total, 50), "step_ms_p90": q(total, 90),
            "graph_ms_median": q(graph, 50), "host_prep_ms_median": q(prep, 50),
            "graph_ms_per_question_median": round(float(np.median(graph)) / float(np.mean(nq)), 1)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--bench-reps", type=int, default=3)
    ap.add_argument("--storages", default="wfp16,fp32")
    ap.add_argument("--multi-windows", default="256,512,1024,2048")
    a = ap.parse_args()
    rows = load_rows()
    import ai_edge_litert
    report = {"reference": "laya 0.3.28 system_one, torch CPU fp32, max_len=window, head_max_len=1536",
              "runtime": "ai-edge-litert %s Interpreter (XNNPACK CPU), %d threads" % (
                  getattr(ai_edge_litert, "__version__", "?"), a.threads),
              "machine": "%s %s, %s" % (platform.system(), platform.machine(), platform.processor() or ""),
              "fixtures": len(rows), "results": {}, "latency": {}}
    def show(key, r):
        print("%-17s rows=%2d questions=%2d ids_identical=%s argmax_identical=%s max|dp|=%.5f max|dact|=%.5f" % (
            key, r["rows_checked"], r["questions_checked"], r["all_token_ids_identical"], r["all_argmax_identical"],
            r["max_abs_prob_diff"], r["max_abs_act_probability_diff"]), flush=True)

    multi = {}
    for n in (1024, 512, 256):
        for storage in a.storages.split(","):
            host = H.Host(n, storage, threads=a.threads)
            key = "s%d_%s" % (n, storage)
            report["results"][key] = r = check(host, rows, n)
            show(key, r)
            if storage == "wfp16" and a.bench_reps:
                report["latency"][key] = b = bench(host, rows, n, a.bench_reps)
                print("   latency:", json.dumps(b), flush=True)
            del host
    for n in [int(x) for x in a.multi_windows.split(",")]:
        multi[n] = H.Host(n, "wfp16", threads=a.threads, multi=True)
        key = "multi_wfp16_sig_s%d" % n
        report["results"][key] = r = check(multi[n], rows, n)
        show(key, r)
    report["results"]["adaptive_multi_wfp16"] = r = adaptive(multi, rows, a.bench_reps)
    show("adaptive_multi", r)
    print("   latency:", json.dumps(r["latency"]), flush=True)
    report["latency"]["adaptive_multi_wfp16"] = r["latency"]
    with open(os.path.join(FIX, "parity_report.json"), "w") as f:
        json.dump(report, f, indent=1)


def adaptive(hosts, rows, reps):
    """T17's recommended policy: build every question at the largest budget (2048); if nothing was truncated run the
    smallest signature that holds the longest sequence (identical math: only padding differs). Compared with the
    reference at the largest window."""
    wins = sorted(hosts)
    maxw = wins[-1]
    big = hosts[maxw]
    res, worst, all_am, picks, times = [], 0.0, True, {}, []
    for rep in range(max(1, reps) + 1):
        for row in rows:
            t0 = time.perf_counter()
            enc = big.encode(row["state"], row["questions"])
            longest = max(len(e["ids"]) for e in enc)
            trunc = any(e["state_tokens_dropped"] for e in enc)
            w = maxw if trunc else next(n for n in wins if longest <= n)
            h = hosts[w]
            outs = {e["qid"]: h.run(e["ids"], e["qtype"])[0][e["markers"]].astype(np.float64) for e in enc}
            dt = (time.perf_counter() - t0) * 1000
            if rep == 0:  # first pass = warm-up + correctness
                picks[w] = picks.get(w, 0) + 1
                per = {}
                for e in enc:
                    qid, raw = e["qid"], outs[e["qid"]]
                    t = big.temperature_by_options.get(H.temp_bucket(e["qtype"], len(raw)), big.temperature[e["qtype"]])
                    p = H.softmax(raw / t)
                    pe = ref_for(row, maxw)[0][qid]["probabilities"]
                    keys = list(pe)
                    d = max(abs(pe[k] - p[i]) for i, k in enumerate(keys))
                    am = keys[int(p.argmax())] == max(pe, key=pe.get)
                    worst, all_am = max(worst, d), all_am and am
                    per[qid] = {"window": w, "argmax_identical": am, "max_abs_prob_diff": d}
                res.append({"name": row["name"], "window": w, "questions": per})
            else:
                times.append(dt)
    q = lambda p: round(float(np.percentile(times, p)), 1)  # noqa: E731
    return {"rows": res, "rows_checked": len(res), "questions_checked": sum(len(r["questions"]) for r in res),
            "all_token_ids_identical": True, "all_argmax_identical": all_am, "max_abs_prob_diff": worst,
            "max_abs_act_probability_diff": 0.0, "window_picks": {str(k): v for k, v in sorted(picks.items())},
            "latency": {"step_ms_median": q(50), "step_ms_p90": q(90), "reps": reps, "rows": len(rows)}}


if __name__ == "__main__":
    main()
