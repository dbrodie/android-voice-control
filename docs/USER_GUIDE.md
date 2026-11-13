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

The build includes the JEV key from `.env`. You can replace it in the app under Settings → JEV API key.

## 2. First-run setup

Open the app. The **Setup** list shows a green ✓ for each step that is done. Do the steps from top to bottom:

1. **Display over other apps**: tap *Grant*, turn on *Android Voice Control*, then go back. This is what
   lets the bubble appear.
2. **Accessibility service**: tap *Open*. Go to *Installed apps* (on some phones *Downloaded apps*), choose
   *Android Voice Control*, turn it on and confirm. This is how the app sees the screen and taps for you.
   Android 13+ may first say the setting is "restricted" for side-loaded apps. If so, open the app's
   *App info* page, tap ⋮ → *Allow restricted settings*, and try again.
3. **Microphone**: tap *Allow*.
4. **Sign in with ChatGPT**: tap *Sign in*, then *Sign in with ChatGPT*. The browser opens. Log in, and you
   come back to the app. Voice then runs on your ChatGPT Plus/Pro plan. If you want to use an **OpenAI API
   key** instead, skip this step and enter the key under *Settings → OpenAI API key*.
5. **Notifications**: tap *Allow*. You get the "bubble running" notification, which has a Stop button.
6. *(Optional)* **Ignore battery optimisation**: this stops aggressive phones from killing the bubble.

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

## 5. Troubleshooting

| Symptom | Fix |
|---|---|
| "Voice Control can't see or touch the screen yet" or "accessibility service is no longer connected" | Turn the accessibility service **off and on** again (Settings → Accessibility → Android Voice Control). This happens after you reinstall or update the app: Android keeps the switch on, but the old connection is dead. |
| "Can't take a screenshot: INTERNAL_ERROR" | Same fix: turn the accessibility service off and on. |
| The bubble disappears in Settings | This is normal. Android hides every app's floating windows while Settings and some permission screens are open. The agent keeps working, and the bubble comes back when you leave Settings. |
| Tapping the bubble opens the app with "Voice isn't set up" | Sign in with ChatGPT, or enter an OpenAI API key and pick that backend under *Voice*. |
| "Android didn't allow the microphone right now" | Open the app once (it then counts as in use), then tap the bubble again. |
| "Sign in again" / "ChatGPT rejected the sign-in" | Your sign-in expired or was revoked. Use *Sign in with ChatGPT* → *Manage*, then sign in again. |
| "Your ChatGPT plan has no voice" / "Voice limit reached" | Your plan's voice allowance is used up. Wait, or switch to an OpenAI API key. |
| The assistant says "on it" but nothing happens | Sometimes GPT doesn't hand the task over. Say it again, a bit more directly: "open the clock app". |
| The agent taps the wrong thing or gives up | Be more specific: name the app and the setting. Run reports are saved (see below). |
| The bubble is gone after a while | Turn off battery optimisation for the app (Setup list), then tap *Start overlay* again. |

**Costs.** JEV costs about $0.0002 per step, so a typical task costs $0.001–$0.003. The app shows the cost
when the task finishes, and it is also in the run report. With ChatGPT sign-in, GPT-Live voice uses your
ChatGPT plan's allowance. With an API key, OpenAI bills your account per minute of audio.

## 6. Debug commands (debug build, over adb)

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

# ChatGPT sign-in (see app/src/main/java/.../voice/auth/README.md)
adb push ~/.codex/auth.json /data/local/tmp/codex-auth.json
adb shell am broadcast -p $P -a $P.AUTH_IMPORT --es path /data/local/tmp/codex-auth.json
adb shell rm /data/local/tmp/codex-auth.json
adb shell am broadcast -p $P -a $P.AUTH_STATUS
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
