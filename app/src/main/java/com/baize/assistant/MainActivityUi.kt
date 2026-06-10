package com.baize.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// =============================================================================
// MainActivityUi.kt — MainActivity 的 UI 构建扩展函数。
// 包含：主界面 UI、助理小窗 UI、键盘自适应、侧边栏动画。
// =============================================================================

// -----------------------------------------------------------------------------
// 主界面 UI 构建（首页）。
// 布局结构：root → stage(LinearLayout.HORIZONTAL) → [sidebar | content]
// sidebar 通过 translationX 动画滑入/滑出。
// -----------------------------------------------------------------------------
internal fun MainActivity.buildUi() {
    if (isAssistantWindow()) {
        buildAssistBarUiV2()
        return
    }

    val bg = pageBg()
    val primaryText = primaryText()
    val secondaryText = secondaryText()
    val surface = surfaceColor()

    // ===== root: 最外层容器 =====
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(28), 0, dp(18))
        setBackgroundColor(bg)
        isClickable = true
        isFocusableInTouchMode = true
    }

    // ===== stage: 水平滑动容器，承载侧边栏 + 主内容区 =====
    val stage = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        translationX = -sidebarWidth().toFloat()
        setPadding(0, 0, 0, 0)
    }
    mainStageView = stage

    // ===== content: 主内容区 =====
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(bg)
        setPadding(dp(16), 0, dp(16), 0)
    }

    // ---- 顶部栏：菜单按钮 + 标题 ----
    val topBar = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(48)
    }

    val menuButton = Button(this).apply {
        text = "☰"
        stylePlainIconButton()
    }

    val title = TextView(this).apply {
        text = "Hello"
        textSize = 28f
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(primaryText)
        layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
    }
    topBar.addView(menuButton)
    topBar.addView(title)

    // ---- 状态区：展示各核心模块配置状态 ----
    statusText = TextView(this).apply {
        textSize = 14f
        setTextColor(secondaryText)
        setPadding(0, dp(10), 0, dp(12))
    }

    // ---- 输入框：语音识别结果会回填到这里 ----
    input = EditText(this).apply {
        hint = "有问题，尽管问"
        minLines = 1
        maxLines = 3
        textSize = 16f
        gravity = Gravity.CENTER_VERTICAL
        includeFontPadding = false
        setPadding(dp(14), 0, dp(14), 0)
        setTextColor(primaryText())
        setHintTextColor(secondaryText())
        background = null
        imeOptions = EditorInfo.IME_ACTION_SEND
        setSingleLine(true)
        setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                handleUserInput()
                true
            } else {
                false
            }
        }
    }

    // ---- 操作按钮 ----
    val askButton = Button(this).apply {
        text = "发送"
        styleAssistPrimaryButton()
        setOnClickListener { handleUserInput() }
    }

    voiceButton = Button(this).apply {
        text = "语音"
        styleAssistPrimaryButton()
        setOnClickListener { startVoiceInput() }
    }

    val settingsButton = Button(this).apply {
        text = "设置"
        styleDarkMenuButton()
        setOnClickListener { showSettingsDialog() }
    }

    val accessibilityButton = Button(this).apply {
        text = "打开无障碍设置"
        styleDarkMenuButton()
        setOnClickListener {
            startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    val defaultAssistantButton = Button(this).apply {
        text = "设置为默认助理"
        styleDarkMenuButton()
        setOnClickListener { openDefaultAssistantSettings() }
    }

    val testAlarmButton = Button(this).apply {
        text = "测试：2 分钟后系统闹钟"
        styleSecondaryButton()
        setOnClickListener {
            val scheduledAt = java.time.OffsetDateTime.now().plusMinutes(2)
            openSystemAlarm("Assistant MVP 测试闹钟", scheduledAt)
            saveTask("alarm", "Assistant MVP 测试闹钟", "系统时钟", scheduledAt)
            speak("已打开系统时钟创建测试闹钟")
        }
    }

    val testReminderButton = Button(this).apply {
        text = "测试：5 分钟后系统日程"
        styleSecondaryButton()
        setOnClickListener {
            val scheduledAt = java.time.OffsetDateTime.now().plusMinutes(5)
            openSystemReminder("Assistant MVP 系统日程", "由 Assistant MVP 创建", scheduledAt)
            saveTask("reminder", "Assistant MVP 系统日程", "系统日历", scheduledAt)
            speak("已打开系统日历创建测试日程")
        }
    }

    // ---- 回答区 ----
    answerText = TextView(this).apply {
        textSize = 15f
        setTextColor(secondaryText)
        setPadding(0, dp(10), 0, dp(6))
    }

    // ---- 对话区标题 ----
    val chatHeader = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(18), 0, dp(8))
    }
    val chatTitle = TextView(this).apply {
        text = "对话"
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(primaryText)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    chatHeader.addView(chatTitle)

    // ===== 侧边栏：聊天历史、设置入口 =====
    val sidebar = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBg(surface, dp(20), dividerColor(), dp(1))
        setPadding(dp(16), 0, dp(16), dp(12))
        visibility = View.VISIBLE
    }
    sidebarView = sidebar

    val historyTopBar = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(48)
    }
    val closeHistoryButton = Button(this).apply {
        text = "☰"
        stylePlainIconButton()
        setOnClickListener { hideSidebar(sidebar) }
    }
    val historyTitle = TextView(this).apply {
        text = "历史"
        textSize = 28f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(primaryText)
        includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
    }
    historyTopBar.addView(closeHistoryButton)
    historyTopBar.addView(historyTitle)

    val newChatButton = Button(this).apply {
        text = "新聊天"
        styleDarkMenuButton()
        setOnClickListener {
            selectEmptyFirstConversationOrCreate()
            hideSidebar(sidebar)
            refreshConversations()
            refreshChat()
        }
    }
    val searchChatButton = Button(this).apply {
        text = "搜索聊天"
        styleDarkMenuButton()
        setOnClickListener { showConversationSearch() }
    }
    val recentLabel = TextView(this).apply {
        text = "最近"
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(primaryText)
        setPadding(0, dp(14), 0, dp(6))
    }

    conversationList = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }
    val conversationScrollView = ScrollView(this).apply {
        isFillViewport = false
        addView(
            conversationList,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
    }

    sidebar.addView(historyTopBar)
    sidebar.addView(newChatButton)
    sidebar.addView(searchChatButton)
    sidebar.addView(settingsButton)
    sidebar.addView(defaultAssistantButton)
    sidebar.addView(accessibilityButton)
    sidebar.addView(recentLabel)
    sidebar.addView(conversationScrollView, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
    ))

    // ===== 聊天消息列表 =====
    chatList = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBg(bg, dp(18), Color.TRANSPARENT, 0)
        setPadding(dp(10), dp(10), dp(10), dp(10))
    }
    chatScrollView = ScrollView(this).apply {
        setBackgroundColor(bg)
        addView(chatList, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    // ===== 最近任务区（保留但暂不在主列表渲染，由 refreshTasks 负责） =====
    taskList = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }

    // ---- 触发侧边栏 ----
    menuButton.setOnClickListener { showSidebar(sidebar) }

    // ---- 输入组合器：输入框 + 语音/发送按钮 ----
    val composerActions = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(voiceButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(8) })
        addView(askButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(8) })
    }

    val composer = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        setPadding(dp(10), dp(6), dp(8), dp(6))
        background = roundedBg(composerColor(), dp(28), Color.TRANSPARENT, 0)
        isClickable = true
        setOnClickListener {
            input.requestFocus()
            showKeyboard(input)
        }
        addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        addView(composerActions)
    }

    // ---- 组装 ----
    content.addView(topBar)
    content.addView(chatHeader)
    content.addView(chatScrollView, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
    ))
    content.addView(answerText)
    content.addView(composer)

    stage.addView(sidebar, LinearLayout.LayoutParams(sidebarWidth(), ViewGroup.LayoutParams.MATCH_PARENT))
    stage.addView(content, LinearLayout.LayoutParams(
        resources.displayMetrics.widthPixels, ViewGroup.LayoutParams.MATCH_PARENT
    ))

    root.addView(stage, LinearLayout.LayoutParams(
        sidebarWidth() + resources.displayMetrics.widthPixels, 0, 1f
    ))

    // 键盘弹起时动态调整 root 底部 padding
    installKeyboardLift(root)

    setContentView(root)
}

