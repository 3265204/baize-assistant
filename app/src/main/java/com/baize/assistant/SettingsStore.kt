package com.baize.assistant

import android.content.Context
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val defaultLocalModelPath = AssetModelInstaller(context).modelPath()

    // 从 SharedPreferences 读取所有可配置项，并为首次启动提供 MVP 默认值。
    fun load(): DeepSeekSettings {
        val savedModel = prefs.getString("model", "").orEmpty()
        return DeepSeekSettings(
            apiKey = prefs.getString("api_key", "").orEmpty(),
            baseUrl = prefs.getString("base_url", DeepSeekSettings.DEFAULT_BASE_URL).orEmpty(),
            model = normalizeModel(savedModel),
            speechLocale = prefs.getString("speech_locale", DeepSeekSettings.DEFAULT_SPEECH_LOCALE).orEmpty(),
            speechEngine = prefs.getString("speech_engine", DeepSeekSettings.SPEECH_ENGINE_LOCAL).orEmpty(),
            localAsrModelPath = defaultLocalModelPath,
            localAsrLanguage = prefs.getString("local_asr_language", DeepSeekSettings.DEFAULT_LOCAL_ASR_LANGUAGE).orEmpty(),
            localAsrNumThreads = prefs.getInt("local_asr_num_threads", DeepSeekSettings.DEFAULT_LOCAL_ASR_THREADS),
            localAsrAutoStop = prefs.getBoolean("local_asr_auto_stop", true),
            ttsEnabled = prefs.getBoolean("tts_enabled", true),
            ttsResponseEnabled = prefs.getBoolean("tts_response_enabled", true),
            openingNotificationEnabled = prefs.getBoolean("opening_notification_enabled", true),
            greetingText = prefs.getString("greeting_text", "你好，我在").orEmpty().ifBlank { "你好，我在" }
        )
    }

    // 保存 DeepSeek、语音识别语言、本地 TTS 开关等设置。
    fun save(settings: DeepSeekSettings) {
        prefs.edit()
            .putString("api_key", settings.apiKey)
            .putString("base_url", settings.baseUrl)
            .putString("model", settings.model)
            .putString("speech_locale", settings.speechLocale)
            .putString("speech_engine", settings.speechEngine)
            .putString("local_asr_language", settings.localAsrLanguage)
            .putInt("local_asr_num_threads", settings.localAsrNumThreads)
            .putBoolean("local_asr_auto_stop", settings.localAsrAutoStop)
            .putBoolean("tts_enabled", settings.ttsEnabled)
            .putBoolean("tts_response_enabled", settings.ttsResponseEnabled)
            .putBoolean("opening_notification_enabled", settings.openingNotificationEnabled)
            .putString("greeting_text", settings.greetingText)
            .apply()
    }

    private fun normalizeModel(savedModel: String): String {
        return when {
            savedModel.isBlank() -> DeepSeekSettings.DEFAULT_MODEL
            savedModel == DeepSeekSettings.LEGACY_DEFAULT_MODEL -> DeepSeekSettings.DEFAULT_MODEL
            else -> savedModel
        }
    }
}
