package com.baize.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession

/**
 * 语音交互会话。
 * 由系统通过 AssistantVoiceSessionService 创建，在 onShow() 中启动 AssistActivity。
 *
 * startAssistantActivity() 会为 Activity 加上 Assistant 专用窗口标志
 * （TYPE_APPLICATION_OVERLAY 级别），保证它作为浮动面板而非全屏 Activity 展示。
 */
class AssistantVoiceSession(private val appContext: Context) : VoiceInteractionSession(appContext) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)

        val intent = Intent(appContext, AssistActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_AUTO_VOICE, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        runCatching {
            // 首选：通过 Session 启动，获得正确的助理浮层窗口属性
            startAssistantActivity(intent)
        }.onFailure {
            // 降级：普通 startActivity，保留锁屏穿透标志
            appContext.startActivity(intent)
        }
        finish()
    }
}
