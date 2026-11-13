# Android Voice Control

Talk to your phone and it does the task. A floating mic bubble sits over every app; tap it, say
"open settings and turn on Bluetooth", and the phone opens Settings, finds the toggle and taps it,
then tells you it is done.

Under the hood, GPT-Live (OpenAI's live voice model) understands what you want and hands the phone a
crisp task. The phone then runs a screenshot → decide → act loop on-device: take a screenshot, ask
JEV (TypeSafe AI's System One model) what to tap, type or swipe next, do it through an Android
accessibility service, and repeat until the task is done. No computer, no adb, no cloud agent in
the middle.

## Credit where it is due

This app is a port of **[System One Computer Use](https://github.com/brainstormity/System-One-Computer-Use)**
by the brainstormity team, and it would not exist without it. That project is an amazing piece of
work: a complete computer-use and phone-use agent in a few hundred readable lines, with a model that
only ever picks from options (so web pages can't inject instructions), a confidence gate that stops
instead of guessing, zoom-in refinement for tiny targets, and a dashboard that shows exactly what
the agent saw, chose and paid for at every step. Every one of those ideas is carried over here, and
the Kotlin port is checked against the original Python with golden tests. If you want to understand
how this app thinks, read `jev_agent.py` there first.

The ChatGPT sign-in and GPT-Live call handling follow
**[opencode-gpt-live](https://github.com/malhashemi/opencode-gpt-live)** by M. Adel Alhashemi.

## What you need

- An Android phone on Android 11 or newer.
- A JEV API key from [TypeSafe AI](https://console.typesafe.ai/keys). Steps cost about $0.0002 each.
- A ChatGPT Plus/Pro account for the voice side, or an OpenAI API key (official GPT-Live API).

## Install and set up

1. `adb install dist/voice-control-release.apk` (or build it, see below).
2. Open **Voice Control** and work through the setup checklist: display over other apps, the
   accessibility service, microphone, notifications.
3. Sign in with ChatGPT (opens the OpenAI login in your browser) or enter an OpenAI API key.
4. Enter your JEV API key. The app verifies it and stores it encrypted.
5. Tap **Start overlay**. The grey bubble appears; tap it and talk.

Bubble colours: grey = idle, amber = connecting, green = listening, blue = GPT speaking, red = error.
While a task runs, a thin strip at the top shows the step and a stop button. The **Runs** screen shows
every run step by step with the screenshot, the action, confidence and cost.

The full guide, including troubleshooting and the debug commands, is in [docs/USER_GUIDE.md](docs/USER_GUIDE.md).

## Build

```bash
cp .env.example .env     # set JAVA_HOME, ANDROID_HOME; optional JEV_KEY / OPENAI_API_KEY for debug builds
source scripts/env.sh
scripts/build.sh         # debug APK
./gradlew :app:assembleRelease   # release APK, signed if keystore.properties exists; embeds no keys
```

`scripts/emulator.sh`, `scripts/install.sh` and `scripts/logs.sh` cover the emulator workflow.
Architecture notes are in [ARCHITECTURE.md](ARCHITECTURE.md) and [docs/SKELETON.md](docs/SKELETON.md).

## Safety

The agent controls your real phone. It stops when JEV is unsure, it never types text that is not in
your spoken request, and on-screen text is treated as data, not instructions. Still: watch it while it
runs, and press the stop button on the strip if it does something unexpected.

## License

MIT. See [LICENSE](LICENSE) and [NOTICE](NOTICE) for third-party credits.
