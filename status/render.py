#!/usr/bin/env python3
"""Render status.html from status/tasks.json. Usage: python3 status/render.py"""
import json, html, datetime, pathlib
root = pathlib.Path(__file__).resolve().parent.parent
data = json.loads((root / "status/tasks.json").read_text())
COLORS = {"todo": "#9aa0a6", "running": "#1a73e8", "blocked": "#e37400", "done": "#188038", "failed": "#d93025", "review": "#8430ce"}
rows = []
for t in data["tasks"]:
    c = COLORS.get(t["status"], "#9aa0a6")
    log = "".join(f"<li>{html.escape(l)}</li>" for l in t.get("log", []))
    rows.append(f"""<tr>
  <td><b>{html.escape(t['id'])}</b></td>
  <td>{html.escape(t['title'])}<div class=owner>{html.escape(t.get('owner',''))}</div></td>
  <td><span class=badge style="background:{c}">{t['status'].upper()}</span></td>
  <td>{html.escape(t.get('summary',''))}{'<ul class=log>'+log+'</ul>' if log else ''}</td>
</tr>""")
notes = "".join(f"<li>{html.escape(n)}</li>" for n in data.get("notes", []))
blocked = "".join(f"<li>{html.escape(n)}</li>" for n in data.get("needs_input", []))
page = f"""<!doctype html><html><head><meta charset=utf-8><meta http-equiv=refresh content=30>
<title>Android Voice Control — build status</title>
<style>
body{{font:15px/1.45 -apple-system,Segoe UI,Roboto,sans-serif;margin:32px auto;max-width:1100px;color:#202124;padding:0 16px}}
h1{{margin:0 0 4px}} .sub{{color:#5f6368;margin-bottom:24px}}
table{{border-collapse:collapse;width:100%}} th,td{{text-align:left;vertical-align:top;padding:10px 12px;border-bottom:1px solid #e0e0e0}}
th{{background:#f8f9fa;font-weight:600}} .badge{{color:#fff;border-radius:12px;padding:2px 10px;font-size:12px;font-weight:600;white-space:nowrap}}
.owner{{color:#5f6368;font-size:12px}} ul.log{{margin:6px 0 0;padding-left:18px;color:#5f6368;font-size:13px}}
.box{{background:#fff8e1;border:1px solid #ffe082;border-radius:8px;padding:12px 16px;margin:16px 0}}
.ok{{background:#e6f4ea;border-color:#a8dab5}}
</style></head><body>
<h1>Android Voice Control — build status</h1>
<div class=sub>Overall: <b>{html.escape(data['overall'])}</b> · updated {datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')} · page auto-refreshes every 30 s</div>
<p>{html.escape(data.get('goal',''))}</p>
{'<div class=box><b>Needs your input</b><ul>'+blocked+'</ul></div>' if blocked else ''}
<table><tr><th>#</th><th>Task</th><th>Status</th><th>What is happening</th></tr>{''.join(rows)}</table>
{'<h3>Notes</h3><ul>'+notes+'</ul>' if notes else ''}
</body></html>"""
(root / "status.html").write_text(page)
print("rendered status.html")
