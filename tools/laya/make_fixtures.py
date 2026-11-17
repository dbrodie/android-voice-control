"""Compute the PyTorch ground-truth parity fixtures into tools/laya/fixtures/*.json.

  tools/laya/.venv/bin/python -I tools/laya/make_fixtures.py

One file per row: {name, tree, ui_tree, goal, history, extra_ops, text_options, state, questions, candidates,
token_ids: {qid: {ids, markers, qtype}}, expected: {qid: {...}}, expected_by_window: {"1024": ..., "512": ...|null},
usage}. `expected` / `token_ids` are at the 1024 window (max_len=1024, head_max_len=1536), computed by the official
laya 0.3.28 package on CPU fp32. Probabilities are unrounded (temperature-scaled exactly as laya does).
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import candidates as C  # noqa: E402
import reference as R  # noqa: E402

FIX = os.path.join(HERE, "fixtures")
X = C.EXTRA_OP_DESC

ROWS = [
    ("settings_bt_from_main", "settings_main", "Turn on Bluetooth", ["OPEN_APP Settings"], None, None),
    ("settings_dark_theme", "settings_main", "Turn on dark theme", [], None, None),
    ("settings_storage_extra", "settings_main", "Check how much storage is free", ["OPEN_APP Settings"], X, None),
    ("settings_de_bluetooth", "settings_main", "Schalte Bluetooth ein", ["OPEN_APP Settings"], None, None),
    ("bt_toggle", "settings_bluetooth", "Turn on Bluetooth",
     ["OPEN_APP Settings", "CLICK Connected devices Bluetooth, pairing", "CLICK Bluetooth"], None, None),
    ("bt_rename", "settings_bluetooth", "Rename my phone to Daniel's Pixel",
     ["OPEN_APP Settings", "CLICK Connected devices Bluetooth, pairing"], None, ["Daniel's Pixel", "Pixel"]),
    ("chrome_search_weather", "chrome_ntp", "Search for weather in Berlin", ["OPEN_APP Chrome"], X,
     ["weather in Berlin", "Berlin"]),
    ("chrome_open_youtube", "chrome_ntp", "Open YouTube in Chrome", ["OPEN_APP Chrome"], None, None),
    ("chrome_feed_article", "chrome_ntp", "Read the article about the northern lights", [], None, None),
    ("article_carrington", "chrome_article", "Open the Wikipedia article about the Carrington Event",
     ["OPEN_APP Chrome", 'INPUT_TEXT "aurora wikipedia"', "CLICK Aurora - Wikipedia"], None, None),
    ("article_scroll_extra", "chrome_article", "Find the section about aurora photography", ["SCROLL_DOWN"], X, None),
    ("clock_enable_715", "clock_alarms", "Turn on the 7:15 AM alarm", ["OPEN_APP Clock"], None, None),
    ("clock_add_alarm", "clock_alarms", "Set an alarm for 5:45 AM", ["OPEN_APP Clock"], None, ["5:45 AM", "5:45"]),
    ("clock_open_timer", "clock_alarms", "Start a 10 minute timer", ["OPEN_APP Clock"], X, None),
    ("msg_reply", "messages_keyboard", "Reply to Dana: see you at 7:30", ["OPEN_APP Messages", "CLICK Dana Levi",
                                                                          "CLICK Text message"], X,
     ["see you at 7:30", "Dana", "7:30"]),
    ("msg_call", "messages_keyboard", "Call Dana", ["OPEN_APP Messages", "CLICK Dana Levi"], None, None),
    ("home_open_chrome", "home_screen", "Open Chrome", [], None, None),
    ("home_all_apps_extra", "home_screen", "Show me all my apps", [], X, None),
    ("drawer_whatsapp", "app_drawer", "Open WhatsApp", ["HOME", "SCROLL_UP"], None, None),
    ("drawer_zoom_extra", "app_drawer", "Open the Zoom app and join a meeting", ["HOME"], X, None),
    ("dialog_two_minutes", "dialog_over_content", "Set the screen timeout to 2 minutes",
     ["OPEN_APP Settings", "CLICK Display & touch Dark theme, font size, touch",
      "CLICK Screen timeout After 30 seconds of inactivity"], None, None),
    ("dialog_cancel", "dialog_over_content", "Never mind, close the screen timeout dialog", [], None, None),
    ("empty_game", "empty_screen", "Start a new game", ["OPEN_APP Game"], None, None),
    ("empty_game_extra", "empty_screen", "Go back to the home screen", ["OPEN_APP Game", "WAIT"], X, None),
]


def answers_with_raw(r):
    out = {}
    for (qid, ans), enc, (raw, act) in zip(r["answers"].items(), r["encoded"], r["raw"]):
        assert enc["qid"] == qid
        out[qid] = {"choice": ans["choice"], "probabilities": ans["probabilities"], "confidence": ans["confidence"],
                    "answer_confidence": ans["answer_confidence"],
                    "act_probability": ans["action"]["act_probability"], "raw_logits": raw, "act_softmax": act}
    return out


def unrounded(r, agent):
    """laya rounds probabilities to 4 decimals; recompute them unrounded from the raw logits (same temperature)."""
    import numpy as np
    from laya.common import temp_bucket
    for qid, e in r.items():
        k = len(e["raw_logits"])
        qt = 0
        t = agent.temperature_by_options.get(temp_bucket(qt, k), agent.temperature[qt])
        z = np.asarray(e["raw_logits"], np.float64) / t
        p = np.exp(z - z.max())
        p /= p.sum()
        rounded = e["probabilities"]
        e["probabilities_rounded"] = rounded
        e["probabilities"] = {kk: float(v) for kk, v in zip(rounded, p)}
        e["temperature"] = t
        assert max(abs(e["probabilities"][kk] - rounded[kk]) for kk in rounded) < 6e-4, (qid, rounded)
    return r


def main():
    os.makedirs(FIX, exist_ok=True)
    agent = R.load_agent()
    names = []
    for name, tree_name, goal, hist, extra, texts in ROWS:
        with open(os.path.join(HERE, "trees", tree_name + ".json"), encoding="utf8") as f:
            tree = json.load(f)
        by_window = {}
        for n in (1024, 512, 256, 2048):
            try:
                r = R.run(agent, tree, goal, hist, extra, texts, max_len=n, head_max_len=1536)
            except ValueError as e:  # options do not fit the window
                by_window[str(n)] = {"error": str(e)}
                continue
            by_window[str(n)] = {"expected": unrounded(answers_with_raw(r), agent), "usage": r["usage"],
                                 "token_ids": {e["qid"]: {"ids": e["ids"], "markers": e["markers"], "qtype": e["qtype"]}
                                               for e in r["encoded"]}}
            if n == 1024:
                main_r = r
        r = main_r
        row = {
            "name": name, "tree": "trees/%s.json" % tree_name, "ui_tree": tree, "goal": goal, "history": hist,
            "extra_ops": extra, "text_options": texts, "state": r["state"], "questions": r["questions"],
            "candidates": [[c["id"], "[%d] %s" % (c["id"], C.option_label(c)), c["node_index"], c["bounds"]]
                           for c in r["candidates"]],
            "window": 1024, "head_max_len": 1536,
            "token_ids": by_window["1024"]["token_ids"], "expected": by_window["1024"]["expected"],
            "usage": by_window["1024"]["usage"],
            "expected_by_window": {k: (v if "error" in v else {"expected": v["expected"], "usage": v["usage"],
                                                              "token_ids": v["token_ids"]})
                                   for k, v in by_window.items() if k != "1024"},
        }
        with open(os.path.join(FIX, name + ".json"), "w", encoding="utf8") as f:
            json.dump(row, f, ensure_ascii=False)
            f.write("\n")
        ex = row["expected"]
        print("%-24s cands=%2d tokens=%4d trunc=%-5s op=%s(%.3f) target=%s 512=%s 256=%s" % (
            name, len(row["candidates"]), row["usage"]["input_tokens"], row["usage"]["truncated"],
            ex["operation"]["choice"], ex["operation"]["probabilities"][ex["operation"]["choice"]],
            ex.get("target", {}).get("choice"), "ok" if "error" not in by_window["512"] else "does-not-fit", "ok" if "error" not in by_window["256"] else "does-not-fit"))
        names.append(name)
    with open(os.path.join(FIX, "INDEX.json"), "w") as f:
        json.dump({"rows": names, "source": "laya 0.3.28, torch CPU fp32, ByunByun/laya-android",
                   "window": 1024, "head_max_len": 1536}, f, indent=1)


if __name__ == "__main__":
    main()
