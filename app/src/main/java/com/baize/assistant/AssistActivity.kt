package com.baize.assistant

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager

/**
 * 助理小窗 Activity — 底部胶囊面板。
 *
 * 两种触发路径：
 *   1. VoiceInteractionSession.startAssistantActivity() → 助理浮层（推荐）
 *   2. MainActivity 收到 VOICE_COMMAND 后跳转 → 包裹在 AssistWindowTheme 中
 *
 * 特性：
 *   - 底部弹出、全宽、自适应高度、无暗色遮罩
 *   - 打开时用 TTS 朗读自定义问候语（如"你好，我在"），同时启动录音
 *   - 锁屏/熄屏状态下可穿透显示并点亮屏幕
 *   - 所有通知行为可在设置中开关和自定义
 */
class AssistActivity : MainActivity() {

    override fun isAssistantWindow(): Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 锁屏穿透
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        // 底部弹出、全宽、自适应高度、无暗色遮罩
        window.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        window.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        window.setDimAmount(0f)
        playOpeningNotification()
    }

    /**
     * 播放助理小窗打开通知（纯 TTS，无提示音）。
     *
     *   - `openingNotificationEnabled == false` → 静默
     *   - TTS 已就绪 → playGreetingThenListen（朗读 + 立即录音）
     *   - TTS 未就绪 → pendingGreeting 暂存，就绪后自动播放
     */
    private fun playOpeningNotification() {
        val settings = settingsStore.load()
        if (!settings.openingNotificationEnabled) return
        if (!settings.ttsEnabled || !isTtsReady()) return

        if (ttsEngineReady) {
            playGreetingThenListen(settings.greetingText)
        } else {
            pendingGreeting = settings.greetingText
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
