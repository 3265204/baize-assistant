package com.baize.assistant

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout

import android.widget.ScrollView
import android.widget.TextView
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

// =============================================================================
// MainActivityChat.kt — 对话/会话管理 + 状态/任务刷新 + 设置弹窗。
// 涵盖：聊天消息展示、会话列表、搜索/删除、状态摘要、任务列表、设置表单。
// =============================================================================

// -----------------------------------------------------------------------------
// 聊天消息展示
// -----------------------------------------------------------------------------

/** 刷新聊天消息列表：从 ChatStore 读取当前会话所有消息并渲染。 */
internal fun MainActivity.refreshChat() {
    if (!isChatListReady()) return
    chatList.removeAllViews()
    val messages = chatStore.load()
    if (messages.isEmpty()) {
        chatList.addView(TextView(this).apply {
            text = "还没有对话"
            textSize = 15f
            setTextColor(secondaryText())
            setPadding(dp(8), dp(8), dp(8), dp(8))
        })
        return
    }
    for (message in messages) {
        chatList.addView(createMessageBubble(message))
    }
    if (isChatScrollViewReady()) {
        chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) }
    }
}

/**
 * 创建单条消息气泡 View。
 * - user 消息：蓝底白字，右对齐
 * - assistant 消息：白底（或深色底）+ 深色文字，左对齐
 * 长按可删除。
 */
@SuppressLint("ClickableViewAccessibility")
internal fun MainActivity.createMessageBubble(message: ChatMessage): View {
    val isUser = message.role == "user"
    return TextView(this).apply {
        text = message.content
        textSize = 15f
        setTextColor(if (isUser) Color.WHITE else primaryText())
        background = roundedBg(
            if (isUser) accentColor() else surfaceColor(),
            dp(16),
            if (isUser) Color.TRANSPARENT else dividerColor(),
            if (isUser) 0 else dp(1)
        )
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setOnLongClickListener {
            confirmDeleteMessage(message)
            true
        }
        layoutParams = LinearLayout.LayoutParams(
            (resources.displayMetrics.widthPixels * if (isAssistantWindow()) 0.72 else 0.78).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = if (isUser) Gravity.END else Gravity.START
            topMargin = dp(5)
            bottomMargin = dp(5)
        }
    }
}

/**
 * 添加一条聊天消息。
 * - 主界面：写入 ChatStore 并刷新
 * - 助理小窗：写入内存 buffer（最多保留 12 条），同步持久化到隐藏会话
 */
internal fun MainActivity.addChatMessage(role: String, content: String) {
    val message = ChatMessage(
        id = UUID.randomUUID().toString(),
        role = role,
        content = content,
        createdAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    )
    if (isAssistantWindow()) {
        assistantWindowMessages.add(message)
        while (assistantWindowMessages.size > 12) {
            assistantWindowMessages.removeAt(0)
        }
        if (assistantWindowConversationId.isBlank()) {
            assistantWindowConversationId = chatStore.createConversation("助理小窗口").id
        }
        chatStore.addToConversation(assistantWindowConversationId, message)
        return
    }

    chatStore.add(message)
    refreshConversations()
    if (isChatListReady()) refreshChat()
}

/** 获取当前上下文消息：主界面从 ChatStore，小窗从内存 buffer。 */
internal fun MainActivity.currentContextMessages(): List<ChatMessage> {
    return if (isAssistantWindow()) {
        assistantWindowMessages.toList()
    } else {
        chatStore.load()
    }
}

// -----------------------------------------------------------------------------
// 消息/会话删除确认
// -----------------------------------------------------------------------------

/** 确认清空当前会话所有消息。 */
internal fun MainActivity.confirmClearChat() {
    android.app.AlertDialog.Builder(this)
        .setTitle("清空对话")
        .setMessage("删除当前对话记录？")
        .setPositiveButton("删除") { _, _ ->
            chatStore.clearCurrentConversation()
            refreshConversations()
            refreshChat()
        }
        .setNegativeButton("取消", null)
        .show()
}

/** 确认删除单条消息。 */
internal fun MainActivity.confirmDeleteMessage(message: ChatMessage) {
    android.app.AlertDialog.Builder(this)
        .setTitle("删除消息")
        .setMessage("删除这条消息？")
        .setPositiveButton("删除") { _, _ ->
            chatStore.deleteMessage(message.id)
            refreshConversations()
            refreshChat()
        }
        .setNegativeButton("取消", null)
        .show()
}

