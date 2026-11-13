"""Builds the exact request bodies upstream's ask_jev() / refine_target() send (phone path) with fake images."""
import ast
import base64
import io
import json
import re
import sys

UP = '/Users/daniel/projects/android_voice_control/upstream/System-One-Computer-Use/'
ns = {'re': re, 'io': io, 'base64': base64}
want_assign = {'MODEL', 'ACTIONS', 'PHONE_ACTIONS', 'KNOWN_SITES'}
want_fn = {'ask_jev', 'refine_target', 'quoted_texts', 'text_options', 'websites'}
for node in ast.parse(open(UP + 'jev_agent.py').read()).body:
    if (isinstance(node, ast.Assign) and any(getattr(t, 'id', None) in want_assign for t in node.targets)) or \
            (isinstance(node, ast.FunctionDef) and node.name in want_fn):
        exec(compile(ast.Module([node], []), 'jev_agent.py', 'exec'), ns)

sent = []
ns['post_jev'] = lambda key, body: sent.append(body) or {
    'answers': {'target': {'box': {'left': 0, 'top': 0, 'right': 1, 'bottom': 1}, 'confidence': 1}}}


class FakeImg:
    """Stands in for a PIL image: writes fixed bytes as the 'JPEG'."""
    size = (700, 1500)
    width, height = size

    def save(self, buf, fmt, quality):
        buf.write(b'SCREENSHOT')

    def crop(self, box):
        self.crop_box = box
        return self

    def resize(self, size, method):
        return self


ns['Image'] = type('Image', (), {'LANCZOS': 1})
task = 'Open Settings and turn on Wi-Fi, then search wikipedia and github.com for "Aliens"'
apps = [('settings', 'com.android.settings'), ('chrome', 'com.android.chrome')]
texts, urls = ns['text_options'](task), ns['websites'](task)
history = ['h%d' % i for i in range(12)]
ns['ask_jev']('KEY', task, FakeImg(), history, texts, urls, ns['PHONE_ACTIONS'], apps)
ns['ask_jev']('KEY', 'Open Settings', FakeImg(), [], [], [], ns['PHONE_ACTIONS'], [])
ns['refine_target']('KEY', task, FakeImg(), {'left': 0.4, 'top': 0.5, 'right': 0.6, 'bottom': 0.55}, [], True)
extra = (" A small floating bubble or status strip belonging to 'Voice Control' may be visible; "
         "it is the controller running you: never tap it.")
for body in sent[:2]:
    body['state']['note'] += extra
compact = lambda b: json.dumps(b, ensure_ascii=False, separators=(',', ':'))
json.dump({'task': task, 'apps': apps, 'history': history, 'compact': [compact(b) for b in sent],
           'ask_full': sent[0], 'ask_minimal': sent[1], 'refine': sent[2]},
          open(sys.argv[1], 'w'), ensure_ascii=False, indent=1)
print(json.dumps(sent[1], indent=1))
