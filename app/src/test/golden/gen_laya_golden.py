"""Runs Byun11/laya-android's own candidate extraction + serialization on our UiTree JSON fixtures and writes
app/src/test/resources/trees/laya_golden.json, which LayaCandidatesTest compares the Kotlin port against byte for byte.

    git clone https://github.com/Byun11/laya-android /tmp/laya_src/laya-android   # tested at ab216d4
    python3 app/src/test/golden/gen_laya_golden.py /tmp/laya_src/laya-android

Stdlib only (src/accessibility.py, src/candidates.py, src/serialization.py import nothing else). UiTree -> their
AndroidControl node format: window = our window id, window_type = AndroidAccessibilityWindowInfo.WindowType number,
id = our node index, depth = distance from the root (root 0), children = child indices in node order.
"""
import glob
import json
import os
import sys

LAYA = sys.argv[1] if len(sys.argv) > 1 else "/tmp/laya_src/laya-android"
sys.path.insert(0, os.path.join(LAYA, "src"))
import accessibility as A  # noqa: E402
import candidates as C  # noqa: E402
import serialization as S  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
TREES = os.path.normpath(os.path.join(HERE, "..", "resources", "trees"))
WTYPE = {"unknown": 0, "application": 1, "input_method": 2, "system": 3, "accessibility_overlay": 4,
         "split_screen_divider": 5, "magnification_overlay": 6}


def to_laya(tree):
    nodes = tree["nodes"]
    wtype = {w["id"]: WTYPE.get(w["type"], 0) for w in tree["windows"]}
    out = []
    for i, n in enumerate(nodes):
        p = n.get("parent", -1)
        depth = out[p]["depth"] + 1 if 0 <= p < i else 0
        d = {"window": n.get("window", 0), "window_type": wtype.get(n.get("window", 0), 0), "id": i,
             "depth": depth, "children": [], "bounds": tuple(n.get("bounds", [0, 0, 0, 0])),
             "class": n.get("class", ""), "text": n.get("text", ""), "content_desc": n.get("desc", ""),
             "hint": n.get("hint", ""), "resource_id": n.get("res_id", "")}
        for k in ("clickable", "long_clickable", "editable", "scrollable", "checkable", "checked", "selected",
                  "focused", "password"):
            d[k] = bool(n.get(k, False))
        d["enabled"] = bool(n.get("enabled", True))
        d["visible"] = bool(n.get("visible", True))
        out.append(d)
        if 0 <= p < i:
            out[p]["children"].append(i)
    return out


def main():
    golden = {}
    files = sorted(glob.glob(os.path.join(TREES, "*.json")) + glob.glob(os.path.join(TREES, "emulator", "*.json")))
    for path in files:
        rel = os.path.relpath(path, TREES)
        if rel == "laya_golden.json":
            continue
        tree = json.load(open(path, encoding="utf8"))
        nodes = to_laya(tree)
        screen = (tree["screen"]["w"], tree["screen"]["h"])
        cands = C.build(nodes, screen)
        labels = [S.option_label(c) for c in cands]
        golden[rel] = {
            "ui": ["[%d] %s" % (i, lab) for i, lab in enumerate(labels)],
            "node_index": [c_node_index(c, nodes, screen) for c in cands],
            "bounds": [c["bounds"] for c in cands],
            "labels": [c["label"] for c in cands],
        }
        print("%-40s nodes=%4d candidates=%3d" % (rel, len(nodes), len(cands)))
    with open(os.path.join(TREES, "laya_golden.json"), "w", encoding="utf8") as f:
        json.dump(golden, f, ensure_ascii=False, indent=1, sort_keys=True)
        f.write("\n")


def c_node_index(c, nodes, screen):
    """Their candidates don't keep the node id; recover it: first actionable non-IME node at c's depth with c's box + label."""
    by_key = C.index(nodes)
    for n in nodes:
        if n["depth"] != c["depth"] or n["window_type"] == A.IME_WINDOW or not any(n.get(f) for f in C.ACTION_FLAGS):
            continue
        box = A.visible_box(n, screen)
        if box is not None and list(box) == c["bounds"] and A.label(n, by_key) == c["label"]:
            return n["id"]
    return -1


if __name__ == "__main__":
    main()