// -----------------------------------------------------------------------------
// 会话列表管理
// -----------------------------------------------------------------------------

/**
 * 刷新侧边栏会话列表。
 * @param filter 搜索关键词，为空则显示全部。
 */
internal fun MainActivity.refreshConversations(filter: String = "") {
    if (!isConversationListReady()) return
    conversationList.removeAllViews()
    val currentId = chatStore.currentConversationId()
    val conversations = chatStore.conversations().filter {
        filter.isBlank() || it.title.contains(filter, ignoreCase = true)
    }
    if (conversations.isEmpty()) {
        conversationList.addView(TextView(this).apply {
            text = "没有匹配聊天"
            textSize = 14f
            setTextColor(Color.rgb(170, 176, 190))
            setPadding(dp(10), dp(8), dp(10), dp(8))
        })
        return
    }
    for (conversation in conversations) {
        conversationList.addView(
            createConversationRow(conversation, conversation.id == currentId)
        )
    }
}

/**
 * 启动时选择会话：优先选第一条空会话，没有则创建新会话。
 * 避免直接进入空白页。
 */
internal fun MainActivity.selectEmptyFirstConversationOrCreate() {
    val first = chatStore.conversations().firstOrNull()
    if (first != null && chatStore.load(first.id).isEmpty()) {
        chatStore.selectConversation(first.id)
    } else {
        chatStore.createConversation("新聊天")
    }
}

/**
 * 创建单条会话行 View。
 * 支持：点击切换、长按删除、滑动冲突抑制。
 */
@SuppressLint("ClickableViewAccessibility")
internal fun MainActivity.createConversationRow(
    conversation: ChatConversation,
    selected: Boolean
): View {
    var rowDownX = 0f
    var rowDownY = 0f
    var rowMovedHorizontally = false
    return TextView(this).apply {
        text = conversation.title
        textSize = 15f
        setTextColor(primaryText())
        maxLines = 1
        background = roundedBg(
            if (selected) selectedRowColor() else Color.TRANSPARENT,
            dp(12), Color.TRANSPARENT, 0
        )
        setPadding(dp(10), dp(10), dp(10), dp(10))
        setOnClickListener {
            chatStore.selectConversation(conversation.id)
            sidebarView?.let { hideSidebar(it) }
            refreshConversations()
            refreshChat()
        }
        // 触摸追踪：水平滑动时抑制长按，避免误触删除
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    rowDownX = event.rawX
                    rowDownY = event.rawY
                    rowMovedHorizontally = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - rowDownX
                    val dy = event.rawY - rowDownY
                    if (kotlin.math.abs(dx) > dp(14) &&
                        kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.2f
                    ) {
                        rowMovedHorizontally = true
                        suppressConversationLongPressUntil =
                            System.currentTimeMillis() + 700L
                    }
                }
            }
            false
        }
        setOnLongClickListener {
            if (rowMovedHorizontally ||
                System.currentTimeMillis() < suppressConversationLongPressUntil
            ) {
                return@setOnLongClickListener true
            }
            confirmDeleteConversation(conversation)
            true
        }
    }
}

/**
 * 搜索聊天弹窗：输入关键词过滤会话列表。
 *
 * 视觉风格与设置弹窗统一：圆角卡片 + 标题 + 输入框 + 按钮栏。
 */
internal fun MainActivity.showConversationSearch() {
    lateinit var dialog: Dialog

    // ── 搜索输入框 ──
    val searchInput = styledEditText("搜索聊天标题", "")

    // ── 底部按钮栏 ──
    val cancelBtn = Button(this).apply {
        text = "取消"
        styleSecondaryButton()
        setOnClickListener { dialog.dismiss() }
    }
    val searchBtn = Button(this).apply {
        text = "搜索"
        stylePrimaryButton()
        setOnClickListener {
            refreshConversations(searchInput.text.toString().trim())
            dialog.dismiss()
        }
    }
    val buttonBar = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(12), 0, 0)
        addView(cancelBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(10)
        })
        addView(searchBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    // ── 标题 ──
    val title = TextView(this).apply {
        text = "搜索聊天"
        textSize = 28f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(primaryText())
        includeFontPadding = false
        setPadding(0, 0, 0, dp(14))
    }

    // ── 卡片容器 ──
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBg(surfaceColor(), dp(24), dividerColor(), dp(1))
        setPadding(dp(20), dp(20), dp(20), dp(20))
        addView(title)
        addView(searchInput)
        addView(buttonBar)
    }

    // ── Dialog 构建与显示 ──
    dialog = Dialog(this).apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(card)
        window?.apply {
            setBackgroundDrawable(roundedBg(Color.TRANSPARENT, dp(24), Color.TRANSPARENT, 0))
            setLayout(
                (resources.displayMetrics.widthPixels * 0.88).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.CENTER)
            // 键盘自动弹出
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        setCancelable(true)
        setCanceledOnTouchOutside(true)
        show()
    }
}

