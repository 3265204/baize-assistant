package com.baize.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Collections

// =============================================================================
// MainActivity.kt — Activity 核心骨架。
//
// 职责：
//   1. 声明所有 UI 组件和状态字段（internal，供同模块扩展函数访问）
//   2. 管理生命周期（onCreate / onDestroy / onRequestPermissionsResult）
//   3. 路由触摸事件（dispatchTouchEvent）→ 侧边栏手势
//
// 业务逻辑已分散到以下扩展文件：
//   - ThemeColors.kt       颜色/样式/dp
//   - MainActivityUi.kt    UI 构建/键盘/侧边栏
//   - MainActivityVoice.kt 语音识别（系统 + 本地）/忙状态
//   - MainActivityChat.kt  对话/会话/状态/设置弹窗
//   - MainActivityResult.kt DeepSeek 结果/系统 Intent/TTS/格式化
//
// 子类：AssistActivity（助理小窗模式，覆写 isAssistantWindow() = true）
// =============================================================================

open class MainActivity : Activity() {

    // ---- 持久化存储 ----
    internal lateinit var settingsStore: SettingsStore
    internal lateinit var taskStore: TaskStore
    internal lateinit var chatStore: ChatStore
    internal lateinit var modelInstaller: AssetModelInstaller

    // ---- 主界面视图 ----
    internal lateinit var taskList: LinearLayout
    internal lateinit var conversationList: LinearLayout
    internal lateinit var chatList: LinearLayout
    internal lateinit var chatScrollView: ScrollView
    internal lateinit var statusText: TextView
    internal lateinit var answerText: TextView
    internal lateinit var assistStatusText: TextView
    internal lateinit var input: EditText
    internal lateinit var voiceButton: Button

    // ---- 侧边栏相关 ----
    internal var sidebarView: View? = null
    internal var mainStageView: View? = null
    internal var sidebarVisible = false

    // ---- 触摸/手势状态 ----
    internal var globalTouchDownX = 0f
    internal var globalTouchDownY = 0f
    internal var suppressConversationLongPressUntil = 0L

    // ---- TTS ----
    internal lateinit var tts: TextToSpeech
    internal var ttsEngineReady = false          // TTS 引擎异步初始化完成标志
    internal var pendingGreeting: String? = null  // TTS 未就绪时暂存的问候语

    // ---- 问候语过滤 ----
    internal var greetingTextForFilter: String? = null  // 当前问候语，ASR 结果中需过滤
    internal var greetingModeUntilMs: Long = 0           // 问候模式截止时间（长静音阈值）

    // ---- 自动语音 ----
    internal var pendingAutoVoice = false

    // ---- 助理小窗忙状态 ----
    internal var assistWindowBusy = false

    // ---- 系统语音识别 ----
    internal var speechRecognizer: SpeechRecognizer? = null
    internal var isListening = false
    internal var discardCurrentVoiceInput = false

    // ---- 本地 Paraformer 录音 ----
    internal var localRecorder: AudioRecord? = null
    internal var localRecordingThread: Thread? = null
    @Volatile internal var isLocalRecording = false
    internal val localPcmChunks = Collections.synchronizedList(mutableListOf<ShortArray>())

    // ---- 助理小窗内存上下文（不与主界面 ChatStore 混用） ----
    internal val assistantWindowMessages = mutableListOf<ChatMessage>()
    internal var assistantWindowConversationId = ""

    // =========================================================================
    // 生命周期
    // =========================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 蓝牙耳机/车载按键 → 跳转到 Bixby 风格助理小窗，本身不渲染
        if (Intent.ACTION_VOICE_COMMAND == intent?.action) {
            startActivity(Intent(this, AssistActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                        or Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
                putExtra(EXTRA_AUTO_VOICE, true)
            })
            finish()
            return
        }

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        // 初始化存储和工具类
        modelInstaller = AssetModelInstaller(this)
        settingsStore = SettingsStore(this)
        taskStore = TaskStore(this)
        chatStore = ChatStore(this)

        // 检查是否需要自动启动语音（由外部 Intent 触发）
        pendingAutoVoice = intent?.getBooleanExtra(EXTRA_AUTO_VOICE, false) == true

        // 首次创建时选择/创建会话
        if (savedInstanceState == null) {
            if (!isAssistantWindow()) {
                selectEmptyFirstConversationOrCreate()
            }
        }

        // 清理超过 7 天的本地任务记录
        taskStore.deleteExpiredTasks()

