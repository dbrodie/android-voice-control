"""Write hand-built, realistic UiTree JSONs (core/UiTree.kt schema) to tools/laya/trees/.

  tools/laya/.venv/bin/python -I tools/laya/make_trees.py

They imitate what PhoneAccessibilityService will dump on a 1080x2400 phone (Android 16, gesture nav): a `system`
status-bar window, the app window(s), and for one screen an `input_method` window. They deliberately include the
edge cases the candidate rules care about: parent/child duplicates (dedup), empty labels, whitespace text, hint-only
fields, disabled and checked nodes, nodes partly or fully off screen, invisible nodes, keyboard keys (must be
dropped), long labels (> 40 / > 60 chars), non-ASCII text, a literal "<mask>" in text, and > 40 candidates.
"""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "trees")
W, H = 1080, 2400
FLAGS = ("clickable", "long_clickable", "editable", "scrollable", "checkable", "checked", "selected", "focused",
         "password")


def N(cls, b, text="", desc="", hint="", res="", kids=(), enabled=True, visible=True, **flags):
    if "." not in cls:
        cls = ("android.widget." if cls not in ("View", "ViewGroup") else "android.view.") + cls
    n = {"class": cls, "text": text, "desc": desc, "hint": hint, "res_id": res, "bounds": list(b),
         "enabled": enabled, "visible": visible, "kids": list(kids)}
    for f in FLAGS:
        n[f] = bool(flags.pop(f, False))
    assert not flags, flags
    return n


def tree(package, activity, windows, keyboard=False):
    """windows: [(type, bounds, root, layer, active, focused)] -> UiTree dict (pre-order nodes, parent index)."""
    nodes, wins = [], []
    for wid, (wtype, wb, root, layer, active, focused) in enumerate(windows, start=1):
        wins.append({"id": wid, "type": wtype, "layer": layer, "active": active, "focused": focused, "bounds": list(wb)})

        def walk(n, parent):
            idx = len(nodes)
            rec = {"id": idx, "parent": parent, "window": wid, "class": n["class"], "text": n["text"],
                   "desc": n["desc"], "hint": n["hint"], "res_id": n["res_id"], "bounds": n["bounds"]}
            for f in ("clickable", "long_clickable", "editable", "scrollable", "checkable", "checked", "selected"):
                rec[f] = n[f]
            rec.update({"enabled": n["enabled"], "focused": n["focused"], "password": n["password"],
                        "visible": n["visible"]})
            nodes.append(rec)
            for k in n["kids"]:
                walk(k, idx)
        walk(root, -1)
    return {"package": package, "activity": activity, "screen": {"w": W, "h": H}, "keyboard_shown": keyboard,
            "taken_at": 1763380000000, "windows": wins, "nodes": nodes}


def status_bar(time="12:30"):
    return ("system", (0, 0, W, 118), N("FrameLayout", (0, 0, W, 118), kids=[
        N("TextView", (52, 30, 160, 92), text=time, res="com.android.systemui:id/clock"),
        N("ImageView", (880, 36, 920, 86), desc="Wifi signal full."),
        N("ImageView", (930, 36, 970, 86), desc="Phone signal full."),
        N("ImageView", (980, 36, 1040, 86), desc="Battery 83 percent."),
    ]), 2, False, False)


def app(root, layer=0):
    return ("application", (0, 0, W, H), root, layer, True, True)


def row(y, title, summary=None, icon=True, h=190, res="android:id/title", **kw):
    kids = []
    if icon:
        kids.append(N("ImageView", (44, y + 60, 116, y + 132), res="android:id/icon"))
    tv = [N("TextView", (176, y + 40, 1000, y + 100), text=title, res=res)]
    if summary:
        tv.append(N("TextView", (176, y + 100, 1000, y + 150), text=summary, res="android:id/summary"))
    kids.append(N("RelativeLayout", (176, y + 40, 1036, y + 150), kids=tv))
    return N("LinearLayout", (0, y, W, y + h), clickable=True, kids=kids, **kw)