/** 确认删除整个会话及其消息。 */
internal fun MainActivity.confirmDeleteConversation(conversation: ChatConversation) {
    android.app.AlertDialog.Builder(this)
        .setTitle("删除聊天")
        .setMessage("删除\u201c${conversation.title}\u201d？")
        .setPositiveButton("删除") { _, _ ->
            chatStore.deleteConversation(conversation.id)
            refreshConversations()
            refreshChat()
        }
        .setNegativeButton("取消", null)
        .show()
}

// -----------------------------------------------------------------------------
// 状态摘要 & 最近任务
// -----------------------------------------------------------------------------

/**
 * 刷新首页状态摘要。
 * 展示：DeepSeek 配置、语音识别引擎、本地模型、TTS、问候语。
 */
internal fun MainActivity.refreshStatus() {
    if (!isStatusTextReady()) return
    val settings = settingsStore.load()
    val keyStatus = if (settings.apiKey.isBlank()) "未配置" else "已配置"
    val speechStatus = if (android.speech.SpeechRecognizer.isRecognitionAvailable(this)) "系统可用" else "系统不可用"
    val engineStatus = if (settings.speechEngine == DeepSeekSettings.SPEECH_ENGINE_LOCAL) {
        "本地轻量识别"
    } else {
        "系统识别"
    }
    val ttsStatus = if (settings.ttsEnabled) "开启" else "关闭"
    val ttsResponseStatus = if (settings.ttsResponseEnabled) "开启" else "关闭"
    val greetingStatus = if (settings.openingNotificationEnabled) "问候语" else "关闭"
    val localModelStatus = if (java.io.File(settings.localAsrModelPath).exists()) "已内置" else "安装中/缺失"
    statusText.text =
        "DeepSeek：$keyStatus\n" +
        "Base URL：${settings.baseUrl}\n" +
        "Model：${settings.model}\n" +
        "语音识别：$engineStatus，$speechStatus\n" +
        "本地模型：$localModelStatus\n" +
        "TTS：$ttsStatus（应答朗读：$ttsResponseStatus）\n" +
        "打开提醒：$greetingStatus"
}

