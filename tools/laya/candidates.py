"""Laya-Android candidate extraction + serialization, ported to OUR UiTree JSON (core/UiTree.kt).

Faithful port of Byun11/laya-android `src/accessibility.py`, `src/candidates.py`, `src/serialization.py` and
`history_str` in `scripts/build_androidcontrol_dataset.py` (repo HEAD fetched 2026-11-17). The model was trained on
exactly this format; anything else is out of distribution. The Kotlin port (T16) must reproduce every rule below
byte-for-byte; `tools/laya/fixtures/*.json` carry the expected output for golden tests.

Rules (numbers refer to the code below):

 1. Node set: every node of every window, in UiTree order (pre-order per window, windows in the order the nodes
    appear). AndroidControl's forest has the same shape (all windows, incl. status bar / nav bar `system` windows),
    so we do NOT drop system windows and do NOT apply a "dialog on top" rule: Byun11's code has neither.
 2. Skip nodes whose window type is `input_method` (soft keyboard; AndroidControl window type 2).
 3. Skip nodes with none of the action flags clickable / long_clickable / editable / scrollable / checkable.
 4. Visible box: node must have visible=true; bounds clipped to [0,0,screen.w,screen.h]; dropped if the clipped box
    has zero or negative width/height.
 5. Label: own text = (text or desc or hint or "").strip() -- note the `or` chain: a whitespace-only text wins
    over desc and yields "" (faithful). If non-empty: collapse whitespace runs to one space, cut to 60 chars.
    Else: DFS over descendants (children in tree order, pre-order), collecting each descendant's own text
    (whitespace-collapsed), stopping before visiting another node once sum(len(part)+1) >= 60; the result is the
    parts joined with " " and cut to 60 chars. Descendants are not filtered by visibility or window.
 6. Dedup key = (clipped box, label). On a collision the deeper node (strictly greater depth; root depth 0) replaces
    the record (its class/res_id/enabled/checked/selected) with the action flags OR-ed; at equal or smaller depth the
    first record is kept and only the action flags are OR-ed in.
 7. Order: sort by (top, left, bottom, right, label, short class) ascending; ids 0..n-1 in that order.
 8. Short class = text after the last "." of className ("" if none). res_id short = after the last "/".
 9. Option label (serialization.option_label): label[:40] or "-" (when the label is empty), then "(<Class>)", then
    flags in this order, space separated: edit (editable), long (long_clickable and not clickable),
    scroll (scrollable and not clickable), on (checked), selected (selected), disabled (not enabled).
    Note `checkable` itself is never printed and `on` uses `checked` regardless of checkable.
10. State ui line: "[i] <option label>". Target question criteria: {"<i>": <option label>} (no "[i]" prefix);
    laya renders each option as "<i>: <option label>".
11. History strings (last 3, oldest first): "CLICK <label>" / "LONG_PRESS <label>" using the FULL 60-char label of the
    clicked candidate, or its short class when the label is empty, or "CLICK (unlabeled)" when the tap was not on
    a candidate; 'INPUT_TEXT "<text>"'; "OPEN_APP <app name>"; otherwise the bare op ("SCROLL_DOWN", "BACK", ...).

Our UiTree differs from AndroidControl's protobuf only in naming (desc/content_desc, res_id/resource_id,
long_clickable is the same) and in carrying a parent index instead of child ids + depth; depth and child order are
derived from the parent indices (children keep their order of appearance in `nodes`).
"""
import json
import sys

ACTION_FLAGS = ("clickable", "long_clickable", "editable", "scrollable", "checkable")
IME_WINDOW_TYPE = "input_method"
LABEL_LIMIT = 60
OPTION_LABEL_LIMIT = 40

# serialization.py, byte-for-byte
OP_DESC = {
    "CLICK": "tap a UI element",
    "LONG_PRESS": "long-press a UI element",
    "SCROLL_UP": "scroll up",
    "SCROLL_DOWN": "scroll down",
    "SCROLL_LEFT": "scroll left",
    "SCROLL_RIGHT": "scroll right",
    "OPEN_APP": "launch an app by name",
    "INPUT_TEXT": "type text into the focused field",
    "BACK": "press the system back button",
    "HOME": "go to the home screen",
    "WAIT": "wait for the screen to update",
    "DONE": "the goal is complete; stop",
}
Q_OP = "Which operation should be performed next?"
Q_TARGET = "Which UI element should be targeted?"


def _prepare(tree):
    """UiTree nodes -> list of dicts with depth / children / window_type (indices = position in tree['nodes'])."""
    wtype = {w["id"]: w.get("type", "unknown") for w in tree.get("windows", [])}
    nodes = tree["nodes"]
    out = []
    for i, n in enumerate(nodes):
        out.append({**n, "_idx": i, "children": [], "window_type": wtype.get(n.get("window"), "unknown")})
    for i, n in enumerate(out):
        p = n.get("parent", -1)
        if p is not None and 0 <= p < len(out) and p != i:
            out[p]["children"].append(i)
    for n in out:  # depth = number of ancestors (iterative; guards against cycles)
        d, p, seen = 0, n.get("parent", -1), set()
        while p is not None and 0 <= p < len(out) and p not in seen:
            seen.add(p)
            d += 1
            p = out[p].get("parent", -1)
        n["depth"] = d
    return out


def visible_box(n, screen):
    """Rule 4."""
    if not n.get("visible") or "bounds" not in n:
        return None
    w, h = screen
    x1, y1, x2, y2 = n["bounds"]
    x1, y1, x2, y2 = max(0, x1), max(0, y1), min(w, x2), min(h, y2)
    if x2 <= x1 or y2 <= y1:
        return None
    return (x1, y1, x2, y2)


