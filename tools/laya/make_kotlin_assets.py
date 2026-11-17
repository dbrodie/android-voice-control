"""Golden data for the Kotlin Laya host (T17), from the T14 fixtures. Pure stdlib; run from anywhere:

  python3 -I tools/laya/make_kotlin_assets.py

Writes
  app/src/test/resources/laya/state_json_cases.json   state -> json.dumps(state, ensure_ascii=False), for
                                                       LayaSequence.stateJson (fixture states + tricky strings)
  app/src/debug/assets/laya/parity.json                compact rows for the debug LAYA_PARITY broadcast: state,
                                                       questions, and per window the expected ids/markers and
                                                       probabilities (or the error laya raised)
"""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
FIX = os.path.join(HERE, "fixtures")


def rows():
    for name in json.load(open(os.path.join(FIX, "INDEX.json")))["rows"]:
        yield json.load(open(os.path.join(FIX, name + ".json"), encoding="utf8"))


def main():
    cases = []
    parity = []
    for d in rows():
        st = d["state"]
        cases.append({"name": d["name"], "goal": st["goal"], "history": st["history"], "ui": st["ui"],
                      "json": json.dumps(st, ensure_ascii=False)})
        windows = {}
        base_w = str(d["window"])
        by_w = dict(d["expected_by_window"])
        by_w[base_w] = {"expected": d["expected"], "token_ids": d["token_ids"], "usage": d["usage"]}
        for w, v in sorted(by_w.items(), key=lambda kv: int(kv[0])):
            if "error" in v:
                windows[w] = {"error": v["error"]}
                continue
            windows[w] = {
                "truncated": v["usage"]["truncated"],
                "questions": {q: {"ids": v["token_ids"][q]["ids"], "markers": v["token_ids"][q]["markers"],
                                  "probabilities": [v["expected"][q]["probabilities"][k]
                                                    for k in d["questions"][q]["criteria"]]}
                              for q in d["questions"]},
            }
        parity.append({
            "name": d["name"], "goal": st["goal"], "history": st["history"], "ui": st["ui"],
            "state_json": json.dumps(st, ensure_ascii=False),
            "questions": {q: {"instructions": v["instructions"],
                              "keys": list(v["criteria"].keys()),
                              "descs": list(v["criteria"].values())}
                          for q, v in d["questions"].items()},
            "windows": windows,
        })
    tricky = [
        ("a/b\\c \"q\"    tab\t nl\n cr\r bs\b ff\f nul\u0000 \u001f del\u007f", [], []),
        ("Emoji \U0001F600 and Hebrew שלום, CJK 中文", ["CLICK café", "INPUT_TEXT \"x\""],
         ["[0] <mask> (Button)", "[1] - (View) edit"]),
        ("", [""], [""]),
    ]
    for g, h, u in tricky:
        cases.append({"name": "tricky", "goal": g, "history": h, "ui": u,
                      "json": json.dumps({"goal": g, "history": h, "ui": u}, ensure_ascii=False)})
    out1 = os.path.join(ROOT, "app/src/test/resources/laya/state_json_cases.json")
    out2 = os.path.join(ROOT, "app/src/debug/assets/laya/parity.json")
    for p in (out1, out2):
        os.makedirs(os.path.dirname(p), exist_ok=True)
    json.dump(cases, open(out1, "w", encoding="utf8"), ensure_ascii=True, indent=0)
    json.dump({"rows": parity, "source": "tools/laya/fixtures (T14), laya 0.3.28 torch fp32 CPU"},
              open(out2, "w", encoding="utf8"), ensure_ascii=True, separators=(",", ":"))
    print(out1, len(cases), "cases;", out2, os.path.getsize(out2), "bytes")


if __name__ == "__main__":
    main()
