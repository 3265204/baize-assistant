<!-- markdownlint-disable -->

<div align="center">

# Baize

An AI assistant that lives on your Android phone

Powered by DeepSeek

Under active development ✿✿ヽ(°▽°)ノ✿

<br>
<div>
    <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-purple?logo=kotlin">
    <img alt="Android" src="https://img.shields.io/badge/Android-8.0%2B-green?logo=android">
    <img alt="license" src="https://img.shields.io/badge/License-MIT-lightgrey">
    <img alt="status" src="https://img.shields.io/badge/status-MVP-yellow">
</div>
<br>

[中文](README.md)

</div>

<!-- markdownlint-restore -->

## Features

- **Set alarms** — "Wake me up at 7 tomorrow" → creates a system alarm via `AlarmClock`, synced to your Galaxy Watch
- **Add reminders** — "Remind me to submit the report Friday 3pm" → writes to `CalendarContract`, visible in your system calendar
- **Offline voice recognition** — built-in sherpa-onnx + Paraformer Chinese model, no internet needed, low latency, zero privacy risk
- **TTS replies** — uses Android's built-in `TextToSpeech`, free, battery-friendly, no third-party dependencies
- **Assistant panel** — register as the default assistant, then long-press the power button to bring up a bottom panel (works with Samsung's side key customization)
- **Multi-turn chat** — multiple conversations, history search, one-tap delete
- **Pure Kotlin** — zero Java, `com.baize.assistant`, namespace and applicationId unified

## Getting Started

You'll need: Android Studio, JDK 17, Android SDK 35, an Android 8.0+ phone, and a DeepSeek API key.

```bash
git clone <your-repo-url>
cd baize-assistant
./gradlew assembleDebug
```

On Windows, use `.\gradlew.bat assembleDebug`.

The first build downloads the sherpa-onnx AAR and the Paraformer Chinese speech model — speed depends on your network.

Install the APK → open Baize → enter your API key in Settings → done. Optionally, set Baize as the default assistant: `Settings → Apps → Default apps → Digital assistant app`, then long-press the power button to invoke it.

## Tech Stack

| Item | Choice | Why |
| --- | --- | --- |
| Language | Kotlin | Android's first-class language |
| Min API | 26 (Android 8.0) | Covers the vast majority of active devices |
| AI | DeepSeek `deepseek-v4-flash` | Great Chinese understanding, affordable, fast |
| Local ASR | sherpa-onnx + Paraformer Chinese small (int8) | Works offline, good privacy and latency |
| TTS | Android `TextToSpeech` | Zero extra dependencies |
| Storage | `SharedPreferences` + JSON | Good enough for MVP |

## Permissions

| Permission | Purpose |
| --- | --- |
| `INTERNET` | Call the DeepSeek API |
| `RECORD_AUDIO` | Voice input / offline ASR |
| `SET_ALARM` | Create system alarms |
| `READ/WRITE_CALENDAR` | Read and write calendar reminders |
| Voice Interaction Service | Register as the system default assistant |

## Known Issues

- Early stage; primarily tested on Samsung s25Ultra phones. Assistant entry behavior may differ on other brands.
- Alarm querying, deletion, and holiday skipping are limited by system APIs — vendor support varies.
- Larger local speech models are more accurate but consume more power and generate more heat. Currently using a small model as a balance.
- Calendar reminder auto-save depends on the system calendar UI; some ROMs may have compatibility issues.
- MVP phase — code structure and data formats may still change. Issues and PRs welcome.

## Project Layout

```text
baize-assistant/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/
│       │   └── sherpa-onnx-paraformer-zh-small/   ← offline ASR model files
│       ├── java/com/baize/assistant/
│       │   │
│       │   ├── Main UI ────────────────────────────
│       │   │   ├── MainActivity.kt               Activity skeleton, lifecycle, gestures
│       │   │   ├── MainActivityUi.kt              UI building, keyboard, sidebar
│       │   │   ├── MainActivityVoice.kt           Voice recognition (system + local)
│       │   │   ├── MainActivityChat.kt            Chat sending, conversation CRUD, settings
│       │   │   ├── MainActivityResult.kt          DeepSeek result dispatch, alarm/calendar intents
│       │   │   └── ThemeColors.kt                 Colors, dp extensions, theme constants
│       │   │
│       │   ├── Assistant Entry ────────────────────
│       │   │   ├── AssistActivity.kt              Assistant panel (bottom sheet, lockscreen)
│       │   │   ├── AssistantVoiceInteractionService.kt   Register as default assistant
│       │   │   ├── AssistantVoiceSessionService.kt       Voice session service
│       │   │   └── AssistantVoiceSession.kt              Session lifecycle, TTS playback
│       │   │
│       │   ├── Core Modules ───────────────────────
│       │   │   ├── DeepSeekClient.kt              API client, prompt builder, stream parser
│       │   │   ├── LocalSherpaAsr.kt              Local sherpa-onnx speech recognition
│       │   │   └── AssetModelInstaller.kt         Extract ASR model on first launch
│       │   │
│       │   └── Data Layer ─────────────────────────
│       │       ├── AssistantModels.kt             All data classes, enums, parsers
│       │       ├── ChatStore.kt                   Conversation/message JSON persistence
│       │       ├── TaskStore.kt                   Alarm and reminder records, expiry cleanup
│       │       └── SettingsStore.kt               API key, TTS toggle, preferences
│       │
│       └── res/
├── build.gradle
├── settings.gradle
├── README.md
└── README.en.md
```

## Acknowledgments

- [DeepSeek](https://deepseek.com) — AI inference service
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) — On-device speech recognition engine
- [Paraformer](https://github.com/alibaba-damo-academy/FunASR) — Chinese speech recognition model

## License

MIT

<br>

> If this project helps you, a Star ⭐ would mean a lot~
