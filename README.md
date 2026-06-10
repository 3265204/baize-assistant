<!-- markdownlint-disable -->

<div align="center">

# 白泽 · Baize

一个跑在 Android 手机上的 AI 助理

基于DeepSeek

绝赞开发中 ✿✿ヽ(°▽°)ノ✿

<br>
<div>
    <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-purple?logo=kotlin">
    <img alt="Android" src="https://img.shields.io/badge/Android-8.0%2B-green?logo=android">
    <img alt="license" src="https://img.shields.io/badge/License-MIT-lightgrey">
    <img alt="status" src="https://img.shields.io/badge/status-MVP-yellow">
</div>
<br>

[English](README.en.md)

</div>

<!-- markdownlint-restore -->

## 特性

- **定闹钟** — "明早七点叫我" → 系统闹钟创建，手表同步，不走旁路
- **加提醒** — "周五下午三点催我交周报" → 日历事件写入 `CalendarContract`，系统日历直接可见
- **离线语音识别** — 内置 sherpa-onnx + Paraformer 中文小模型，不用联网也能转文字，延迟低、零隐私泄漏
- **TTS 念回复** — 系统自带 `TextToSpeech`，免费、省电、不依赖第三方
- **助理小窗** — 注册为系统默认助理后长按电源键呼出（三星长按侧边键自定义助手），底部弹出、全宽无遮罩、用完即走
- **多轮对话** — 多会话切换、历史搜索、一键删除，聊过的内容不丢
- **纯 Kotlin** — 零 Java，`com.baize.assistant`，namespace 和 applicationId 统一

## 怎么跑起来

需要：Android Studio、JDK 17、Android SDK 35、Android 8.0+ 手机、DeepSeek API Key。

```bash
git clone <your-repo-url>
cd baize-assistant
./gradlew assembleDebug
```

Windows 用 `.\gradlew.bat assembleDebug`。

首次构建会下载 sherpa-onnx AAR 和 Paraformer 中文语音模型，由网络环境决定下载速度。

装好 APK → 打开白泽 → 设置里填 API Key → 就完事了。可选：`设置 → 应用 → 默认应用 → 数字助理应用` 里选白泽，之后长按电源键直接呼出。

## 技术栈

| 项目 | 选型 | 原因 |
| --- | --- | --- |
| 语言 | Kotlin | Android 首选 |
| 最低 API | 26 (Android 8.0) | 覆盖绝大多数在用手机 |
| AI | DeepSeek `deepseek-v4-flash` | 中文理解好、便宜、响应快 |
| 本地 ASR | sherpa-onnx + Paraformer 中文小模型 (int8) | 离线可用，隐私和延迟都好 |
| TTS | Android 系统 `TextToSpeech` | 零额外依赖 |
| 存储 | `SharedPreferences` + JSON | MVP 够用 |

## 权限

| 权限 | 用途 |
| --- | --- |
| `INTERNET` | 调 DeepSeek API |
| `RECORD_AUDIO` | 语音输入 / 离线 ASR |
| `SET_ALARM` | 创建系统闹钟 |
| `READ/WRITE_CALENDAR` | 读写日历提醒 |
| Voice Interaction Service | 注册为系统助理入口 |

## 已知问题

- 早期版本，在三星S25Ultra上测式，其他品牌助理入口行为可能有差异。
- 闹钟查询/删除/节假日跳过受系统 API 限制，各厂商支持度不一。
- 本地语音模型越大越准，但发热和耗电也更大。当前用小模型做平衡。
- 日历提醒自动保存依赖系统日历页面，部分 ROM 兼容性有问题。
- MVP 阶段，代码和数据格式可能调整，欢迎 Issue 和 PR。

## 项目结构

```text
baize-assistant/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/
│       │   └── sherpa-onnx-paraformer-zh-small/   ← 离线 ASR 模型文件
│       ├── java/com/baize/assistant/
│       │   │
│       │   ├── 主界面 ─────────────────────────────
│       │   │   ├── MainActivity.kt               Activity 骨架、生命周期、手势
│       │   │   ├── MainActivityUi.kt              UI 构建、键盘、侧边栏
│       │   │   ├── MainActivityVoice.kt           语音识别（系统 + 本地录音）
│       │   │   ├── MainActivityChat.kt            对话发送、会话增删改、设置弹窗
│       │   │   ├── MainActivityResult.kt          DeepSeek 结果分发、闹钟/日历 Intent
│       │   │   └── ThemeColors.kt                 颜色、dp 扩展、主题常量
│       │   │
│       │   ├── 助理入口 ───────────────────────────
│       │   │   ├── AssistActivity.kt              助理小窗（底部弹出、锁屏穿透）
│       │   │   ├── AssistantVoiceInteractionService.kt   注册为系统默认助理
│       │   │   ├── AssistantVoiceSessionService.kt       语音会话服务
│       │   │   └── AssistantVoiceSession.kt              会话生命周期、TTS 播报
│       │   │
│       │   ├── 核心模块 ───────────────────────────
│       │   │   ├── DeepSeekClient.kt              API 调用、提示词构建、流式解析
│       │   │   ├── LocalSherpaAsr.kt              本地 sherpa-onnx 语音识别封装
│       │   │   └── AssetModelInstaller.kt         首次启动解压 ASR 模型到私有目录
│       │   │
│       │   └── 数据层 ────────────────────────────
│       │       ├── AssistantModels.kt             全部数据类、枚举、解析器
│       │       ├── ChatStore.kt                   对话/会话 JSON 持久化
│       │       ├── TaskStore.kt                   闹钟和提醒记录、过期清理
│       │       └── SettingsStore.kt               API Key、TTS 开关等配置
│       │
│       └── res/
├── build.gradle
├── settings.gradle
├── README.md
└── README.en.md
```

## 致谢

- [DeepSeek](https://deepseek.com) — AI 推理服务
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) — 本地语音识别引擎
- [Paraformer](https://github.com/alibaba-damo-academy/FunASR) — 中文语音识别模型

## License

MIT

<br>

> 如果这个项目对你有帮助，点个 Star 吧 ⭐，这是对我最大的支持~