def own_text(n):
    return (n.get("text") or n.get("desc") or n.get("hint") or "").strip()


def label(n, nodes, limit=LABEL_LIMIT):
    """Rule 5 (accessibility.label)."""
    t = own_text(n)
    if t:
        return " ".join(t.split())[:limit]
    parts, stack, seen = [], list(reversed(n["children"])), set()
    while stack and sum(len(p) + 1 for p in parts) < limit:
        cid = stack.pop()
        if cid in seen:
            continue
        seen.add(cid)
        child = nodes[cid]
        ct = own_text(child)
        if ct:
            parts.append(" ".join(ct.split()))
        stack.extend(reversed(child["children"]))
    return " ".join(parts)[:limit]


def build(tree):
    """Candidate dicts in model order (rules 1-8). Each has id, label, class, resource_id, enabled, checked,
    selected, bounds, depth, node_index and the five action flags."""
    screen = (tree["screen"]["w"], tree["screen"]["h"])
    nodes = _prepare(tree)
    groups = {}
    for n in nodes:
        if n["window_type"] == IME_WINDOW_TYPE or not any(n.get(f) for f in ACTION_FLAGS):
            continue
        box = visible_box(n, screen)
        if box is None:
            continue
        lab = label(n, nodes)
        key = (box, lab)
        c = groups.get(key)
        if c is None or n["depth"] > c["depth"]:
            merged = {f: bool(n.get(f, False) or (c[f] if c else False)) for f in ACTION_FLAGS}
            groups[key] = c = {
                "label": lab, "class": (n.get("class") or "").rsplit(".", 1)[-1],
                "resource_id": (n.get("res_id") or "").rsplit("/", 1)[-1],
                "enabled": bool(n.get("enabled", False)), "checked": bool(n.get("checked", False)),
                "selected": bool(n.get("selected", False)), "bounds": list(box), "depth": n["depth"],
                "node_index": n["_idx"], **merged,
            }
        else:
            for f in ACTION_FLAGS:
                c[f] = bool(c[f] or n.get(f, False))
    cands = sorted(groups.values(), key=lambda c: (c["bounds"][1], c["bounds"][0], c["bounds"][3],
                                                   c["bounds"][2], c["label"], c["class"]))
    for i, c in enumerate(cands):
        c["id"] = i
    return cands


def option_label(c):
    """Rule 9 (serialization.option_label)."""
    flags = [f for f, on in (("edit", c["editable"]), ("long", c["long_clickable"] and not c["clickable"]),
                             ("scroll", c["scrollable"] and not c["clickable"]), ("on", c["checked"]),
                             ("selected", c["selected"]), ("disabled", not c["enabled"])) if on]
    return " ".join([c["label"][:OPTION_LABEL_LIMIT] or "-", "(%s)" % c["class"], *flags])


def candidate_list(tree):
    """[(id, line_text, node_index, bounds)] -- the ordered list T16/T17 need."""
    return [(c["id"], "[%d] %s" % (c["id"], option_label(c)), c["node_index"], c["bounds"]) for c in build(tree)]


def history_str(op, cand=None, text=None, app=None):
    """Rule 11 (build_androidcontrol_dataset.history_str). `cand` is the candidate dict acted on, or None."""
    if op in ("CLICK", "LONG_PRESS"):
        return "%s %s" % (op, (cand["label"] or cand["class"]) if cand is not None else "(unlabeled)")
    if op == "INPUT_TEXT":
        return 'INPUT_TEXT "%s"' % (text or "")
    if op == "OPEN_APP":
        return "OPEN_APP %s" % (app or "")
    return op


def state_and_questions(tree, goal, history, extra_ops=None, text_options=None, text_instructions=None):
    """The exact Laya-Android request (serialization.row without `expected`), plus our optional additions.

    extra_ops: {OP_NAME: description} appended AFTER the 12 trained operations (untrained; see README).
    text_options: list of strings -> an extra `text` choice question (untrained; see README).
    Returns (state, questions, candidates).
    """
    cands = build(tree)
    labels = [option_label(c) for c in cands]
    state = {"goal": goal, "history": list(history)[-3:], "ui": ["[%d] %s" % (i, lab) for i, lab in enumerate(labels)]}
    crit = dict(OP_DESC)
    for k, v in (extra_ops or {}).items():
        crit[k] = v
    questions = {"operation": {"type": "choice", "instructions": Q_OP, "criteria": crit}}
    if cands:  # laya rejects a choice question with zero options; Byun11 only asks when groundable
        questions["target"] = {"type": "choice", "instructions": Q_TARGET,
                               "criteria": {str(i): lab for i, lab in enumerate(labels)}}
    if text_options:
        questions["text"] = {"type": "choice", "instructions": text_instructions or TEXT_INSTRUCTIONS,
                             "criteria": {str(i): t for i, t in enumerate(text_options)}}
    return state, questions, cands


# Our addition (not in Laya-Android's training data): which of the task's text options to type.
TEXT_INSTRUCTIONS = "Which text should be typed into the focused field?"

# Our addition: descriptions for app actions Laya-Android was never trained on (only used with --extra-ops).
EXTRA_OP_DESC = {
    "TYPE_ENTER": "type text into the focused field and press enter",
    "ENTER": "press the enter key",
    "OPEN_WEBSITE": "open a website by its address",
    "ALL_APPS": "open the list of all apps",
    "SWIPE_LEFT": "swipe left",
    "SWIPE_RIGHT": "swipe right",
}


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("usage: python tools/laya/candidates.py <ui_tree.json>")
    with open(sys.argv[1], encoding="utf8") as f:
        t = json.load(f)
    for cid, line, idx, b in candidate_list(t):
        print("%-60s node=%d bounds=%s" % (line, idx, b))
