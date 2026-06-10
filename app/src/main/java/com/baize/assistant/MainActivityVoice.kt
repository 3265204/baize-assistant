package com.baize.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.Toast
import java.util.Collections

// =============================================================================
// MainActivityVoice.kt — 语音识别相关扩展函数。
// 支持两种引擎：
//   1. 系统 SpeechRecognizer（在线/离线混合）
//   2. 本地 sherpa-onnx Paraformer（AudioRecord 采集 PCM → 本地推理）
// 同时管理助理小窗的"忙"状态（Busy State）。
// =============================================================================

// -----------------------------------------------------------------------------
// 语音录入入口：根据设置选择系统识别或本地 Paraformer。
// -----------------------------------------------------------------------------
internal fun MainActivity.startVoiceInput() {
    // 问候语播放期间不打断 TTS（playGreetingThenListen 中边播边录）
    if (greetingTextForFilter == null) stopTtsPlayback()
    if (isAssistantWindow() && assistWindowBusy) return

    // 检查录音权限
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MainActivity.REQUEST_RECORD_AUDIO)
        return
    }

    // 清空小窗回答区
    if (isAssistantWindow() && isAnswerTextReady()) {
        answerText.visibility = View.GONE
        answerText.text = ""
    }

    discardCurrentVoiceInput = false

    if (settingsStore.load().speechEngine == DeepSeekSettings.SPEECH_ENGINE_LOCAL) {
        toggleLocalAsrRecording()
    } else {
        startSystemVoiceInput()
    }
}

// -----------------------------------------------------------------------------
// 系统语音识别（SpeechRecognizer）。
// 使用 EXTRA_PREFER_OFFLINE 优先离线，降级到在线。
// -----------------------------------------------------------------------------
internal fun MainActivity.startSystemVoiceInput() {
    if (!SpeechRecognizer.isRecognitionAvailable(this)) {
        val message = "当前系统没有可用的语音识别服务。建议在设置里启用本地高准确识别。"
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        answerText.text = message
        setAssistStatus("没有可用的系统识别")
        clearAssistBusy("语音不可用")
        speak(message)
        return
    }
    if (isListening) return

    val settings = settingsStore.load()
    speechRecognizer?.destroy()
    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
        setRecognitionListener(createRecognitionListener())
    }

    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, settings.speechLocale)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, settings.speechLocale)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PROMPT, "请说出你的任务")
    }

    isListening = true
    setAssistStatus("正在听")
    setAssistBusy("正在倾听", canStopListening = true)
    answerText.text = "正在听..."
    speechRecognizer?.startListening(intent)
}

// -----------------------------------------------------------------------------
// RecognitionListener：处理系统识别的各阶段回调。
// 识别成功后自动填入输入框并触发 handleUserInput。
// -----------------------------------------------------------------------------
internal fun MainActivity.createRecognitionListener(): RecognitionListener {
    return object : RecognitionListener {
        override fun onReadyForSpeech(params: android.os.Bundle?) {
            setAssistStatus("正在听")
            setAssistBusy("正在倾听", canStopListening = true)
            answerText.text = "请开始说话..."
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            setAssistStatus("正在识别")
            setAssistBusy("正在识别")
            answerText.text = "正在识别..."
        }

        override fun onError(error: Int) {
            isListening = false
            if (discardCurrentVoiceInput) {
                discardCurrentVoiceInput = false
                clearAssistBusy()
                return
            }
            setAssistStatus("识别失败")
            clearAssistBusy("识别失败，请再试")
            val message = "语音识别失败：${speechErrorText(error)}"
            answerText.text = message
            speak(message)
        }

        override fun onResults(results: android.os.Bundle?) {
            isListening = false
            if (discardCurrentVoiceInput) {
                discardCurrentVoiceInput = false
                clearAssistBusy()
                return
            }
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isBlank()) {
                setAssistStatus("没有识别到文字")
                clearAssistBusy("没听清，请再试")
                answerText.text = "没有识别到有效语音。"
                speak("没有识别到有效语音。")
                return
            }
            setAssistStatus("识别完成")
            clearAssistBusy()
            input.setText(text)
            input.setSelection(text.length)
            updateAssistActionButton()
            handleUserInput()
        }

        override fun onPartialResults(partialResults: android.os.Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotBlank() && !isAssistantWindow()) {
                input.setText(text)
                input.setSelection(text.length)
            }
        }

        override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
    }
}