/** 刷新"最近任务"列表：从 TaskStore 读取并按卡片样式渲染。 */
internal fun MainActivity.refreshTasks() {
    if (!isTaskListReady()) return
    taskList.removeAllViews()
    val tasks = taskStore.load()
    if (tasks.isEmpty()) {
        taskList.addView(TextView(this).apply { text = "暂无任务" })
        return
    }
    for (task in tasks) {
        taskList.addView(TextView(this).apply {
            text = "${typeLabel(task.type)} ${task.title}\n${task.detail}\n时间：${task.scheduledAt}\n状态：${task.status}"
            textSize = 15f
            setTextColor(Color.rgb(55, 64, 80))
            background = roundedBg(Color.WHITE, dp(14), Color.TRANSPARENT, 0)
            setPadding(dp(14), dp(10), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        })
    }
}

// -----------------------------------------------------------------------------
// 设置弹窗
// -----------------------------------------------------------------------------

/**
 * 设置对话框：集中配置 DeepSeek API、语音识别引擎、TTS、问候语等。
 *
 * 视觉风格与主界面统一：
 *   - 卡片容器：surfaceColor() 背景 + dividerColor() 描边 → 和侧边栏一致
 *   - 标题：28sp bold primaryText → 和"Hello"/"历史"一致
 *   - 输入框：composerColor() 圆角 pill → 和主页输入框一致
 *   - 复选框标签：primaryText() → 和菜单项一致
 *   - 按钮：styleSecondaryButton() 取消 + stylePrimaryButton() 保存
 *   - ScrollView 包裹 → 内容过多时可滚动
 *
 * 保存后自动应用语言设置并刷新状态摘要。
 */
internal fun MainActivity.showSettingsDialog() {
    val current = settingsStore.load()
    lateinit var dialog: Dialog

    // ── 表单控件 ──────────────────────────────────────────────
    val apiKey = styledEditText("DeepSeek API Key", current.apiKey)
    val baseUrl = styledEditText("https://api.deepseek.com", current.baseUrl)
    val model = styledEditText(DeepSeekSettings.DEFAULT_MODEL, current.model)
    val speechLocale = styledEditText("语音语言，例如 zh-CN", current.speechLocale)

    val useLocalAsr = styledCheckBox("启用本地轻量识别（sherpa-onnx Paraformer small）",
        current.speechEngine == DeepSeekSettings.SPEECH_ENGINE_LOCAL)
    val localAsrLanguage = styledEditText("本地识别语言：zh", current.localAsrLanguage)

    // ── 线程数分段选择器（替换 NumberPicker） ──
    var selectedThreads = current.localAsrNumThreads.coerceIn(1, 2)
    val option1 = TextView(this).apply {
        text = "1 线程·省电"
        textSize = 14f; gravity = Gravity.CENTER
        setPadding(0, dp(10), 0, dp(10))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    val option2 = TextView(this).apply {
        text = "2 线程·更快"
        textSize = 14f; gravity = Gravity.CENTER
        setPadding(0, dp(10), 0, dp(10))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    // 点击切换样式
    fun applySegmentState() {
        val (sel, uns) = if (selectedThreads == 1) option1 to option2 else option2 to option1
        sel.background = roundedBg(accentColor(), dp(12), Color.TRANSPARENT, 0)
        sel.setTextColor(Color.WHITE)
        uns.background = roundedBg(Color.TRANSPARENT, dp(12), Color.TRANSPARENT, 0)
        uns.setTextColor(secondaryText())
    }
    option1.setOnClickListener { selectedThreads = 1; applySegmentState() }
    option2.setOnClickListener { selectedThreads = 2; applySegmentState() }
    applySegmentState()

    val localAsrThreadsSegment = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = roundedBg(composerColor(), dp(14), Color.TRANSPARENT, 0)
        setPadding(dp(3), dp(3), dp(3), dp(3))
        addView(option1)
        addView(option2)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) }
    }

    val localAsrAutoStop = styledCheckBox("说完自动停止识别", current.localAsrAutoStop)
    val ttsEnabled = styledCheckBox("启用 TTS 朗读（总开关，关闭后完全静默）", current.ttsEnabled)
    val ttsResponseEnabled = styledCheckBox("TTS 朗读应答内容（关闭后只问候不应答）", current.ttsResponseEnabled)
    val openingEnabled = styledCheckBox("打开助理小窗时播放提示", current.openingNotificationEnabled)
    val greetingText = styledEditText("打开时说的问候语，例如：你好，我在", current.greetingText)

    // ── 表单容器 ──────────────────────────────────────────────
    val form = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(12), dp(4), 0)

        // ── DeepSeek API ──
        addView(sectionLabel("DeepSeek API"))
        addView(apiKey)
        addView(baseUrl)
        addView(model)

        // ── 语音识别 ──
        addView(sectionLabel("语音识别"))
        addView(speechLocale)
        addView(useLocalAsr)
        addView(localAsrLanguage)
        addView(widgetLabel("本地识别线程数（1 更省电，2 更快）："))
        addView(localAsrThreadsSegment)
        addView(localAsrAutoStop)

        // ── TTS 与提醒 ──
        addView(sectionLabel("TTS 朗读与提醒"))
        addView(ttsEnabled)
        addView(ttsResponseEnabled)
        addView(openingEnabled)
        addView(widgetLabel("助理问候语（支持中文、英文等任意文字）："))
        addView(greetingText)
    }

    val scroll = ScrollView(this).apply {
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(form)
    }

    // ── 底部按钮栏 ──
    val cancelBtn = Button(this).apply {
        text = "取消"
        styleSecondaryButton()
        setOnClickListener { dialog.dismiss() }
    }
    val saveBtn = Button(this).apply {
        text = "保存"
        stylePrimaryButton()
        setOnClickListener {
            settingsStore.save(
                DeepSeekSettings(
                    apiKey = apiKey.text.toString().trim(),
                    baseUrl = baseUrl.text.toString().trim()
                        .ifBlank { DeepSeekSettings.DEFAULT_BASE_URL },
                    model = model.text.toString().trim()
                        .ifBlank { DeepSeekSettings.DEFAULT_MODEL },
                    speechLocale = speechLocale.text.toString().trim()
                        .ifBlank { DeepSeekSettings.DEFAULT_SPEECH_LOCALE },
                    speechEngine = if (useLocalAsr.isChecked) {
                        DeepSeekSettings.SPEECH_ENGINE_LOCAL
                    } else {
                        DeepSeekSettings.SPEECH_ENGINE_SYSTEM
                    },
                    localAsrModelPath = modelInstaller.modelPath(),
                    localAsrLanguage = localAsrLanguage.text.toString().trim()
                        .ifBlank { DeepSeekSettings.DEFAULT_LOCAL_ASR_LANGUAGE },
                    localAsrNumThreads = selectedThreads,
                    localAsrAutoStop = localAsrAutoStop.isChecked,
                    ttsEnabled = ttsEnabled.isChecked,
                    ttsResponseEnabled = ttsResponseEnabled.isChecked,
                    openingNotificationEnabled = openingEnabled.isChecked,
                    greetingText = greetingText.text.toString().trim()
                        .ifBlank { "你好，我在" }
                )
            )
            applyTtsLocale()
            refreshStatus()
            dialog.dismiss()
        }
    }
    val buttonBar = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        setPadding(0, dp(12), 0, 0)
        addView(cancelBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(10)
        })
        addView(saveBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    // ── 标题 ──
    val title = TextView(this).apply {
        text = "设置"
        textSize = 28f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(primaryText())
        includeFontPadding = false
        setPadding(0, 0, 0, dp(10))
    }

    // ── 卡片容器 ──
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBg(surfaceColor(), dp(24), dividerColor(), dp(1))
        setPadding(dp(20), dp(16), dp(20), dp(16))
        addView(title)
        addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        addView(buttonBar)
    }

    // ── Dialog 构建与显示 ──
    dialog = Dialog(this).apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(card)
        window?.apply {
            setBackgroundDrawable(roundedBg(Color.TRANSPARENT, dp(24), Color.TRANSPARENT, 0))
            setLayout(
                (resources.displayMetrics.widthPixels * 0.92).toInt(),
                (resources.displayMetrics.heightPixels * 0.82).toInt()
            )
            setGravity(Gravity.CENTER)
        }
        setCancelable(true)
        setCanceledOnTouchOutside(true)
        show()
    }
}

