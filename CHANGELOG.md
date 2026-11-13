# Changelog

Release notes for Voice Control. The CI release workflow publishes the section of the tagged version
(`## <version>` up to the next `## `) as the GitHub release notes.

## 0.1.1

First public release.

- Voice control through GPT-Live: sign in with ChatGPT (Plus/Pro) or use an OpenAI API key.
- On-device JEV screenshot → act loop drives the phone through the accessibility service.
- Floating overlay bubble, colour-coded (idle, connecting, listening, speaking, error), with a task strip and stop button.
- Runs & costs screens: every run step by step, including the GPT voice cost.
- Foldables: the overlay follows fold/unfold.
- Per-ABI release APKs (arm64-v8a, armeabi-v7a, x86_64, x86) plus a universal APK, R8-minified.

## 0.1.0

Initial internal build.