// -----------------------------------------------------------------------------
// 本地轻量识别（sherpa-onnx Paraformer）。
// 流程：AudioRecord 采集 16kHz PCM → 静音检测自动停止 → FloatArray 送 LocalSherpaAsr。
// 相比系统 SpeechRecognizer，无需联网、延迟更低、更适合作为默认引擎。
// -----------------------------------------------------------------------------

/** 切换本地录音状态：正在录则停止并识别，否则开始录制。 */
internal fun MainActivity.toggleLocalAsrRecording() {
    if (isLocalRecording) {
        stopLocalAsrRecording()
    } else {
        startLocalAsrRecording()
    }
}

/** 开始本地录音：初始化 AudioRecord，启动后台线程循环读取 PCM 数据。 */
internal fun MainActivity.startLocalAsrRecording() {
    val minBuffer = AudioRecord.getMinBufferSize(
        MainActivity.LOCAL_ASR_SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )
    if (minBuffer <= 0) {
        answerText.text = "无法初始化本地录音缓冲区。"
        clearAssistBusy("录音失败")
        speak("无法初始化本地录音缓冲区。")
        return
    }

    localPcmChunks.clear()
    localRecorder = AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MainActivity.LOCAL_ASR_SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        minBuffer * 2
    )

    if (localRecorder?.state != AudioRecord.STATE_INITIALIZED) {
        localRecorder?.release()
        localRecorder = null
        answerText.text = "本地录音初始化失败，请确认麦克风权限。"
        clearAssistBusy("录音失败")
        speak("本地录音初始化失败，请确认麦克风权限。")
        return
    }

    isLocalRecording = true
    setAssistStatus("正在听，说完后会自动停止")
    setAssistBusy("正在倾听", canStopListening = true)
    answerText.text = "正在本地录音，停顿约 1 秒后自动停止..."
    localRecorder?.startRecording()

    val settings = settingsStore.load()
    localRecordingThread = Thread {
        val buffer = ShortArray(minBuffer)
        val startedAt = System.currentTimeMillis()
        var hasSpeech = false
        var silentChunksAfterSpeech = 0

        while (isLocalRecording) {
            val read = localRecorder?.read(buffer, 0, buffer.size) ?: 0
            if (read > 0) {
                val chunk = buffer.copyOf(read)
                localPcmChunks.add(chunk)

                // RMS 音量检测：超过阈值认为有语音
                val rms = calculateRms(chunk)
                if (rms > MainActivity.LOCAL_ASR_SPEECH_RMS_THRESHOLD) {
                    hasSpeech = true
                    silentChunksAfterSpeech = 0
                } else if (hasSpeech) {
                    silentChunksAfterSpeech++
                }

                val elapsed = System.currentTimeMillis() - startedAt
                val silenceMs = silentChunksAfterSpeech * read * 1000 / MainActivity.LOCAL_ASR_SAMPLE_RATE

                // 动态静音阈值：问候模式下 TTS 仍在播放，拉长阈值防误停
                val endSilenceMs = if (greetingModeUntilMs > 0 &&
                    System.currentTimeMillis() < greetingModeUntilMs) {
                    5_000L // 问候模式：5 秒静音才停止
                } else {
                    MainActivity.LOCAL_ASR_END_SILENCE_MS // 正常：1 秒
                }

                // 自动停止条件：
                // 1. 检测到语音后静音超过阈值
                // 2. 最大录音时长（默认 12s）
                val shouldStopForSilence = settings.localAsrAutoStop &&
                    hasSpeech &&
                    elapsed >= MainActivity.LOCAL_ASR_MIN_RECORDING_MS &&
                    silenceMs >= endSilenceMs
                val shouldStopForMaxDuration = elapsed >= MainActivity.LOCAL_ASR_MAX_RECORDING_MS

                if (shouldStopForSilence || shouldStopForMaxDuration) {
                    isLocalRecording = false
                    runOnUiThread { stopLocalAsrRecording() }
                    break
                }
            }
        }
    }.apply { start() }
}