def settings_main():
    items = [("Network & internet", "Mobile, Wi‑Fi, hotspot"), ("Connected devices", "Bluetooth, pairing"),
             ("Apps", "Assistant, recent apps, default apps"), ("Notifications", "Notification history, conversations"),
             ("Battery", "83%"), ("Storage", "41% used - 75.52 GB free"), ("Sound & vibration", "Volume, haptics, Do Not Disturb"),
             ("Display & touch", "Dark theme, font size, touch"), ("Wallpaper & style", "Colors, themed icons, app grid"),
             ("Accessibility", "Display, interaction, audio"), ("Security & privacy", "App security, device lock, permissions"),
             ("Location", "On - 9 apps have access to location")]
    rows = [row(560 + i * 190, t, s) for i, (t, s) in enumerate(items)]  # last rows run past the screen bottom
    root = N("FrameLayout", (0, 0, W, H), kids=[N("LinearLayout", (0, 118, W, H), kids=[
        N("TextView", (52, 240, 600, 360), text="Settings", res="com.android.settings:id/homepage_title"),
        N("LinearLayout", (44, 400, 1036, 540), clickable=True, res="com.android.settings:id/search_action_bar", kids=[
            N("ImageView", (80, 436, 148, 504)),
            N("TextView", (176, 430, 900, 510), text="Search Settings", res="com.android.settings:id/search_bar_title"),
        ]),
        N("ImageView", (930, 410, 1030, 530), desc="Profile picture, double tap to open Google Account", clickable=True),
        N("androidx.recyclerview.widget.RecyclerView", (0, 540, W, H), scrollable=True,
          res="com.android.settings:id/recycler_view", kids=rows),
    ])])
    return tree("com.android.settings", "com.android.settings.Settings", [status_bar(), app(root)])


def settings_bluetooth(on=False):
    sw = N("Switch", (900, 420, 1030, 500), checkable=True, checked=on, clickable=False, res="android:id/switch_widget")
    rows = [
        N("LinearLayout", (0, 380, W, 540), clickable=True, kids=[
            N("TextView", (52, 420, 700, 500), text="Use Bluetooth", res="android:id/title"), sw]),
        row(560, "Pair new device", icon=True),
        row(750, "Device name", "Pixel 9", icon=False),
        row(940, "Saved devices", "See all", icon=False, enabled=on),
        N("TextView", (52, 1160, 1036, 1320), res="android:id/title",
          text="When Bluetooth is turned on, your device can communicate with other nearby Bluetooth devices."),
    ]
    root = N("FrameLayout", (0, 0, W, H), kids=[N("LinearLayout", (0, 118, W, H), kids=[
        N("ImageButton", (20, 150, 146, 276), desc="Navigate up", clickable=True),
        N("TextView", (52, 290, 900, 370), text="Bluetooth", res="com.android.settings:id/collapsing_toolbar"),
        N("androidx.recyclerview.widget.RecyclerView", (0, 380, W, H), scrollable=True, kids=rows),
    ])])
    return tree("com.android.settings", "com.android.settings.SubSettings", [status_bar(), app(root)])


