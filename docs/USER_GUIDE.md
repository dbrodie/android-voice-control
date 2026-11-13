# Android Voice Control: user guide

You tap a floating mic bubble and say what you want, for example "open settings and turn on Bluetooth".
GPT-Live (OpenAI's voice model) listens and hands the request to the on-phone agent. The agent takes a
screenshot, asks JEV (TypeSafe System One) what to do next, then taps, types or swipes through Android's
accessibility service. It repeats this until the task is done, and then GPT tells you the result.

The debug APK is `dist/voice-control-debug.apk`. It needs Android 11 (API 30) or newer and was tested on
Android 16.

## 1. Install

```bash
adb install -r dist/voice-control-debug.apk
```
You can also copy the APK to the phone and open it there. The phone will ask you to allow installs from that
source. The app is called **Android Voice Control**.

You need your own **JEV API key** (step 6 below). Release APKs contain no keys at all. Debug builds made
from this repo include the developer's key from `.env` unless they were built with `EMBED_KEYS=false`; you
can still replace it with your own under *JEV API key → Change*.

## 2. First-run setup

Open the app. The first time (and whenever voice has neither a ChatGPT sign-in nor an API key), the
**sign-in screen** opens first:

- **Sign in with ChatGPT** (needs ChatGPT Plus or Pro): OpenAI's login page opens in a browser tab inside the
  app. Log in there. The screen meanwhile says "Waiting for you to finish signing in in the browser…" with a
  5-minute countdown. If you closed the tab by accident, tap **Open browser again**; **Cancel** stops the
  sign-in. When you're done, the tab closes by itself and you see **You're signed in** with your email and plan.
  Tap **Continue**. (If the app doesn't come back on its own, tap **Back to Voice Control** on the page.)
- **Use an OpenAI API key instead**: paste a key (`sk-…`) and tap **Save key and use it**. Voice then uses the
  official API, billed per use to that key.
- **Not now** skips this; you can come back any time from the *Sign in with ChatGPT* row.

If something goes wrong, a red box on the sign-in screen says what happened (cancelled, couldn't be verified,
no connection to OpenAI, token exchange failed, sign-in port busy, timed out, your plan has no voice, …), with
**Try again** below it.

The **Setup** list shows a green ✓ for each step that is done. Do the steps from top to bottom:

1. **Display over other apps**: tap *Grant*, turn on *Android Voice Control*, then go back. This is what
   lets the bubble appear.
2. **Accessibility service**: tap *Open*. Go to *Installed apps* (on some phones *Downloaded apps*), choose
   *Android Voice Control*, turn it on and confirm. This is how the app sees the screen and taps for you.
   Android 13+ may first say the setting is "restricted" for side-loaded apps. If so, open the app's
   *App info* page, tap ⋮ → *Allow restricted settings*, and try again.
3. **Microphone**: tap *Allow*.
4. **Sign in with ChatGPT**: if you skipped the sign-in screen, tap *Sign in* here (same screen as above). Once
   signed in, the row shows your email and plan (for example "you@example.com · ChatGPT Pro"), and the button
   says *Manage*: it shows the account, **Switch account** (signs in again in the browser; to pick a different
   OpenAI account, log out on OpenAI's page first) and **Sign out**. Signing out forgets the sign-in on this
   phone; if the Voice choice below was *ChatGPT sign-in*, it goes back to *Automatic*. If you use an **OpenAI
   API key** instead, this row is optional; you can also change the key under *Settings → OpenAI API key*.
5. **Notifications**: tap *Allow*. You get the "bubble running" notification, which has a Stop button.
6. **JEV API key**: tap *Add*. (After you sign in, the app opens this screen once by itself.) JEV is
   TypeSafe AI's "System One" model: for every step it looks at a screenshot and decides what to tap.
   To get a key:
   1. Open [console.typesafe.ai/keys](https://console.typesafe.ai/keys) (the button on the screen opens
      it) and sign in or create an account.
   2. Create an API key and copy it.
   3. Back in the app, tap *Paste* (or long-press the field), then **Verify & save**. The app sends one tiny
      test request to TypeSafe and tells you whether the key works (✓), is invalid (✗, nothing saved), or
      couldn't be checked (no internet; you can *Save without verifying*).

   The key is stored encrypted on the phone and only sent to `api.typesafe.ai`. The screen shows only the
   first 12 characters. Use *Remove key* to delete it. More on what it costs under *Costs* below.
7. *(Optional)* **Ignore battery optimisation**: this stops aggressive phones from killing the bubble.

Under **Voice** you choose which backend to use:
- *Automatic*: ChatGPT sign-in if you are signed in, otherwise the API key.
- *ChatGPT sign-in*.
- *OpenAI API key*.

The line "In use now: …" shows the backend the app will actually use. It turns orange if neither backend
is set up.

Last, tap **Start overlay**. The blue mic bubble appears, and you can close the app.

## 3. Using the bubble

- **Tap** the bubble to start a conversation. The pill next to it shows *Connecting…*, then *Listening…*.
  It then shows what you said and what the assistant says back. A pulsing ring means the session is open.
- **Talk normally.** The assistant may ask a short question back. It then says something like "On it" and
  starts working on the phone.
- **While the agent works**, the bubble turns into a thin strip at the top of the screen
  ("Voice Control · Step 3 · CLICK at …"). Tap **■** on the strip to stop. You can also say "stop the
  current task".
- **When the task ends**, the pill shows the result (Done / Not sure / Stopped / Error · reason). GPT says a
  one-sentence summary.
- **Tap the bubble again** to end the conversation. **Drag** it to move it; it snaps to the nearest edge.
- Errors appear in the pill in red. For example: *Voice isn't set up*, *Sign in again*, or
  *Voice limit reached*.

You can try the agent without voice: in the app, type a task under **Test without voice** and tap **Run**.
The log under the box shows each step.

### Example voice commands
- "Open settings and turn on Bluetooth" (also works for Wi-Fi, dark theme, airplane mode)
- "Search Chrome for the weather in Berlin"
- "Open the Clock app and show me my alarms"
- "Open YouTube and search for lo-fi music"
- "Open Gmail"
- "Stop the current task" (while a task is running)

## 4. What the agent can and can't do

- It works only from what is on the screen. JEV looks at a screenshot and chooses one action at a time:
  tap, long-press, type, Enter, scroll, swipe, Back, Home, open an app, open a website, or "done". A
  normal step takes 2–5 seconds.
- **It only types text that is in the task.** It can't make up text on its own. GPT writes the task and
  puts the text to type in quotes, for example `Open Chrome and search for "weather in Berlin"`. So say
  exactly what to search for or write. A request like "reply something nice" won't work.
- When it types into a field it has just tapped, it **replaces** what is already there, for example an old
  search query. It does not add to it.
- It opens apps it knows by name directly: Settings, Chrome, Gmail, YouTube, Maps, Clock, Calculator,
  Contacts, Camera, Phone, Messages, Play Store, Photos, Calendar, and the popular social apps. For any
  other app it goes through the app drawer.
- It stops when JEV isn't confident enough, when the screen hasn't changed for 5 steps, or after 30 steps.
  In those cases it reports *Not sure* or *Gave up* instead of guessing.
- It can't get past anything that needs you: PIN or password screens, CAPTCHAs, payment confirmations, or
  screens that block screenshots (banking apps, `FLAG_SECURE`).
- Treat it as an assistant you watch, not one you leave alone. Don't ask it to do anything you can't undo.

## 5. Runs & costs

Every task the agent runs is saved with a step-by-step report, like the upstream web dashboard. Open it from
the app's main screen: **Runs & costs → Open** (below *Start overlay*).

- **The list** shows the newest run first: the task, a coloured status (green *Done*; orange *Not sure*,
  *Stopped*, *Gave up* or *Interrupted* (the app was closed mid-task); red *Error*), when it ran, the number
  of steps, how long it took, what it cost (in US dollars, to 5 decimals) and how many JEV tokens it used.
  The card on top adds up all listed runs: number of runs, total cost, total JEV tokens, and the success rate
  (the share of finished runs that ended *Done*).
- **While a task runs**, a blue-bordered card at the top shows it live (step, action, how sure JEV is, cost so
  far) with a red **Stop** button. The screen refreshes by itself.
- **Tap a run** to see its report. The header repeats the task, status and the agent's closing message (a
  warning, if any, is in a red box), when it started and finished, the totals and the phone. Then each step
  is a card:
  - the screenshot JEV saw, with a **red box** around what it picked and a **red circle** where it tapped
    (tap the picture to see it full screen; tap again to close);
  - **[n] ACTION** and its detail, e.g. `[1] CLICK at (550, 1848)` or `TYPE_ENTER 'weather in Berlin'`;
  - why: what that action means, plus any note (for example "Zoomed in to find exactly where to tap
    (location confidence 43% → 67%)");
  - **How sure JEV was**, as a bar, and the other options it weighed with their probabilities;
  - the tap target's confidence;
  - the result ("Executed.", "Executed, but nothing changed on screen.", or in red "Not executed: JEV wasn't
    sure enough."), how long JEV and the screen took, and the step's tokens and cost.
- **Share** (the arrow at the top of a report) sends its `report.json` to another app, for example Gmail or
  Drive.
- **Trash**: the bin icon on a run (or at the top of its report) moves it to the **Trash** tab. There you can
  tick runs (or **Select all**) and **Restore** them, or **Delete forever** (asks first; screenshots and
  report are gone for good).

The app keeps the **last 20 runs** and deletes older ones as new tasks start. Runs in the Trash don't count
towards the 20 and stay until you delete them forever. A run you restore counts again, so if you already
have 20, the oldest one goes when the next task starts.

## 6. Troubleshooting

| Symptom | Fix |
|---|---|
| "Add your JEV API key in Voice Control" | No JEV key is saved. Open the app → *JEV API key* → *Add* (see setup step 6). |
| "JEV rejected the API key — check it in Voice Control" | TypeSafe answered 401/403: the key is wrong, was deleted, or expired. The Setup row shows "!". Open *JEV API key*, paste a working key from console.typesafe.ai/keys and tap *Verify & save*. |
| "Voice Control can't see or touch the screen yet" or "accessibility service is no longer connected" | Turn the accessibility service **off and on** again (Settings → Accessibility → Android Voice Control). This happens after you reinstall or update the app: Android keeps the switch on, but the old connection is dead. |
| "Can't take a screenshot: INTERNAL_ERROR" | Same fix: turn the accessibility service off and on. |
| The bubble disappears in Settings | This is normal. Android hides every app's floating windows while Settings and some permission screens are open. The agent keeps working, and the bubble comes back when you leave Settings. |
| Tapping the bubble says "Voice isn't set up" and opens the sign-in screen | Sign in with ChatGPT there, or use an OpenAI API key. |
| Sign-in: "Sign-in port is busy" | Another app (another sign-in, or a Codex CLI login in Termux) holds ports 1455 and 1457. Close it and tap *Try again*. |
| Sign-in: "Token exchange failed" / "Sign-in couldn't be verified" | The page you finished on belonged to an older attempt, or OpenAI rejected the code. Tap *Try again* and finish the login within 5 minutes. |
| Sign-in: "Your ChatGPT plan has no voice" | The account is on ChatGPT Free. Sign in with a Plus/Pro account, or use an OpenAI API key. |
| "Android didn't allow the microphone right now" | Open the app once (it then counts as in use), then tap the bubble again. |
| "Sign in again" / "ChatGPT rejected the sign-in" | Your sign-in expired or was revoked. Use *Sign in with ChatGPT* → *Manage*, then sign in again. |
| "Your ChatGPT plan has no voice" / "Voice limit reached" | Your plan's voice allowance is used up. Wait, or switch to an OpenAI API key. |
| The assistant says "on it" but nothing happens | Sometimes GPT doesn't hand the task over. Say it again, a bit more directly: "open the clock app". |
| The agent taps the wrong thing or gives up | Be more specific: name the app and the setting. Check what it saw in *Runs & costs* (section 5). |
| The bubble is gone after a while | Turn off battery optimisation for the app (Setup list), then tap *Start overlay* again. |
| Foldable phone: the bubble is in an odd place after folding or unfolding | It moves to the same edge of the new screen once the screen has settled (about a second). If it still looks wrong, touch it once: that makes it re-measure the screen. |

**Costs.** JEV is billed by TypeSafe to the account that owns your API key: $42 per billion input tokens,
which is about $0.0002 per step, so a typical task costs $0.001–$0.003. Checking a key costs about
$0.00001. The app shows the cost when the task finishes; *Runs & costs* (section 5) shows it per step, per run and in total. With ChatGPT sign-in, GPT-Live voice uses your
ChatGPT plan's allowance. With an API key, OpenAI bills your account per minute of audio.

## 7. Debug commands (debug build, over adb)

```bash
P=com.brainstormity.voicecontrol
# Build + install. install.sh grants permissions, re-binds the accessibility service and checks that a
# screenshot works.
scripts/build.sh && scripts/install.sh
adb shell am start -n $P/.ui.MainActivity                      # open the app once after installing
adb shell am broadcast -a $P.OVERLAY_START                     # start the bubble (works while the app is open)

# Run tasks without voice (multi-word extras need the nested quotes)
adb shell am broadcast -a $P.RUN_TASK --es task "'Open Settings and turn on Bluetooth'"
adb shell "am broadcast -a $P.RUN_TASK --es task 'Open Chrome and search for \"weather in Berlin\"'"
adb shell am broadcast -a $P.STOP
adb logcat -s VC/Agent VC/Hands VC/Overlay

# Run reports: report.json plus one annotated JPEG per step (red box = target, circle = tap point)
adb shell run-as $P ls files/runs
adb exec-out run-as $P cat files/runs/<id>/report.json
adb exec-out run-as $P cat files/runs/<id>/01-CLICK.jpg > 01.jpg
adb shell run-as $P ls files/runs/.trash                       # runs in the Trash
adb shell am start -n $P/.ui.runs.RunsActivity                 # the Runs & costs screen (exported in debug builds)
adb shell am start -n $P/.ui.runs.RunDetailActivity --es id <id>   # one run's report

# ChatGPT sign-in (see app/src/main/java/.../voice/auth/README.md)
adb push ~/.codex/auth.json /data/local/tmp/codex-auth.json
adb shell am broadcast -p $P -a $P.AUTH_IMPORT --es path /data/local/tmp/codex-auth.json
adb shell rm /data/local/tmp/codex-auth.json
adb shell am broadcast -p $P -a $P.AUTH_STATUS
adb shell am broadcast -p $P -a $P.AUTH_LOGIN                         # open the sign-in screen and start a sign-in
adb shell am broadcast -p $P -a $P.AUTH_CALLBACK --es code bogus      # play OpenAI's redirect (expects "Token exchange failed")
adb shell am broadcast -p $P -a $P.AUTH_SIGNOUT
# Note: AUTH_REFRESH rotates the refresh token. A Codex CLI login you imported then stops working on the Mac.

# Voice without a microphone (emulator): fake mic + injected speech
say -o /tmp/req.aiff "Open settings and turn on bluetooth"
ffmpeg -i /tmp/req.aiff -ar 16000 -ac 1 -f s16le /tmp/req.raw && adb push /tmp/req.raw /data/local/tmp/
adb shell am broadcast -a $P.LIVE_START --ez fake_mic true
adb shell am broadcast -a $P.LIVE_INJECT --es file /data/local/tmp/req.raw
adb shell am broadcast -a $P.LIVE_STOP
adb logcat -s VC/Voice VC/Live VC/Agent

# Hands / screen checks
adb shell am broadcast -a $P.HANDS --es op screenshot
adb shell am broadcast -a $P.HANDS --es op tap --ei x 540 --ei y 1200
```