// -----------------------------------------------------------------------------
// 助理小窗 UI（AssistActivity 使用时渲染的紧凑浮动面板）。
// 跟随系统深浅色主题，底部固定一个输入栏 + 语音/发送切换按钮。
// -----------------------------------------------------------------------------
internal fun MainActivity.buildAssistBarUiV2() {
    val darkMode = isDarkMode()
    val barColor = if (darkMode) Color.BLACK else surfaceColor()
    val textColor = primaryText()
    val hintColor = secondaryText()
    val panelStroke = if (darkMode) Color.TRANSPARENT else dividerColor()
    val panelStrokeWidth = if (darkMode) 0 else dp(1)

    // 回答展示区（初始隐藏，有结果后显示）
    answerText = TextView(this).apply {
        text = ""
        textSize = 16f
        setTextColor(textColor)
        setLineSpacing(dp(2).toFloat(), 1.0f)
        setPadding(dp(18), dp(18), dp(18), dp(16))
        visibility = View.GONE
    }

    // 状态提示（"正在思考""正在倾听"等）
    assistStatusText = TextView(this).apply {
        text = ""
        textSize = 12f
        setTextColor(secondaryText())
        visibility = View.GONE
    }

    // 输入框
    input = EditText(this).apply {
        hint = ""
        setSingleLine(true)
        textSize = 18f
        gravity = Gravity.CENTER_VERTICAL
        includeFontPadding = false
        setPadding(dp(14), 0, dp(14), 0)
        setTextColor(textColor)
        setHintTextColor(hintColor)
        background = null
        imeOptions = EditorInfo.IME_ACTION_SEND
        setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                if (assistWindowBusy) return@setOnEditorActionListener true
                handleUserInput()
                true
            } else false
        }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    // 操作按钮：空输入显示"语音"，有文字显示"发送"，忙时不可用
    voiceButton = Button(this).apply {
        text = "语音"
        styleAssistPrimaryButton()
        setOnClickListener {
            if (assistWindowBusy) {
                switchAssistListeningToTyping()
                return@setOnClickListener
            }
            if (input.text.toString().trim().isEmpty()) {
                startVoiceInput()
            } else {
                handleUserInput()
            }
        }
    }

    // 监听输入变化，动态切换按钮文案
    input.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            updateAssistActionButton()
        }
        override fun afterTextChanged(s: android.text.Editable?) = Unit
    })
    updateAssistActionButton()

    // 输入栏
    val bar = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        setPadding(dp(10), dp(6), dp(8), dp(6))
        isClickable = true
        setOnClickListener {
            if (assistWindowBusy && (isListening || isLocalRecording)) {
                switchAssistListeningToTyping()
            } else if (!assistWindowBusy) {
                input.requestFocus()
                showKeyboard(input)
            }
        }
        addView(input)
        addView(voiceButton)
    }

    // 面板：回答 + 状态 + 输入栏
    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBg(barColor, dp(28), panelStroke, panelStrokeWidth)
        isClickable = true
        setOnClickListener {
            if (assistWindowBusy && (isListening || isLocalRecording)) {
                switchAssistListeningToTyping()
            }
        }
        addView(answerText)
        addView(assistStatusText)
        addView(bar)
    }

    // 外层包装：透明背景 + 底部内边距
    val wrapper = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), 0, dp(8), dp(10))
        setBackgroundColor(Color.TRANSPARENT)
        addView(panel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }

    setContentView(wrapper)
}

