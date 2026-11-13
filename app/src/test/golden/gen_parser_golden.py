"""Runs upstream's parser functions (extracted from jev_agent.py / phone.py) and writes golden outputs."""
import ast
import json
import re
import sys

UP = '/Users/daniel/projects/android_voice_control/upstream/System-One-Computer-Use/'
ns = {'re': re}
for path, names, consts in ((UP + 'jev_agent.py', ('quoted_texts', 'text_options', 'websites'), ('KNOWN_SITES',)),
                            (UP + 'phone.py', ('apps_in_task',), ('KNOWN_APPS',))):
    for node in ast.parse(open(path).read()).body:
        if (isinstance(node, ast.Assign) and any(getattr(t, 'id', None) in consts for t in node.targets)) or \
                (isinstance(node, ast.FunctionDef) and node.name in names):
            exec(compile(ast.Module([node], []), path, 'exec'), ns)

tasks = [
    'Open Settings and turn on Wi-Fi',
    'Go to x.com and post "Hello from my JEV agent!"',
    'Search YouTube for “cat videos” then open https://example.org/path?q=1.',
    'Set an alarm for 7.30 in the Clock app',
    'Type "unclosed quote here',
    'Open wikipedia and search for Aliens, then visit github.com/foo, and reddit',
    'Post on X: "Grüße aus Köln"',
    'Open Google Maps and find the next café',
    'Relax with Xbox then open Chrome',
    "Open Rock & Roll's top-10 list",
    'Send "hi" and "bye" to Bob at 12:05',
    'Open the Play Store and install Spotify',
    'Open GOOGLE.COM, then YOUTUBE.',
]
everything = set(ns['KNOWN_APPS'].values())
some = everything - {'com.google.android.googlequicksearchbox', 'com.spotify.music'}
out = []
for t in tasks:
    out.append({
        'task': t,
        'quoted': ns['quoted_texts'](t),
        'texts': ns['text_options'](t),
        'sites': ns['websites'](t),
        'apps_all': [list(a) for a in ns['apps_in_task'](t, everything)],
        'apps_some': [list(a) for a in ns['apps_in_task'](t, some)],
    })
json.dump({'some_missing': sorted(everything - some), 'cases': out}, open(sys.argv[1], 'w'), ensure_ascii=False, indent=1)
for c in out:
    print(c['task'], '|', c['quoted'], '|', c['sites'], '|', c['apps_all'], '|', len(c['texts']))
