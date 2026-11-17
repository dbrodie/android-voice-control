"""Self-tests for candidates.py (Byun11's own __main__ self-check, translated to our UiTree schema, plus edge cases).

  tools/laya/.venv/bin/python -I tools/laya/test_candidates.py
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import candidates as C  # noqa: E402


def node(i, parent, window=1, cls="android.view.View", text="", desc="", hint="", bounds=(0, 0, 10, 10), **flags):
    n = {"id": i, "parent": parent, "window": window, "class": cls, "text": text, "desc": desc, "hint": hint,
         "res_id": "", "bounds": list(bounds), "enabled": flags.pop("enabled", True), "visible": flags.pop("visible", True)}
    for f in ("clickable", "long_clickable", "editable", "scrollable", "checkable", "checked", "selected", "focused",
              "password"):
        n[f] = flags.pop(f, False)
    assert not flags
    return n


def tree(nodes, w=100, h=100, windows=None):
    return {"package": "p", "activity": "a", "screen": {"w": w, "h": h}, "keyboard_shown": False, "taken_at": 0,
            "windows": windows or [{"id": 1, "type": "application", "layer": 0, "active": True, "focused": True,
                                    "bounds": [0, 0, w, h]},
                                   {"id": 2, "type": "input_method", "layer": 1, "active": False, "focused": False,
                                    "bounds": [0, 0, w, h]}],
            "nodes": nodes}


def test_byun11_selfcheck():
    # Byun11 src/candidates.py __main__: dedup collapses parent/child with same box+label; IME node dropped
    t = tree([
        node(0, -1, clickable=True, bounds=(0, 0, 100, 100)),
        node(1, 0, clickable=True, bounds=(0, 0, 100, 100)),
        node(2, 1, text="OK", bounds=(10, 10, 30, 30)),
        node(3, -1, clickable=True, desc="Menu", bounds=(40, 40, 60, 60)),  # its own root (not under node 0)
        node(4, -1, window=2, clickable=True, text="q", bounds=(0, 90, 10, 100)),
    ])
    cs = C.build(t)
    assert [c["label"] for c in cs] == ["OK", "Menu"], cs
    assert cs[0]["depth"] == 1 and cs[0]["node_index"] == 1


def test_equal_depth_keeps_first_and_ors_flags():
    t = tree([node(0, -1, bounds=(0, 0, 100, 100)),
              node(1, 0, clickable=True, text="A", bounds=(0, 0, 50, 50), cls="x.First"),
              node(2, 0, scrollable=True, text="A", bounds=(0, 0, 50, 50), cls="x.Second", enabled=False)])
    (c,) = C.build(t)
    assert c["class"] == "First" and c["clickable"] and c["scrollable"] and c["enabled"]
    assert C.option_label(c) == "A (First)"  # scroll hidden because clickable


def test_whitespace_text_blocks_desc_and_children():
    # (text or desc or hint).strip(): "   " is truthy so desc is never consulted; label then comes from children
    t = tree([node(0, -1, clickable=True, text="   ", desc="Desc", bounds=(0, 0, 50, 50)),
              node(1, 0, text="Child", bounds=(0, 0, 20, 20))])
    (c,) = C.build(t)
    assert c["label"] == "Child", c


def test_limits_flags_and_clipping():
    long = "x" * 70
    t = tree([node(0, -1, bounds=(-20, 0, 120, 100)),
              node(1, 0, clickable=True, long_clickable=True, text=long, bounds=(-20, 5, 120, 30), checked=True,
                   selected=True, enabled=False, cls="android.widget.CheckBox"),
              node(2, 0, long_clickable=True, scrollable=True, editable=True, bounds=(0, 40, 10, 50), cls="EditText"),
              node(3, 0, clickable=True, text="gone", bounds=(0, 200, 10, 300)),
              node(4, 0, clickable=True, text="hidden", bounds=(0, 60, 10, 70), visible=False)])
    a, b = C.build(t)
    assert a["bounds"] == [0, 5, 100, 30] and len(a["label"]) == 60
    assert C.option_label(a) == "x" * 40 + " (CheckBox) on selected disabled"
    assert C.option_label(b) == "- (EditText) edit long scroll"


def test_descendant_label_budget():
    kids = [node(i + 1, 0, text="word%02d" % i, bounds=(0, 0, 5, 5)) for i in range(20)]
    t = tree([node(0, -1, clickable=True, bounds=(0, 0, 50, 50))] + kids)
    (c,) = C.build(t)
    # parts are added while sum(len(p)+1) < 60: 7-char words -> 9 parts (63 >= 60 stops), then cut to 60
    assert c["label"] == " ".join("word%02d" % i for i in range(9))[:60], c["label"]


def test_history_strings():
    c = {"label": "", "class": "ImageButton"}
    assert C.history_str("CLICK", c) == "CLICK ImageButton"
    assert C.history_str("CLICK", None) == "CLICK (unlabeled)"
    assert C.history_str("INPUT_TEXT", text="hi") == 'INPUT_TEXT "hi"'
    assert C.history_str("OPEN_APP", app="Chrome") == "OPEN_APP Chrome"
    assert C.history_str("SCROLL_DOWN") == "SCROLL_DOWN"


if __name__ == "__main__":
    for name, fn in sorted(globals().items()):
        if name.startswith("test_"):
            fn()
            print("ok", name)