        // 初始化系统 TTS 引擎（异步；回调在引擎就绪后触发）
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsEngineReady = true
                applyTtsLocale()
                // 如果 AssistActivity 打开时 TTS 未就绪，问候语被暂存，
                // 现在引擎好了立刻播放，播放完自动开始语音输入
                pendingGreeting?.let { text ->
                    pendingGreeting = null
                    playGreetingThenListen(text)
                }
            }
        }

        // 构建 UI（主界面或助理小窗）
        buildUi()

        // 非小窗模式下刷新所有数据
        if (!isAssistantWindow()) {
            refreshStatus()
            refreshConversations()
            refreshChat()
            refreshTasks()
        }

        // 后台安装本地 ASR 模型（从 APK assets 解压到私有目录）
        Thread {
            val result = modelInstaller.ensureInstalled()
            runOnUiThread {
                result.onFailure {
                    answerText.text = "内置 ASR 模型安装失败：${it.message}"
                }
                if (!isAssistantWindow()) refreshStatus()
                maybeStartAutoVoice()
            }
        }.start()
    }

    override fun onDestroy() {
        // 清理小窗内存上下文
        if (isAssistantWindow()) {
            assistantWindowMessages.clear()
        }

        // 安全释放录音资源
        if (isLocalRecording) {
            isLocalRecording = false
            runCatching { localRecorder?.stop() }
        }

        speechRecognizer?.destroy()
        localRecorder?.release()

        // 停止并释放 TTS
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // 录音权限被授予后自动重新启动语音输入
        if (requestCode == REQUEST_RECORD_AUDIO &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            startVoiceInput()
        }
    }

    // =========================================================================
    // 触摸事件路由
    // =========================================================================

    /**
     * 全局触摸事件拦截（仅主界面）。
     * 实现：
     *   - 右滑 → 打开侧边栏
     *   - 左滑 → 关闭侧边栏
     *   - 水平拖拽期间抑制侧边栏行项目的长按（防误触删除）
     *   - 轻点空白 → 收起键盘
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!isAssistantWindow()) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    globalTouchDownX = event.rawX
                    globalTouchDownY = event.rawY
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - globalTouchDownX
                    val dy = event.rawY - globalTouchDownY
                    val horizontalDrag = kotlin.math.abs(dx) > dp(18) &&
                        kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.2f
                    if (horizontalDrag) {
                        suppressConversationLongPressUntil =
                            System.currentTimeMillis() + 700L
                    }
                }

                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - globalTouchDownX
                    val dy = event.rawY - globalTouchDownY
                    val horizontalSwipe = kotlin.math.abs(dx) > dp(72) &&
                        kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.4f
                    if (horizontalSwipe && dx > 0 && !sidebarVisible) {
                        suppressConversationLongPressUntil =
                            System.currentTimeMillis() + 700L
                        sidebarView?.let { showSidebar(it) }
                        return true
                    }
                    if (horizontalSwipe && dx < 0 && sidebarVisible) {
                        suppressConversationLongPressUntil =
                            System.currentTimeMillis() + 700L
                        sidebarView?.let { hideSidebar(it) }
                        return true
                    }
                    if (kotlin.math.abs(dx) < dp(8) &&
                        kotlin.math.abs(dy) < dp(8)
                    ) {
                        hideKeyboard()
                        currentFocus?.clearFocus()
                    }
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    // =========================================================================
    // 自动语音 & 模式判断
    // =========================================================================

    /**
     * 如果外部 Intent 指定了 auto_voice=true，则在模型安装完成后自动启动语音识别。
     * 延迟 350ms 等待 UI 完全就绪。
     */
    internal fun maybeStartAutoVoice() {
        if (!pendingAutoVoice) return
        pendingAutoVoice = false
        answerText.postDelayed({ startVoiceInput() }, 350)
    }

    /**
     * 是否为助理小窗模式。
     * 默认 false，AssistActivity 覆写返回 true。
     * internal：同模块扩展函数也需要判断窗口模式。
     */
    internal open fun isAssistantWindow(): Boolean = false

    // =========================================================================
    // lateinit 初始化检查 helper（扩展函数中 ::field.isInitialized 无法直访 backing field）
    // =========================================================================

    internal fun isChatListReady() = ::chatList.isInitialized
    internal fun isChatScrollViewReady() = ::chatScrollView.isInitialized
    internal fun isConversationListReady() = ::conversationList.isInitialized
    internal fun isStatusTextReady() = ::statusText.isInitialized
    internal fun isTaskListReady() = ::taskList.isInitialized
    internal fun isAnswerTextReady() = ::answerText.isInitialized
    internal fun isAssistStatusTextReady() = ::assistStatusText.isInitialized
    internal fun isInputReady() = ::input.isInitialized
    internal fun isVoiceButtonReady() = ::voiceButton.isInitialized
    internal fun isTtsReady() = ::tts.isInitialized

    // =========================================================================
    // Companion
    // =========================================================================

    companion object {
        /** Intent Extra Key：自动启动语音识别 */
        const val EXTRA_AUTO_VOICE = "auto_voice"

        /** 录音权限请求码（MainActivity + Voice 文件共用） */
        internal const val REQUEST_RECORD_AUDIO = 1001

        /** 本地 ASR 采样率：16kHz 单声道 */
        internal const val LOCAL_ASR_SAMPLE_RATE = 16000

        /** 最小录音时长（ms），避免误判静音 */
        internal const val LOCAL_ASR_MIN_RECORDING_MS = 1_000L

        /** 静音判定阈值（ms），检测到持续静音后自动停止 */
        internal const val LOCAL_ASR_END_SILENCE_MS = 1_000L

        /** 最大录音时长（ms），防止无限录音 */
        internal const val LOCAL_ASR_MAX_RECORDING_MS = 12_000L

        /** 语音 RMS 阈值，低于此值视为静音 */
        internal const val LOCAL_ASR_SPEECH_RMS_THRESHOLD = 0.014
    }
}
