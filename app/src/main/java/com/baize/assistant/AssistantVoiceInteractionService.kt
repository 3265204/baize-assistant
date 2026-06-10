package com.baize.assistant

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService

/**
 * 系统语音交互服务入口。
 * 当用户设为默认数字助理后，蓝牙耳机按键、长按电源键等硬件触发会走到这里。
 *
 * 正常路径：系统创建 VoiceInteractionSession → onShow() → startAssistantActivity()
 * 降级路径：Session 创建失败 → fallback 到 startActivity()
 */
class AssistantVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        // 禁用系统默认的"显示上下文"（我们自己的 UI 替代）
        setDisabledShowContext(0)
    }

    /**
     * 从锁屏/熄屏触发语音助理（耳机按键、电源键长按）。
     * 不直接 startActivity，而是 showSession() 让系统创建 VoiceInteractionSession，
     * 在 Session 里用 startAssistantActivity() 获得正确的助理浮层窗口属性。
     */
    override fun onLaunchVoiceAssistFromKeyguard() {
        super.onLaunchVoiceAssistFromKeyguard()
        showSession(Bundle(), 0)
    }

    /**
     * Session 创建/显示失败时的降级方案。
     * 直接 startActivity 启动 AssistActivity（含锁屏穿透标志）。
     */
    override fun onShowSessionFailed(args: Bundle) {
        super.onShowSessionFailed(args)
        val intent = Intent(this, AssistActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra(MainActivity.EXTRA_AUTO_VOICE, true)
        }
        startActivity(intent)
    }
}