// -----------------------------------------------------------------------------
// 键盘自适应：键盘弹起时增大 root 底部 padding，避免输入框被遮挡。
// 使用 ValueAnimator 平滑过渡，避免突兀跳动。
// -----------------------------------------------------------------------------
internal fun MainActivity.installKeyboardLift(root: LinearLayout) {
    val baseLeft = 0
    val baseTop = dp(28)
    val baseRight = 0
    val baseBottom = dp(18)
    var lastBottomPadding = baseBottom
    var paddingAnimator: ValueAnimator? = null

    root.viewTreeObserver.addOnGlobalLayoutListener {
        val visibleFrame = Rect()
        root.getWindowVisibleDisplayFrame(visibleFrame)
        val totalHeight = root.rootView.height
        val keyboardHeight = totalHeight - visibleFrame.bottom
        val bottomPadding = if (keyboardHeight > dp(120)) {
            keyboardHeight + dp(8)
        } else {
            baseBottom
        }
        if (lastBottomPadding != bottomPadding) {
            paddingAnimator?.cancel()
            paddingAnimator = ValueAnimator.ofInt(lastBottomPadding, bottomPadding).apply {
                duration = 180L
                addUpdateListener { animator ->
                    val value = animator.animatedValue as Int
                    root.setPadding(baseLeft, baseTop, baseRight, value)
                }
                start()
            }
            lastBottomPadding = bottomPadding
        }
    }
}

// -----------------------------------------------------------------------------
// 键盘显示/隐藏
// -----------------------------------------------------------------------------
internal fun MainActivity.hideKeyboard() {
    val view = currentFocus ?: window.decorView
    val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    manager.hideSoftInputFromWindow(view.windowToken, 0)
}

internal fun MainActivity.showKeyboard(view: View) {
    val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    manager.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
}

// -----------------------------------------------------------------------------
// 侧边栏滑入/滑出动画。
// 通过 translationX 在 100ms 内完成，手感轻快。
// -----------------------------------------------------------------------------
internal fun MainActivity.showSidebar(sidebar: View) {
    sidebarVisible = true
    val stage = mainStageView ?: sidebar
    stage.animate().cancel()
    sidebar.visibility = View.VISIBLE
    stage.animate()
        .translationX(0f)
        .setDuration(100L)
        .start()
}

internal fun MainActivity.hideSidebar(sidebar: View) {
    sidebarVisible = false
    val stage = mainStageView ?: sidebar
    stage.animate().cancel()
    stage.animate()
        .translationX(-sidebarWidth().toFloat())
        .setDuration(100L)
        .start()
}

/** 侧边栏宽度 = 屏幕宽度，全屏覆盖。 */
internal fun MainActivity.sidebarWidth(): Int =
    resources.displayMetrics.widthPixels
