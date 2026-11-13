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

This app is based on, and is mostly the logic of, **[System One Computer Use](https://github.com/brainstormity/System-One-Computer-Use)**
by brainstormity. It is an amazing project and you should check it out.

The ChatGPT sign-in and GPT-Live call handling follow
**[opencode-gpt-live](https://github.com/malhashemi/opencode-gpt-live)** by M. Adel Alhashemi.

## What you need

- An Android phone on Android 11 or newer.
- A JEV API key from [TypeSafe AI](https://console.typesafe.ai/keys). Steps cost about $0.0002 each.
- A ChatGPT Plus/Pro account for the voice side, or an OpenAI API key (official GPT-Live API).

## Install and set up

1. `adb install dist/voice-control-<version>-arm64-v8a-release.apk` (or build it, see below).
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
scripts/release.sh       # signed, R8-minified release APKs + mapping into dist/; embeds no keys
```

Release builds come as one APK per CPU architecture plus a universal one:
`voice-control-<version>-{arm64-v8a,armeabi-v7a,x86_64,x86,universal}-release.apk`. Almost every phone
needs **arm64-v8a** (about 13 MB). Use the universal APK (about 43 MB) for anything else: old 32-bit
phones, x86 emulators, or if you're not sure. Keep `dist/mapping-<version>.txt` to decode crash stack traces.

`scripts/emulator.sh`, `scripts/install.sh` and `scripts/logs.sh` cover the emulator workflow.
Architecture notes are in [ARCHITECTURE.md](ARCHITECTURE.md) and [docs/SKELETON.md](docs/SKELETON.md).

## Safety

The agent controls your real phone. It stops when JEV is unsure, it never types text that is not in
your spoken request, and on-screen text is treated as data, not instructions. Still: watch it while it
runs, and press the stop button on the strip if it does something unexpected.

## License

MIT. See [LICENSE](LICENSE) and [NOTICE](NOTICE) for third-party credits.