// ────────────────────────────────────────────────────────────────
// 设置弹窗 — 控件样式工厂
// ────────────────────────────────────────────────────────────────

/** 分组标题：16sp bold primaryText，上方 16dp 间距 + 底部分割线。 */
private fun MainActivity.sectionLabel(label: String): TextView {
    return TextView(this).apply {
        text = label
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(primaryText())
        setPadding(0, dp(16), 0, dp(6))
    }
}

/** 控件标签：14sp secondaryText，用于 EditText/NumberPicker 上方说明。 */
private fun MainActivity.widgetLabel(label: String): TextView {
    return TextView(this).apply {
        text = label
        textSize = 14f
        setTextColor(secondaryText())
        setPadding(0, dp(8), 0, dp(4))
    }
}

/** 统一样式的 EditText：primaryText 文字、composerColor 圆角 pill 背景、14sp 字号。 */
private fun MainActivity.styledEditText(hintStr: String, valueStr: String): EditText {
    return EditText(this).apply {
        hint = hintStr
        setHintTextColor(secondaryText())
        setText(valueStr)
        setTextColor(primaryText())
        textSize = 14f
        includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(44)
        setPadding(dp(14), 0, dp(14), 0)
        background = roundedBg(composerColor(), dp(14), Color.TRANSPARENT, 0)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(6) }
    }
}

/** 统一样式的 CheckBox：primaryText 标签、14sp 字号、统一内边距。 */
private fun MainActivity.styledCheckBox(label: String, isChecked: Boolean): CheckBox {
    return CheckBox(this).apply {
        text = label
        setTextColor(primaryText())
        textSize = 14f
        this.isChecked = isChecked
        setPadding(dp(4), dp(6), dp(4), dp(2))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
}