/** 停止本地录音：释放 AudioRecord，合并 PCM 数据送 sherpa-onnx 推理。 */
internal fun MainActivity.stopLocalAsrRecording() {
    isLocalRecording = false
    voiceButton.text = if (isAssistantWindow()) "语音" else "语音输入"

    // 安全释放录音资源
    runCatching { localRecordingThread?.join(800) }
    runCatching { localRecorder?.stop() }
    localRecorder?.release()
    localRecorder = null
    localRecordingThread = null

    // 将 ShortArray 块合并为 FloatArray
    val samples = shortChunksToFloatArray(localPcmChunks.toList())
    if (samples.isEmpty()) {
        setAssistStatus("没有听到有效语音")
        clearAssistBusy("没听清，请再试")
        answerText.text = "没有录到有效语音。"
        speak("没有录到有效语音。")
        return
    }

    answerText.text = "正在本地识别..."
    setAssistStatus("正在识别")
    setAssistBusy("正在识别")

    // 后台线程执行 sherpa-onnx 推理，避免阻塞 UI
    Thread {
        val result = LocalSherpaAsr(settingsStore.load()).transcribe(samples, MainActivity.LOCAL_ASR_SAMPLE_RATE)
        runOnUiThread {
            result
                .onSuccess { rawText ->
                    // 过滤问候语前缀（录音期间 TTS 问候语被麦克风捕获）
                    val text = stripGreetingPrefix(rawText, greetingTextForFilter)
                    // 过滤完成后清除，避免影响后续录音
                    greetingTextForFilter = null
                    greetingModeUntilMs = 0

                    if (text.isBlank()) {
                        setAssistStatus("没有识别到文字")
                        clearAssistBusy("没听清，请再试")
                        answerText.text = "本地识别没有返回文本。"
                        speak("本地识别没有返回文本。")
                    } else {
                        setAssistStatus("识别完成")
                        clearAssistBusy()
                        input.setText(text)
                        input.setSelection(text.length)
                        updateAssistActionButton()
                        handleUserInput()
                    }
                }
                .onFailure {
                    setAssistStatus("识别失败")
                    clearAssistBusy("识别失败，请再试")
                    answerText.text = "本地识别失败：${it.message}"
                    speak("本地识别失败：${it.message}")
                }
        }
    }.start()
}

// -----------------------------------------------------------------------------
// 问候语模糊过滤
// -----------------------------------------------------------------------------

/**
 * 从 ASR 识别结果中剥离问候语前缀。
 *
 * 录音期间 TTS 问候语被麦克风捕获，ASR 会把"你好，我在帮我定个闹钟"识别为一整段。
 * 此函数用模糊匹配找到问候语的位置并切除，只保留用户真正说的话。
 *
 * 匹配逻辑：
 *   1. 归一化（去标点、空格）后，在识别文本中找问候语的近似起始位置
 *   2. 逐字比对，允许 ASR 误差（如"你好我在" vs "你好，我在"）
 *   3. 匹配率 > 50% 即认为找到，切除匹配部分
 *   4. 如果找不到匹配 → 返回原文（可能是用户直接开始说话，没被 TTS 影响）
 */
internal fun stripGreetingPrefix(recognized: String, greeting: String?): String {
    if (greeting.isNullOrBlank()) return recognized

    val raw = recognized.trim()
    val gNorm = greeting.replace(Regex("[，。！？、,.!?\\s]"), "") // 去标点空格

    if (gNorm.isEmpty() || raw.length < gNorm.length / 2) return raw

    // 逐字扫描：在 raw 中找 gNorm 的最佳匹配起始位置
    var bestStart = -1
    var bestMatches = 0

    for (start in 0..raw.length - gNorm.length) {
        var matches = 0
        for (j in gNorm.indices) {
            val idx = start + j
            if (idx < raw.length && raw[idx] == gNorm[j]) matches++
        }
        if (matches > bestMatches) {
            bestMatches = matches
            bestStart = start
        }
    }

    // 匹配率 > 50% → 认为找到了问候语
    return if (bestMatches.toDouble() / gNorm.length > 0.5) {
        val after = raw.substring(bestStart + gNorm.length)
        after.replace(Regex("^[，。！？、,.!?\\s]+"), "").trim()
    } else {
        raw // 没找到，返回原文
    }
}

// -----------------------------------------------------------------------------
// PCM 数据处理工具
// -----------------------------------------------------------------------------

/** 将 ShortArray 块列表合并为 sherpa-onnx 所需的 FloatArray（归一化到 [-1, 1]）。 */
internal fun shortChunksToFloatArray(chunks: List<ShortArray>): FloatArray {
    val total = chunks.sumOf { it.size }
    val output = FloatArray(total)
    var offset = 0
    for (chunk in chunks) {
        for (sample in chunk) {
            output[offset++] = sample / 32768.0f
        }
    }
    return output
}

