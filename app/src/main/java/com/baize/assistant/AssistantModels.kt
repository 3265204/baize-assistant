package com.baize.assistant

data class DeepSeekSettings(
    // DeepSeek API Key：用于调用 DeepSeek，不在代码中硬编码。
    val apiKey: String,
    // Base URL：默认官方地址，也可替换成兼容 OpenAI Chat Completions 的代理地址。
    val baseUrl: String,
    // 模型名：MVP 默认 deepseek-v4-flash。
    val model: String,
    // 语音识别与 TTS 语言：默认中文普通话，传给系统 SpeechRecognizer 和 TextToSpeech。
    val speechLocale: String,
    // 语音识别引擎：local_paraformer 更轻更省电，system 使用 Android 系统识别服务。
    val speechEngine: String,
    // sherpa-onnx Paraformer 模型文件路径：由 App 从 APK assets 自动安装到私有目录。
    val localAsrModelPath: String,
    // 本地识别语言：Paraformer small 当前主要面向中文，保留字段方便后续切模型。
    val localAsrLanguage: String,
    // 本地 ASR 推理线程数：默认 1，降低发热；高性能设备可调到 2。
    val localAsrNumThreads: Int,
    // 本地 ASR 自动静音停止：用户说完后自动结束录音并识别，减少无效推理。
    val localAsrAutoStop: Boolean,
    // 本地 TTS 开关：关闭后 App 不主动朗读回答或执行结果。
    val ttsEnabled: Boolean,
    // TTS 应答朗读开关：仅在 ttsEnabled=true 时生效。关闭后不再朗读 DeepSeek 回答和系统提示，
    // 但助理小窗的问候语仍可单独通过 openingNotificationEnabled 控制。
    val ttsResponseEnabled: Boolean,
    // 助理小窗打开时的通知开关：控制是否播放问候语或提示音。
    val openingNotificationEnabled: Boolean,
    // 助理小窗问候语：打开助理小窗时用 TTS 朗读的自定义文本，默认"你好，我在"。
    val greetingText: String
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_MODEL = "deepseek-v4-flash"
        const val LEGACY_DEFAULT_MODEL = "deepseek-chat"
        const val DEFAULT_SPEECH_LOCALE = "zh-CN"
        const val SPEECH_ENGINE_LOCAL = "local_paraformer"
        const val SPEECH_ENGINE_SYSTEM = "system"
        const val DEFAULT_LOCAL_ASR_LANGUAGE = "zh"
        const val DEFAULT_LOCAL_ASR_THREADS = 1
    }
}

data class AssistantIntent(
    // DeepSeek 返回的结构化意图：set_alarm、create_reminder、question、unknown。
    val intent: String,
    // 闹钟或日程标题。
    val title: String = "",
    // ISO-8601 带时区时间，例如 2026-06-10T07:00:00+08:00。
    val datetime: String = "",
    // 日程详情或补充说明。
    val detail: String = "",
    // 重复星期：使用 java.util.Calendar 的星期值，1=周日，2=周一，...，7=周六。
    val repeatDays: List<Int> = emptyList(),
    // 是否请求跳过法定节假日；系统闹钟 Intent 没有官方字段，仅用于给用户说明限制。
    val skipHolidays: Boolean = false,
    // 普通问答或无法识别时的自然语言回复。
    val answer: String = ""
)

data class AssistantTask(
    // 本地任务 id，仅用于任务列表展示。
    val id: String,
    // 本地任务类型：alarm 或 reminder。
    val type: String,
    // 用户可读标题。
    val title: String,
    // 来源或说明，例如系统时钟、系统日历。
    val detail: String,
    // 创建时间，用于 7 天自动清理。
    val createdAt: String,
    // 计划执行时间，用于首页展示。
    val scheduledAt: String,
    // 当前状态：MVP 阶段记录 created。
    val status: String
)

data class ChatMessage(
    // 本地聊天消息 id，用于长按删除单条消息。
    val id: String,
    // role 为 user 或 assistant。
    val role: String,
    // 消息正文。
    val content: String,
    // 创建时间，用于排序和展示。
    val createdAt: String
)

data class ChatConversation(
    // 会话 id，用于切换和删除整段对话。
    val id: String,
    // 列表标题，默认取第一条用户消息。
    val title: String,
    // 更新时间，用于“最近”排序。
    val updatedAt: String
)