def chrome_ntp():
    tiles = []
    for i, (name) in enumerate(["YouTube", "Wikipedia", "Gmail", "Amazon.com", "Facebook", "Weather", "Reddit", "Maps"]):
        x, y = 40 + (i % 4) * 250, 1180 + (i // 4) * 260
        tiles.append(N("FrameLayout", (x, y, x + 250, y + 260), clickable=True, long_clickable=True, kids=[
            N("ImageView", (x + 80, y + 30, x + 170, y + 120), res="com.android.chrome:id/tile_view_icon"),
            N("TextView", (x + 10, y + 150, x + 240, y + 200), text=name, res="com.android.chrome:id/tile_view_title")]))
    feed = [N("LinearLayout", (40, 1760 + i * 420, 1040, 2140 + i * 420), clickable=True, kids=[
        N("TextView", (60, 1780 + i * 420, 1020, 1900 + i * 420), text=t),
        N("TextView", (60, 1910 + i * 420, 600, 1960 + i * 420), text=s)])
        for i, (t, s) in enumerate([("Northern lights may be visible tonight across much of Europe, forecasters say",
                                     "The Guardian · 3h"), ("The 12 best budget phones of the year, tested", "The Verge · 1d")])]
    root = N("FrameLayout", (0, 0, W, H), kids=[N("LinearLayout", (0, 118, W, H), kids=[
        N("FrameLayout", (0, 118, W, 280), res="com.android.chrome:id/toolbar", kids=[
            N("ImageButton", (10, 140, 136, 266), desc="Open the home page", clickable=True,
              res="com.android.chrome:id/home_button"),
            N("EditText", (150, 150, 780, 256), hint="Search or type web address", editable=True, clickable=True,
              long_clickable=True, res="com.android.chrome:id/url_bar"),
            N("ImageButton", (800, 140, 926, 266), desc="Switch or close tabs", clickable=True, long_clickable=True,
              res="com.android.chrome:id/tab_switcher_button"),
            N("ImageButton", (940, 140, 1066, 266), desc="Customize and control Google Chrome", clickable=True,
              res="com.android.chrome:id/menu_button"),
        ]),
        N("androidx.recyclerview.widget.RecyclerView", (0, 280, W, H), scrollable=True,
          res="com.android.chrome:id/recycler_view", kids=[
              N("ImageView", (290, 420, 790, 600), desc="Google"),
              N("LinearLayout", (40, 680, 1040, 840), clickable=True, res="com.android.chrome:id/search_box", kids=[
                  N("EditText", (120, 700, 840, 820), hint="Search or type URL", clickable=True,
                    res="com.android.chrome:id/search_box_text"),
                  N("ImageView", (860, 715, 950, 805), desc="Start voice search", clickable=True),
                  N("ImageView", (960, 715, 1030, 805), desc="Search with your camera using Google Lens", clickable=True)]),
              N("TextView", (40, 1100, 600, 1160), text="Most visited"),
              *tiles,
              N("TextView", (40, 1700, 600, 1750), text="Discover", clickable=True),
              *feed,
          ]),
    ])])
    return tree("com.android.chrome", "org.chromium.chrome.browser.ChromeTabbedActivity", [status_bar(), app(root)])


def chrome_article():
    """A Wikipedia-like page in Chrome: WebView nodes with many links (> 40 candidates, long state)."""
    words = ["Aurora", "Solar wind", "Magnetosphere", "Ionosphere", "Oxygen", "Nitrogen", "Kp index", "Geomagnetic storm",
             "Coronal mass ejection", "Sunspot", "Solar cycle 25", "Arctic Circle", "Tromsø", "Fairbanks, Alaska", "Iceland",
             "Finnish Lapland", "Auroral oval", "Substorm", "Plasma", "Van Allen radiation belt", "Photon", "Emission spectrum",
             "Green light (557.7 nm)", "Red aurora", "Aurora australis", "Southern Hemisphere", "Tasmania", "New Zealand",
             "Antarctica", "Space weather", "NOAA", "Carrington Event", "Telegraph", "Power grid", "GPS", "Satellite",
             "Astronomy", "Meteorology", "Folklore", "Sámi people", "Inuit", "Norse mythology", "Galileo Galilei",
             "Pierre Gassendi", "Kristian Birkeland", "Earth's magnetic field", "Polar night", "Light pollution",
             "Long-exposure photography", "Camera settings"]
    links, y = [], 760
    for i, w in enumerate(words):
        col = i % 3
        x = 40 + col * 340
        links.append(N("View", (x, y, x + 320, y + 64), text=w, clickable=True))
        if col == 2:
            y += 80
            if i % 9 == 8:
                links.append(N("TextView", (40, y, 1040, y + 120), text="The aurora is a natural light display in "
                               "Earth's sky, predominantly seen in high-latitude regions around the Arctic and Antarctic."))
                y += 140
    web = N("android.webkit.WebView", (0, 280, W, H), text="Aurora - Wikipedia", scrollable=True, kids=[
        N("View", (0, 280, W, 420), kids=[
            N("Button", (20, 300, 140, 400), desc="Open main menu", clickable=True),
            N("View", (160, 300, 600, 400), text="Wikipedia The Free Encyclopedia", clickable=True),
            N("Button", (900, 300, 1060, 400), text="Search", clickable=True)]),
        N("TextView", (40, 440, 1040, 560), text="Aurora"),
        N("View", (40, 580, 1040, 700), text="From Wikipedia, the free encyclopedia"),
        *links,
    ])
    root = N("FrameLayout", (0, 0, W, H), kids=[N("LinearLayout", (0, 118, W, H), kids=[
        N("FrameLayout", (0, 118, W, 280), kids=[
            N("ImageButton", (10, 140, 136, 266), desc="Open the home page", clickable=True),
            N("EditText", (150, 150, 780, 256), text="en.m.wikipedia.org/wiki/Aurora", editable=True, clickable=True,
              res="com.android.chrome:id/url_bar"),
            N("ImageButton", (800, 140, 926, 266), desc="Switch or close tabs", clickable=True),
            N("ImageButton", (940, 140, 1066, 266), desc="Customize and control Google Chrome", clickable=True)]),
        web])])
    return tree("com.android.chrome", "org.chromium.chrome.browser.ChromeTabbedActivity", [status_bar(), app(root)])


def clock_alarms():
    alarms = [("6:30 AM", "Mon, Tue, Wed, Thu, Fri", True), ("7:15 AM", "Tomorrow", False),
              ("9:00 AM", "Sat, Sun · Gym", False), ("10:45 PM", "Not scheduled", False)]
    cards = []
    for i, (t, d, on) in enumerate(alarms):
        y = 400 + i * 330
        cards.append(N("FrameLayout", (30, y, 1050, y + 300), clickable=True, kids=[
            N("TextView", (70, y + 30, 600, y + 160), text=t, clickable=True, res="com.google.android.deskclock:id/digital_clock"),
            N("TextView", (70, y + 170, 700, y + 230), text=d),
            N("Switch", (880, y + 60, 1010, y + 140), checkable=True, checked=on, clickable=True,
              desc="%s alarm" % t, res="com.google.android.deskclock:id/onoff"),
            N("ImageButton", (900, y + 200, 1000, y + 280), desc="Expand alarm", clickable=True),
        ]))
    tabs = []
    for i, name in enumerate(["Alarm", "Clock", "Timer", "Stopwatch", "Bedtime"]):
        x = i * 216
        tabs.append(N("FrameLayout", (x, 2180, x + 216, 2337), clickable=True, selected=(i == 0), desc=name, kids=[
            N("ImageView", (x + 70, 2200, x + 146, 2260)), N("TextView", (x + 20, 2270, x + 196, 2320), text=name)]))
    root = N("FrameLayout", (0, 0, W, H), kids=[N("LinearLayout", (0, 118, W, H), kids=[
        N("TextView", (52, 200, 600, 320), text="Alarm"),
        N("ImageButton", (940, 190, 1060, 310), desc="More options", clickable=True),
        N("androidx.recyclerview.widget.RecyclerView", (0, 360, W, 2180), scrollable=True, kids=cards),
        N("ImageButton", (430, 1960, 650, 2160), desc="Add alarm", clickable=True, res="com.google.android.deskclock:id/fab"),
        N("android.widget.HorizontalScrollView", (0, 2180, W, 2337), kids=[N("LinearLayout", (0, 2180, W, 2337), kids=tabs)]),
    ])])
    return tree("com.google.android.deskclock", "com.android.deskclock.DeskClock", [status_bar("6:02"), app(root)])


def messages_keyboard():
    """Messages conversation, compose field focused, soft keyboard up (input_method window must be ignored)."""
    msgs = [("Hey! Are we still on for dinner tonight?", 60), ("Yes, 19:30 at the café on Rothschild 👍", 300),
            ("Great. Can you send me the address?", 60), ("Sure, one sec", 300),
            ("Also: אל תשכח את הכרטיסים 🎟️ <mask> ok?", 60)]
    bubbles = [N("LinearLayout", (x, 600 + i * 150, x + 700, 730 + i * 150), clickable=True, long_clickable=True, kids=[
        N("TextView", (x + 20, 620 + i * 150, x + 680, 710 + i * 150), text=t)]) for i, (t, x) in enumerate(msgs)]
    app_root = N("FrameLayout", (0, 0, W, H), kids=[N("LinearLayout", (0, 118, W, H), kids=[
        N("ImageButton", (10, 150, 136, 276), desc="Back", clickable=True),
        N("TextView", (160, 160, 700, 260), text="Dana Levi", clickable=True),
        N("ImageButton", (760, 150, 886, 276), desc="Voice call", clickable=True),
        N("ImageButton", (900, 150, 1026, 276), desc="More options", clickable=True),
        N("androidx.recyclerview.widget.RecyclerView", (0, 300, W, 1360), scrollable=True, kids=bubbles),
        N("LinearLayout", (0, 1360, W, 1480), kids=[
            N("ImageButton", (10, 1370, 110, 1470), desc="Show attach media options", clickable=True),
            N("EditText", (120, 1370, 900, 1470), hint="Text message", editable=True, clickable=True, focused=True,
              long_clickable=True, res="com.google.android.apps.messaging:id/compose_message_text"),
            N("ImageButton", (920, 1370, 1060, 1470), desc="Send SMS", clickable=True, enabled=False)]),
    ])])
    keys, kb_top = [], 1500
    for r_i, rowk in enumerate(["qwertyuiop", "asdfghjkl", "zxcvbnm"]):
        for k_i, ch in enumerate(rowk):
            x = 10 + k_i * 106 + r_i * 50
            keys.append(N("Button", (x, kb_top + 120 + r_i * 170, x + 100, kb_top + 280 + r_i * 170), text=ch, clickable=True))
    keys.append(N("Button", (300, 2150, 780, 2300), desc="Space", clickable=True))
    keys.append(N("Button", (900, 2150, 1070, 2300), desc="Enter", clickable=True))
    kb = N("FrameLayout", (0, kb_top, W, H), kids=[
        N("LinearLayout", (0, kb_top, W, kb_top + 110), kids=[
            N("TextView", (40, kb_top + 10, 340, kb_top + 100), text="Sure", clickable=True),
            N("TextView", (390, kb_top + 10, 690, kb_top + 100), text="Thanks", clickable=True)]),
        *keys])
    return tree("com.google.android.apps.messaging", "com.google.android.apps.messaging.ui.conversation.ConversationActivity",
                [status_bar(), app(app_root), ("input_method", (0, kb_top, W, H), kb, 1, False, False)], keyboard=True)


def home_screen():
    icons = []
    names = [("Gmail", 0, 0), ("Photos", 1, 0), ("YouTube", 2, 0), ("Calendar", 3, 0), ("Weather", 0, 1), ("Clock", 1, 1),
             ("Maps", 2, 1), ("Settings", 3, 1)]
    for name, cx, cy in names:
        x, y = 40 + cx * 250, 1300 + cy * 300
        icons.append(N("TextView", (x, y, x + 250, y + 280), text=name, desc=name, clickable=True, long_clickable=True))
    hotseat = []
    for i, name in enumerate(["Phone", "Messages", "Chrome", "Camera"]):
        x = 40 + i * 250
        hotseat.append(N("TextView", (x, 2020, x + 250, 2240), desc=name, clickable=True, long_clickable=True))
    root = N("FrameLayout", (0, 0, W, H), kids=[N("com.android.launcher3.DragLayer", (0, 0, W, H), kids=[
        N("com.android.launcher3.Workspace", (0, 118, W, 1960), scrollable=True, desc="Home", kids=[
            N("android.appwidget.AppWidgetHostView", (40, 220, 1040, 560), clickable=True, kids=[
                N("TextView", (80, 260, 700, 360), text="Mon, Nov 17"), N("TextView", (80, 380, 700, 480), text="14°C Cloudy")]),
            N("com.android.launcher3.CellLayout", (0, 1200, W, 1900), kids=icons)]),
        N("View", (40, 1910, 1040, 1950), desc="Page 1 of 2"),
        N("com.android.launcher3.Hotseat", (0, 2000, W, 2260), kids=hotseat),
        N("FrameLayout", (40, 2270, 1040, 2390), clickable=True, desc="Google search", kids=[
            N("ImageView", (60, 2290, 140, 2370), desc="Google app", clickable=True),
            N("ImageView", (860, 2290, 940, 2370), desc="Voice search", clickable=True),
            N("ImageView", (950, 2290, 1030, 2370), desc="Google Lens", clickable=True)]),
    ])])
    return tree("com.google.android.apps.nexuslauncher", "com.google.android.apps.nexuslauncher.NexusLauncherActivity",
                [status_bar(), app(root)])


def app_drawer():
    """All apps grid: 5 x 10 icons (> 40 candidates) + search; bottom row partly off screen."""
    names = ["Assistant", "Calculator", "Calendar", "Camera", "Chrome", "Clock", "Contacts", "Drive", "Duo", "Files",
             "Fit", "Gemini", "Gmail", "Google", "Google One", "Google Pay", "Google TV", "Home", "Keep Notes", "Maps",
             "Meet", "Messages", "News", "Phone", "Photos", "Play Books", "Play Games", "Play Store", "Podcasts",
             "Recorder", "Safety", "Settings", "Sheets", "Slides", "Spotify", "Telegram", "Translate", "Uber",
             "Wallet", "Waze", "Weather", "WhatsApp", "Wikipedia", "YouTube", "YouTube Music", "Zoom", "Docs",
             "Authenticator", "Lens", "Tasks"]
    icons = []
    for i, n in enumerate(names):
        x, y = 20 + (i % 5) * 208, 420 + (i // 5) * 205
        icons.append(N("TextView", (x, y, x + 208, y + 200), text=n, desc=n, clickable=True, long_clickable=True))
    root = N("FrameLayout", (0, 0, W, H), kids=[N("com.android.launcher3.allapps.AllAppsContainerView", (0, 118, W, H), kids=[
        N("com.android.launcher3.allapps.search.AppsSearchContainerLayout", (40, 160, 1040, 300), kids=[
            N("EditText", (100, 170, 900, 290), hint="Search your phone and more", editable=True, clickable=True),
            N("ImageView", (920, 190, 1010, 280), desc="Google Lens", clickable=True)]),
        N("com.android.launcher3.allapps.AllAppsRecyclerView", (0, 400, W, H), scrollable=True, kids=icons)])])
    return tree("com.google.android.apps.nexuslauncher", "com.google.android.apps.nexuslauncher.NexusLauncherActivity",
                [status_bar(), app(root)])


def dialog_over_content():
    """Settings > Display with the 'Screen timeout' dialog on top (separate application window, higher layer)."""
    under = N("FrameLayout", (0, 0, W, H), kids=[N("LinearLayout", (0, 118, W, H), kids=[
        N("ImageButton", (20, 150, 146, 276), desc="Navigate up", clickable=True),
        N("TextView", (52, 290, 900, 370), text="Display & touch"),
        N("androidx.recyclerview.widget.RecyclerView", (0, 380, W, H), scrollable=True, kids=[
            row(400, "Brightness level", "62%", icon=False),
            N("LinearLayout", (0, 590, W, 780), clickable=True, kids=[
                N("TextView", (52, 620, 800, 690), text="Adaptive brightness"),
                N("TextView", (52, 690, 800, 750), text="Optimizes brightness for surroundings"),
                N("Switch", (900, 640, 1030, 720), checkable=True, checked=True)]),
            row(790, "Dark theme", "Will never turn on automatically", icon=False),
            row(980, "Screen timeout", "After 30 seconds of inactivity", icon=False),
            row(1170, "Font size and text", "Default", icon=False),
            row(1360, "   ", None, icon=False),  # whitespace-only title: label falls back to... nothing ("" -> "-")
        ])])])
    opts = ["15 seconds", "30 seconds", "1 minute", "2 minutes", "5 minutes", "10 minutes", "30 minutes"]
    radios = [N("RadioButton", (100, 820 + i * 120, 980, 930 + i * 120), text=o, clickable=True, checkable=True,
                checked=(o == "30 seconds")) for i, o in enumerate(opts)]
    dlg = N("FrameLayout", (60, 640, 1020, 1880), kids=[N("LinearLayout", (60, 640, 1020, 1880), kids=[
        N("TextView", (100, 680, 980, 780), text="Screen timeout", res="android:id/alertTitle"),
        N("ListView", (60, 800, 1020, 1680), scrollable=True, kids=radios),
        N("Button", (700, 1720, 980, 1840), text="Cancel", clickable=True, res="android:id/button2"),
        # a duplicate clickable wrapper with the same box + label as its child (dedup keeps the deeper node)
        N("FrameLayout", (380, 1720, 680, 1840), clickable=True, kids=[
            N("Button", (380, 1720, 680, 1840), text="Use default", clickable=True, enabled=False)]),
    ])])
    return tree("com.android.settings", "com.android.settings.SubSettings",
                [status_bar(), ("application", (60, 640, 1020, 1880), dlg, 1, True, True),
                 ("application", (0, 0, W, H), under, 0, False, False)])


def empty_screen():
    """A game / canvas screen: nothing actionable except an invisible view (0 candidates)."""
    root = N("FrameLayout", (0, 0, W, H), kids=[N("android.view.SurfaceView", (0, 0, W, H)),
                                                N("Button", (400, 1000, 680, 1100), text="Play", clickable=True,
                                                  visible=False)])
    return tree("com.example.game", "com.unity3d.player.UnityPlayerActivity", [status_bar(), app(root)])


TREES = {
    "settings_main": settings_main, "settings_bluetooth": settings_bluetooth, "chrome_ntp": chrome_ntp,
    "chrome_article": chrome_article, "clock_alarms": clock_alarms, "messages_keyboard": messages_keyboard,
    "home_screen": home_screen, "app_drawer": app_drawer, "dialog_over_content": dialog_over_content,
    "empty_screen": empty_screen,
}

if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for name, fn in TREES.items():
        t = fn()
        with open(os.path.join(OUT, name + ".json"), "w", encoding="utf8") as f:
            json.dump(t, f, ensure_ascii=False, indent=None, separators=(",", ":"))
            f.write("\n")
        print(name, len(t["nodes"]), "nodes")
