package com.baize.assistant

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button

// =============================================================================
// ThemeColors.kt — 主题颜色、dp 转换、按钮样式、圆角背景等纯 UI 工具函数。
// 所有函数不依赖 MainActivity 内部状态，可作为 Context/View 扩展独立使用。
// =============================================================================

// -----------------------------------------------------------------------------
// dp 转换：将设计稿 dp 值转为实际像素，适配不同密度屏幕。
// -----------------------------------------------------------------------------
fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density + 0.5f).toInt()

// -----------------------------------------------------------------------------
// 深色模式检测：根据系统 UI Mode 判断当前是否为夜间模式。
// -----------------------------------------------------------------------------
fun Context.isDarkMode(): Boolean {
    val mask = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    return mask == Configuration.UI_MODE_NIGHT_YES
}

// -----------------------------------------------------------------------------
// 主题色板：所有颜色统一在此管理，便于后续适配多主题。
// -----------------------------------------------------------------------------
fun Context.pageBg(): Int =
    if (isDarkMode()) Color.rgb(0, 0, 0) else Color.rgb(247, 247, 250)

fun Context.surfaceColor(): Int =
    if (isDarkMode()) Color.rgb(28, 28, 30) else Color.WHITE

fun Context.primaryText(): Int =
    if (isDarkMode()) Color.WHITE else Color.rgb(20, 20, 24)

fun Context.secondaryText(): Int =
    if (isDarkMode()) Color.rgb(174, 174, 178) else Color.rgb(110, 110, 118)

fun Context.dividerColor(): Int =
    if (isDarkMode()) Color.rgb(58, 58, 60) else Color.rgb(220, 220, 226)

fun Context.accentColor(): Int =
    if (isDarkMode()) Color.rgb(10, 132, 255) else Color.rgb(0, 122, 255)

fun Context.selectedRowColor(): Int =
    if (isDarkMode()) Color.rgb(44, 44, 46) else Color.rgb(232, 232, 237)

fun Context.composerColor(): Int =
    if (isDarkMode()) Color.rgb(38, 38, 40) else Color.rgb(232, 232, 237)

// -----------------------------------------------------------------------------
// 圆角背景：构建 GradientDrawable，支持纯色/描边两种模式。
// strokeWidth=0 表示不描边。
// -----------------------------------------------------------------------------
fun roundedBg(color: Int, radius: Int, strokeColor: Int, strokeWidth: Int): GradientDrawable {
    return GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
        if (strokeWidth > 0) {
            setStroke(strokeWidth, strokeColor)
        }
    }
}

// -----------------------------------------------------------------------------
// 按钮样式预设：封装重复的 Button 外观配置，集中维护视觉一致性。
// -----------------------------------------------------------------------------

/** 主操作按钮：白字蓝底，用于"发送""语音"等核心操作。 */
fun Button.stylePrimaryButton() {
    setTextColor(Color.WHITE)
    textSize = 15f
    isAllCaps = false
    background = roundedBg(context.accentColor(), context.dp(14), Color.TRANSPARENT, 0)
}

/** 次要按钮：深色文字 + 浅底描边，用于测试按钮等辅助操作。 */
fun Button.styleSecondaryButton() {
    setTextColor(context.primaryText())
    textSize = 15f
    isAllCaps = false
    background = roundedBg(context.surfaceColor(), context.dp(14), context.dividerColor(), context.dp(1))
}

/** 文字链接按钮：蓝色无背景，用于轻量操作入口。 */
fun Button.styleTextButton() {
    setTextColor(context.accentColor())
    textSize = 14f
    isAllCaps = false
    background = roundedBg(Color.TRANSPARENT, context.dp(12), Color.TRANSPARENT, 0)
    minHeight = 0
    minimumHeight = 0
    setPadding(context.dp(10), context.dp(2), context.dp(10), context.dp(2))
}

/** 侧边栏菜单按钮：深色文字无背景，左对齐，用于设置/无障碍等入口。 */
fun Button.styleDarkMenuButton() {
    setTextColor(context.primaryText())
    textSize = 16f
    isAllCaps = false
    gravity = Gravity.CENTER_VERTICAL or Gravity.START
    background = roundedBg(Color.TRANSPARENT, context.dp(12), Color.TRANSPARENT, 0)
    setPadding(context.dp(10), context.dp(8), context.dp(10), context.dp(8))
}

/** 纯图标按钮：如 ☰ 菜单键，居中对齐无背景。 */
fun Button.stylePlainIconButton() {
    setTextColor(context.primaryText())
    textSize = 24f
    isAllCaps = false
    includeFontPadding = false
    gravity = Gravity.CENTER
    background = roundedBg(Color.TRANSPARENT, context.dp(14), Color.TRANSPARENT, 0)
    minWidth = context.dp(44)
    minimumWidth = context.dp(44)
    minHeight = context.dp(44)
    minimumHeight = context.dp(44)
    setPadding(0, 0, 0, 0)
}

/** 助理窗口图标按钮：可自定义文字颜色。 */
fun Button.styleAssistIconButton(textColor: Int) {
    setTextColor(textColor)
    textSize = 24f
    isAllCaps = false
    background = roundedBg(Color.TRANSPARENT, context.dp(20), Color.TRANSPARENT, 0)
    minWidth = context.dp(42)
    minimumWidth = context.dp(42)
    setPadding(0, 0, 0, 0)
}

/** 助理窗口主操作按钮：深色模式白底，浅色模式蓝底，用于"语音/发送"切换。 */
fun Button.styleAssistPrimaryButton() {
    val darkMode = context.isDarkMode()
    setTextColor(if (darkMode) Color.BLACK else Color.WHITE)
    textSize = 18f
    isAllCaps = false
    includeFontPadding = false
    gravity = Gravity.CENTER
    background = roundedBg(
        if (darkMode) Color.WHITE else context.accentColor(),
        context.dp(24),
        Color.TRANSPARENT,
        0
    )
    minWidth = context.dp(52)
    minimumWidth = context.dp(52)
    minHeight = context.dp(44)
    minimumHeight = context.dp(44)
    setPadding(context.dp(12), 0, context.dp(12), 0)
}
