"""How does Laya-Android treat operation criteria it was never trained on? (PyTorch reference, CPU fp32)

  tools/laya/.venv/bin/python -I tools/laya/extra_ops_study.py

Part A: every fixture scenario is asked twice, with the 12 trained operations only and with our 6 extra app actions
appended (candidates.EXTRA_OP_DESC). Reports: does the argmax among the 12 trained ops change, how much mass the extras
absorb, and the total-variation distance between the two 12-op distributions after renormalising.
Part B: probes where one extra action is the right answer, to see whether the model can pick an untrained label.
Part C: the `text` choice question (also untrained) on the fixture rows that have text options.
Writes fixtures/extra_ops_study.json.
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import candidates as C  # noqa: E402
import make_fixtures as MF  # noqa: E402
import reference as R  # noqa: E402

PROBES = [  # (tree, goal, history, op we would want)
    ("chrome_ntp", "Search for weather in Berlin", ["OPEN_APP Chrome", "CLICK Search or type URL",
                                                   'INPUT_TEXT "weather in Berlin"'], "ENTER"),
    ("chrome_ntp", "Type weather in Berlin into the search box and press enter", ["OPEN_APP Chrome",
                                                                                 "CLICK Search or type URL"], "TYPE_ENTER"),
    ("chrome_ntp", "Go to the website wikipedia.org", ["OPEN_APP Chrome"], "OPEN_WEBSITE"),
    ("home_screen", "Open the app drawer and show all apps", [], "ALL_APPS"),
    ("home_screen", "Swipe left to the next home screen page", [], "SWIPE_LEFT"),
    ("messages_keyboard", "Reply to Dana: see you at 7:30 and send it", ["CLICK Text message",
                                                                        'INPUT_TEXT "see you at 7:30"'], "ENTER"),
]


def ops_probs(agent, tree, goal, hist, extra):
    r = R.run(agent, tree, goal, hist, extra, None, max_len=1024, head_max_len=1536)
    return r["answers"]["operation"]["probabilities"]


def main():
    agent = R.load_agent()
    trees = {}

    def tree(name):
        if name not in trees:
            with open(os.path.join(HERE, "trees", name + ".json"), encoding="utf8") as f:
                trees[name] = json.load(f)
        return trees[name]

    part_a = []
    for name, tname, goal, hist, _, _ in MF.ROWS:
        base = ops_probs(agent, tree(tname), goal, hist, None)
        ext = ops_probs(agent, tree(tname), goal, hist, C.EXTRA_OP_DESC)
        trained = list(C.OP_DESC)
        mass = sum(v for k, v in ext.items() if k not in C.OP_DESC)
        ren = {k: ext[k] / max(1e-9, 1 - mass) for k in trained}
        tv = 0.5 * sum(abs(base[k] - ren[k]) for k in trained)
        b_arg, e_arg = max(base, key=base.get), max(ext, key=ext.get)
        part_a.append({"name": name, "goal": goal, "base_argmax": b_arg, "base_p": base[b_arg],
                       "extra_argmax": e_arg, "extra_p": ext[e_arg], "extra_mass": round(mass, 4),
                       "trained_argmax_changed": max(ren, key=ren.get) != b_arg, "tv_trained": round(tv, 4),
                       "top_extra": max(((k, v) for k, v in ext.items() if k not in C.OP_DESC), key=lambda kv: kv[1])})
        a = part_a[-1]
        print("A %-24s base=%s(%.2f) +extras=%s(%.2f) extra_mass=%.3f tv=%.3f top_extra=%s(%.3f)" % (
            name, b_arg, base[b_arg], e_arg, ext[e_arg], mass, tv, *a["top_extra"]))
    part_b = []
    for tname, goal, hist, want in PROBES:
        ext = ops_probs(agent, tree(tname), goal, hist, C.EXTRA_OP_DESC)
        base = ops_probs(agent, tree(tname), goal, hist, None)
        top3 = sorted(ext.items(), key=lambda kv: -kv[1])[:3]
        part_b.append({"tree": tname, "goal": goal, "history": hist, "wanted": want, "p_wanted": ext[want],
                       "rank_wanted": sorted(ext, key=ext.get, reverse=True).index(want) + 1, "top3": top3,
                       "base_top": max(base, key=base.get)})
        print("B %-62s want=%-12s p=%.3f rank=%d top3=%s base=%s" % (goal[:62], want, ext[want], part_b[-1]["rank_wanted"],
                                                                       ", ".join("%s %.2f" % kv for kv in top3), part_b[-1]["base_top"]))
    part_c = []
    for name, tname, goal, hist, extra, texts in MF.ROWS:
        if not texts:
            continue
        r = R.run(agent, tree(tname), goal, hist, extra, texts, max_len=1024, head_max_len=1536)
        p = r["answers"]["text"]["probabilities"]
        part_c.append({"name": name, "goal": goal, "options": texts, "probs": {texts[int(k)]: v for k, v in p.items()}})
        print("C %-24s %s" % (name, part_c[-1]["probs"]))
    with open(os.path.join(HERE, "fixtures", "extra_ops_study.json"), "w", encoding="utf8") as f:
        json.dump({"extra_ops": C.EXTRA_OP_DESC, "part_a_distribution_shift": part_a, "part_b_untrained_targets": part_b,
                   "part_c_text_question": part_c}, f, ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