/** 计算 PCM 数据的 RMS（均方根）值，用于静音检测。 */
internal fun calculateRms(samples: ShortArray): Double {
    if (samples.isEmpty()) return 0.0
    var sum = 0.0
    for (sample in samples) {
        val value = sample.toDouble()
        sum += value * value
    }
    return kotlin.math.sqrt(sum / samples.size) / Short.MAX_VALUE
}

/** 将 SpeechRecognizer 错误码转为中文可读描述。 */
internal fun speechErrorText(error: Int): String {
    return when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "录音错误"
        SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少麦克风权限"
        SpeechRecognizer.ERROR_NETWORK -> "网络错误，离线包可能不可用"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时，离线包可能不可用"
        SpeechRecognizer.ERROR_NO_MATCH -> "没有匹配结果"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务忙"
        SpeechRecognizer.ERROR_SERVER -> "识别服务错误"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有检测到语音"
        else -> "错误码 $error"
    }
}

// -----------------------------------------------------------------------------
// 助理窗口忙状态管理（仅 AssistActivity 使用）
// 忙状态下锁定输入框和按钮，防止重复提交。
// -----------------------------------------------------------------------------

/** 设置助理窗口状态提示文案。 */
internal fun MainActivity.setAssistStatus(message: String) {
    if (isAssistantWindow() && isAssistStatusTextReady()) {
        assistStatusText.text = message
    }
}

/**
 * 进入"忙"状态：锁定输入框、禁用发送按钮。
 * @param message 输入框 placeholder 文案
 * @param canStopListening 是否允许通过点击输入栏中断当前倾听
 */
internal fun MainActivity.setAssistBusy(message: String, canStopListening: Boolean = false) {
    if (!isAssistantWindow() || !isInputReady() || !isVoiceButtonReady()) return
    assistWindowBusy = true
    input.text.clear()
    input.hint = message
    input.isEnabled = canStopListening
    input.isFocusable = false
    input.isFocusableInTouchMode = false
    input.isCursorVisible = false
    input.setOnClickListener {
        if (canStopListening) switchAssistListeningToTyping()
    }
    input.alpha = 0.72f
    voiceButton.visibility = View.GONE
    voiceButton.isEnabled = false
    voiceButton.alpha = 1f
}

/** 中断当前语音监听（仅小窗模式）。 */
internal fun MainActivity.stopAssistListeningIfNeeded() {
    if (!isAssistantWindow()) return
    discardCurrentVoiceInput = true
    when {
        isLocalRecording -> cancelLocalAsrRecording()
        isListening -> {
            isListening = false
            speechRecognizer?.cancel()
            clearAssistBusy()
        }
    }
}

/** 正在倾听时切换到键盘输入：点击"正在倾听"输入栏会走这里。 */
internal fun MainActivity.switchAssistListeningToTyping() {
    if (!isAssistantWindow()) return
    stopTtsPlayback()
    stopAssistListeningIfNeeded()
    if (isInputReady()) {
        input.requestFocus()
        input.post { showKeyboard(input) }
    }
}

/** 安全取消本地录音（不进行识别）。 */
internal fun MainActivity.cancelLocalAsrRecording() {
    isLocalRecording = false
    runCatching { localRecordingThread?.join(800) }
    runCatching { localRecorder?.stop() }
    localRecorder?.release()
    localRecorder = null
    localRecordingThread = null
    localPcmChunks.clear()
    clearAssistBusy()
}

/** 清除忙状态，恢复正常交互。 */
internal fun MainActivity.clearAssistBusy(nextHint: String = "") {
    if (!isAssistantWindow() || !isInputReady() || !isVoiceButtonReady()) return
    assistWindowBusy = false
    input.hint = nextHint
    input.isEnabled = true
    input.isFocusable = true
    input.isFocusableInTouchMode = true
    input.isCursorVisible = true
    input.setOnClickListener(null)
    input.alpha = 1f
    voiceButton.visibility = View.VISIBLE
    voiceButton.isEnabled = true
    voiceButton.alpha = 1f
    updateAssistActionButton()
}

/** 根据输入框内容切换按钮文案：空→"语音"，有文字→"发送"。 */
internal fun MainActivity.updateAssistActionButton() {
    if (!isAssistantWindow() || !isVoiceButtonReady() || !isInputReady()) return
    if (assistWindowBusy) return
    voiceButton.text = if (input.text.toString().trim().isEmpty()) "语音" else "发送"
}
