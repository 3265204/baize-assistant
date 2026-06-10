package com.baize.assistant

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Toast
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

// =============================================================================
// MainActivityResult.kt — DeepSeek 结果处理 + 系统 Intent + TTS + 工具函数。
// 包含：handleUserInput、handleDeepSeekResult、系统闹钟/提醒/日历、
//       默认助理设置、任务保存、TTS 朗读、日期时间格式化。
// =============================================================================

// -----------------------------------------------------------------------------
// 用户输入处理：校验 → 发送给 DeepSeek → 分发结果
// -----------------------------------------------------------------------------

/**
 * 处理用户文本输入（输入框提交或语音识别回填后自动触发）。
 * 校验 API Key 后，在后台线程调用 DeepSeekClient，回来后分发结果。
 */
internal fun MainActivity.handleUserInput() {
    if (isAssistantWindow() && assistWindowBusy) return
    val text = input.text.toString().trim()
    if (text.isEmpty()) {
        Toast.makeText(this, "请输入一句话", Toast.LENGTH_SHORT).show()
        return
    }
    val settings = settingsStore.load()
    if (settings.apiKey.isBlank()) {
        showSettingsDialog()
        Toast.makeText(this, "请先填写 DeepSeek API Key", Toast.LENGTH_LONG).show()
        return
    }

    val contextMessages = currentContextMessages()
    addChatMessage("user", text)
    input.text.clear()
    updateAssistActionButton()
    setAssistStatus("DeepSeek 正在生成")

    if (isAssistantWindow()) {
        setAssistBusy("正在思考")
        answerText.visibility = View.GONE
    }
    answerText.text = "正在理解..."

    Thread {
        val result = DeepSeekClient(settings).send(text, contextMessages)
        runOnUiThread {
            result
                .onSuccess {
                    handleDeepSeekResult(it)
                    if (isAssistantWindow()) answerText.visibility = View.VISIBLE
                    setAssistStatus("回答完成")
                    clearAssistBusy()
                }
                .onFailure {
                    setAssistStatus("出错了")
                    val message = "调用失败：${it.message}"
                    answerText.text = message
                    if (isAssistantWindow()) answerText.visibility = View.VISIBLE
                    clearAssistBusy()
                    addChatMessage("assistant", message)
                    speak(message)
                }
        }
    }.start()
}

// -----------------------------------------------------------------------------
// DeepSeek 意图分发：根据 intent 字段路由到对应系统操作
// -----------------------------------------------------------------------------

/**
 * 根据 DeepSeek 返回的 AssistantIntent 分发给对应的系统操作。
 * 支持的意图：set_alarm、dismiss_alarm、create_reminder、delete_reminder、list_reminders、question、unknown。
 */
internal fun MainActivity.handleDeepSeekResult(result: AssistantIntent) {
    when (result.intent) {
        "set_alarm" -> {
            val scheduledAt = parseDateTime(result.datetime)
            if (scheduledAt == null) {
                val message = "DeepSeek 没有返回有效闹钟时间。"
                answerText.text = message
                addChatMessage("assistant", message)
                speak(message)
                return
            }
            val title = result.title.ifBlank { "闹钟" }
            openSystemAlarm(title, scheduledAt, result.repeatDays)
            saveTask("alarm", title, alarmDetail(result), scheduledAt)
            val holidayNote = if (result.skipHolidays) {
                "；系统接口不支持自动跳过节假日，请在三星时钟里手动确认"
            } else ""
            val message = "已打开系统时钟创建闹钟：${formatDateTime(scheduledAt)}" +
                "${formatRepeatDays(result.repeatDays)}$holidayNote"
            answerText.text = message
            addChatMessage("assistant", message)
            speak(message)
        }

        "dismiss_alarm" -> {
            val scheduledAt = parseDateTime(result.datetime)
            dismissSystemAlarm(result.title, scheduledAt)
            val target = when {
                result.title.isNotBlank() -> "\u201c${result.title}\u201d"
                scheduledAt != null -> formatDateTime(scheduledAt)
                else -> "下一个闹钟"
            }
            val message = "已打开闹钟列表，请手动关闭 $target"
            answerText.text = message
            addChatMessage("assistant", message)
            speak(message)
        }

        "create_reminder" -> {
            val scheduledAt = parseDateTime(result.datetime)
            if (scheduledAt == null) {
                val message = "DeepSeek 没有返回有效日程时间。"
                answerText.text = message
                addChatMessage("assistant", message)
                speak(message)
                return
            }
            val title = result.title.ifBlank { "日程" }
            val message = openSystemReminder(title, result.detail.ifBlank { "由 Assistant MVP 创建" }, scheduledAt)
            saveTask("reminder", title, result.detail.ifBlank { "系统日历" }, scheduledAt)
            answerText.text = message
            addChatMessage("assistant", message)
            speak(message)
        }

        "delete_reminder" -> {
            val scheduledAt = parseDateTime(result.datetime)
            val keyword = result.title

            // 关键词为空 → 删除全部日程
            if (keyword.isBlank()) {
                val all = searchReminders("", limit = 200)
                val message = if (all.isEmpty()) {
                    "没有日程可删除"
                } else {
                    all.forEach { deleteReminderByEventId(it.eventId) }
                    // 清空本地全部任务记录
                    val localTasks = taskStore.load()
                    localTasks.forEach { taskStore.deleteByTitleAndTime(it.title, null) }
                    refreshTasks()
                    "已删除全部 ${all.size} 条日程"
                }
                answerText.text = message
                addChatMessage("assistant", message)
                speak(message)
                return
            }

            // 第一阶段：模糊搜索（LIKE %keyword%）
            val candidates = searchReminders(
                keyword,
                scheduledAt?.toInstant()?.toEpochMilli()
            )

            val message = when {
                candidates.isEmpty() -> {
                    "未找到包含\u201c${keyword}\u201d的日程"
                }
                // 精确命中一条 → 直接删除
                candidates.size == 1 -> {
                    val r = candidates.first()
                    deleteReminderByEventId(r.eventId)
                    taskStore.deleteByTitleAndTime(r.title, null)
                    refreshTasks()
                    "已删除日程：\u201c${r.title}\u201d（${formatEpoch(r.dtstart)}）"
                }
                // 多条 → 列出候选，不删，等用户选
                else -> {
                    val listText = candidates.mapIndexed { i, r ->
                        "${i + 1}. ${r.title}（${formatEpoch(r.dtstart)}）"
                    }.joinToString("\n")
                    "找到 ${candidates.size} 条日程：\n$listText\n请说编号或完整名称来删除"
                }
            }

            answerText.text = message
            addChatMessage("assistant", message)
            speak(message)
        }

        "list_reminders" -> {
            val reminders = searchReminders("", limit = 20)
            val message = if (reminders.isEmpty()) {
                "暂无即将到来的日程"
            } else {
                val listText = reminders.mapIndexed { i, r ->
                    "${i + 1}. ${r.title}（${formatEpoch(r.dtstart)}）"
                }.joinToString("\n")
                "即将到来的日程：\n$listText\n说\"删除+编号\"或\"删除+标题\"即可删除"
            }
            answerText.text = message
            addChatMessage("assistant", message)
            speak(message)
        }

        "question" -> {
            val message = result.answer.ifBlank { "DeepSeek 已回答，但内容为空。" }
            answerText.text = message
            addChatMessage("assistant", message)
            speak(message)
        }

        else -> {
            val message = result.answer.ifBlank { "暂时无法识别这个任务。" }
            answerText.text = message
            addChatMessage("assistant", message)
            speak(message)
        }
    }
}

// -----------------------------------------------------------------------------
// 系统 Intent：闹钟 / 关闭闹钟 / 日历日程
// -----------------------------------------------------------------------------

/**
 * 打开系统时钟创建闹钟。
 * Galaxy Watch 通过三星时钟同步，因此只使用系统闹钟 Intent。
 */
internal fun MainActivity.openSystemAlarm(
    title: String,
    scheduledAt: OffsetDateTime,
    repeatDays: List<Int> = emptyList()
) {
    val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
        putExtra(AlarmClock.EXTRA_HOUR, scheduledAt.hour)
        putExtra(AlarmClock.EXTRA_MINUTES, scheduledAt.minute)
        putExtra(AlarmClock.EXTRA_MESSAGE, title)
        if (repeatDays.isNotEmpty()) {
            putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(repeatDays))
        }
        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
    }
    startSafely(intent, "无法打开系统时钟")
}

/**
 * 打开系统时钟闹钟列表并定位到目标闹钟，由用户关闭。
 *
 * 注意：Android 不提供公开 API 以编程方式删除/停用未来的闹钟。
 * ACTION_DISMISS_ALARM 仅对正在响铃的闹钟有效。
 * 这里改用 ACTION_SHOW_ALARMS + 搜索参数，主流时钟 App 会自动
 * 定位到匹配的闹钟，用户轻点一次即可关闭。
 */
internal fun MainActivity.dismissSystemAlarm(title: String, scheduledAt: OffsetDateTime?) {
    val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
        when {
            // 有标签 → 按标签定位
            title.isNotBlank() -> {
                putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_LABEL)
                putExtra(AlarmClock.EXTRA_MESSAGE, title)
            }
            // 有时间 → 按时间定位
            scheduledAt != null -> {
                putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
                putExtra(AlarmClock.EXTRA_HOUR, scheduledAt.hour)
                putExtra(AlarmClock.EXTRA_MINUTES, scheduledAt.minute)
            }
            // 都没有 → 打开闹钟列表首页
            else -> {
                putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
            }
        }
    }
    startSafely(intent, "无法打开系统闹钟列表")
}

/**
 * 直接通过 ContentResolver 写入系统日历创建日程事件（含提醒闹铃）。
 *
 * 优先使用 ContentProvider 直接写入；若无 WRITE_CALENDAR 权限，
 * 则回退到 ACTION_INSERT 意图方式打开日历 App（需用户手动保存）。
 *
 * @return 结果描述文本，由调用方展示和朗读。
 */
internal fun MainActivity.openSystemReminder(
    title: String,
    detail: String,
    scheduledAt: OffsetDateTime
): String {
    // 无日历写入权限 → 回退到 Intent 方式
    if (checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
        val begin = scheduledAt.toInstant().toEpochMilli()
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + 10 * 60 * 1000)
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.Events.DESCRIPTION, detail)
            putExtra(CalendarContract.Events.HAS_ALARM, true)
        }
        startSafely(intent, "无法打开系统日历")
        return "已打开系统日历创建日程：${formatDateTime(scheduledAt)}"
    }

    // 获取默认日历 ID
    val calId = getDefaultCalendarId()
    if (calId == -1L) {
        return "未找到可用日历，请确认设备已登录账号并同步日历"
    }

    val begin = scheduledAt.toInstant().toEpochMilli()
    val end = begin + 10 * 60 * 1000

    // 写入日历事件
    val eventValues = ContentValues().apply {
        put(CalendarContract.Events.CALENDAR_ID, calId)
        put(CalendarContract.Events.TITLE, title)
        put(CalendarContract.Events.DESCRIPTION, detail)
        put(CalendarContract.Events.DTSTART, begin)
        put(CalendarContract.Events.DTEND, end)
        put(CalendarContract.Events.EVENT_TIMEZONE, scheduledAt.offset.id)
        put(CalendarContract.Events.HAS_ALARM, 1)
    }

    val eventUri = contentResolver.insert(CalendarContract.Events.CONTENT_URI, eventValues)
    if (eventUri == null) {
        return "日历日程创建失败，请检查日历权限或存储空间"
    }

    // 写入提醒闹铃（事件发生时立即提醒）
    val eventId = ContentUris.parseId(eventUri)
    val reminderValues = ContentValues().apply {
        put(CalendarContract.Reminders.EVENT_ID, eventId)
        put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        put(CalendarContract.Reminders.MINUTES, 0)
    }
    contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, reminderValues)

    return "已创建日历日程：${formatDateTime(scheduledAt)}"
}

/** 查询默认日历 ID（取第一个可见日历）。无可用日历时返回 -1。 */
private fun MainActivity.getDefaultCalendarId(): Long {
    val projection = arrayOf(CalendarContract.Calendars._ID)
    val cursor = contentResolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        projection,
        null, null, null
    )
    return cursor?.use {
        if (it.moveToFirst()) it.getLong(0) else -1L
    } ?: -1L
}

/**
 * 从系统日历中删除日程事件。
 *
 * 按标题精确匹配，可选时间窗口（±30 分钟）进一步过滤。
 * 同时删除关联的 Reminders 记录。
 *
 * @return 实际删除的事件数量。
 */
internal fun MainActivity.deleteSystemReminder(
    title: String,
    scheduledAtEpoch: Long? = null
): Int {
    if (checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
        return 0
    }

    val calId = getDefaultCalendarId()
    if (calId == -1L) return 0

    // 构建查询条件：按日历 + 标题匹配
    val selection = StringBuilder(
        "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.TITLE} = ?"
    )
    val selectionArgs = mutableListOf(calId.toString(), title)

    // 有时间则加 ±30 分钟窗口
    if (scheduledAtEpoch != null) {
        selection.append(" AND ${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?")
        selectionArgs.add((scheduledAtEpoch - 30 * 60 * 1000).toString())
        selectionArgs.add((scheduledAtEpoch + 30 * 60 * 1000).toString())
    }

    val projection = arrayOf(CalendarContract.Events._ID)
    val cursor = contentResolver.query(
        CalendarContract.Events.CONTENT_URI,
        projection,
        selection.toString(),
        selectionArgs.toTypedArray(),
        null
    )

    val eventIds = mutableListOf<Long>()
    cursor?.use {
        while (it.moveToNext()) {
            eventIds.add(it.getLong(0))
        }
    }

    var deleted = 0
    for (eventId in eventIds) {
        // 先删除关联的 Reminders
        contentResolver.delete(
            CalendarContract.Reminders.CONTENT_URI,
            "${CalendarContract.Reminders.EVENT_ID} = ?",
            arrayOf(eventId.toString())
        )
        // 再删除事件本身
        val rows = contentResolver.delete(
            CalendarContract.Events.CONTENT_URI,
            "${CalendarContract.Events._ID} = ?",
            arrayOf(eventId.toString())
        )
        deleted += rows
    }
    return deleted
}

// -----------------------------------------------------------------------------
// 日历日程搜索与删除（模糊匹配）
// -----------------------------------------------------------------------------

/** 日历日程搜索结果条目。 */
internal data class ReminderInfo(
    val eventId: Long,
    val title: String,
    val dtstart: Long,
    val description: String
)

/**
 * 模糊搜索系统日历日程事件。
 *
 * 关键词使用 SQL LIKE %keyword% 匹配标题，同时可选时间窗口过滤。
 * 结果按开始时间升序排列。
 *
 * @param keyword   搜索关键词，空字符串表示取全部
 * @param scheduledAtEpoch 可选时间约束（±1 小时），不传则只查未来事件
 * @param limit     最大返回条数，默认 10
 */
internal fun MainActivity.searchReminders(
    keyword: String,
    scheduledAtEpoch: Long? = null,
    limit: Int = 10
): List<ReminderInfo> {
    if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
        return emptyList()
    }
    val calId = getDefaultCalendarId()
    if (calId == -1L) return emptyList()

    val selection = StringBuilder("${CalendarContract.Events.CALENDAR_ID} = ?")
    val selectionArgs = mutableListOf(calId.toString())

    // 关键词模糊匹配
    if (keyword.isNotBlank()) {
        selection.append(" AND ${CalendarContract.Events.TITLE} LIKE ?")
        selectionArgs.add("%$keyword%")
    }

    // 时间窗口过滤
    if (scheduledAtEpoch != null) {
        selection.append(" AND ${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?")
        selectionArgs.add((scheduledAtEpoch - 60 * 60 * 1000).toString())
        selectionArgs.add((scheduledAtEpoch + 60 * 60 * 1000).toString())
    } else {
        // 默认只查未来事件（从现在开始）
        selection.append(" AND ${CalendarContract.Events.DTSTART} >= ?")
        selectionArgs.add(System.currentTimeMillis().toString())
    }

    val projection = arrayOf(
        CalendarContract.Events._ID,
        CalendarContract.Events.TITLE,
        CalendarContract.Events.DTSTART,
        CalendarContract.Events.DESCRIPTION
    )

    val cursor = contentResolver.query(
        CalendarContract.Events.CONTENT_URI,
        projection,
        selection.toString(),
        selectionArgs.toTypedArray(),
        "${CalendarContract.Events.DTSTART} ASC LIMIT $limit"
    )

    val results = mutableListOf<ReminderInfo>()
    cursor?.use {
        while (it.moveToNext()) {
            results.add(
                ReminderInfo(
                    eventId = it.getLong(0),
                    title = it.getString(1) ?: "",
                    dtstart = it.getLong(2),
                    description = it.getString(3) ?: ""
                )
            )
        }
    }
    return results
}

/** 通过事件 ID 删除单条日历日程（含关联 Reminders）。返回是否成功。 */
internal fun MainActivity.deleteReminderByEventId(eventId: Long): Boolean {
    if (checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
        return false
    }
    contentResolver.delete(
        CalendarContract.Reminders.CONTENT_URI,
        "${CalendarContract.Reminders.EVENT_ID} = ?",
        arrayOf(eventId.toString())
    )
    val rows = contentResolver.delete(
        CalendarContract.Events.CONTENT_URI,
        "${CalendarContract.Events._ID} = ?",
        arrayOf(eventId.toString())
    )
    return rows > 0
}

/** epoch 毫秒 → 格式化时间字符串（复用 formatDateTime）。 */
private fun MainActivity.formatEpoch(epoch: Long): String {
    return formatDateTime(
        java.time.Instant.ofEpochMilli(epoch)
            .atZone(java.time.ZoneId.systemDefault())
            .toOffsetDateTime()
    )
}

/** 安全启动 Activity：捕获异常并用 Toast + TTS 提示。 */
internal fun MainActivity.startSafely(intent: Intent, errorPrefix: String) {
    try {
        startActivity(intent)
    } catch (e: Exception) {
        val message = "$errorPrefix：${e.message}"
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        speak(message)
    }
}

/** 尝试打开默认助理设置页面，依次尝试多个 Intent。 */
internal fun MainActivity.openDefaultAssistantSettings() {
    val intents = listOf(
        Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        Intent(Settings.ACTION_SETTINGS)
    )
    val opened = intents.any { intent ->
        runCatching {
            startActivity(intent)
            true
        }.getOrDefault(false)
    }
    if (!opened) {
        Toast.makeText(this, "无法打开默认助理设置", Toast.LENGTH_LONG).show()
    }
}

// -----------------------------------------------------------------------------
// 本地任务记录
// -----------------------------------------------------------------------------

/**
 * 保存本地任务记录（仅用于首页可视化，不替代系统闹钟/日历）。
 * 创建时间超过 7 天的任务会在 App 启动时由 TaskStore.deleteExpiredTasks() 清理。
 */
internal fun MainActivity.saveTask(
    type: String,
    title: String,
    detail: String,
    scheduledAt: OffsetDateTime
) {
    taskStore.add(
        AssistantTask(
            id = UUID.randomUUID().toString(),
            type = type,
            title = title,
            detail = detail,
            createdAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            scheduledAt = scheduledAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            status = "created"
        )
    )
    refreshTasks()
}

// -----------------------------------------------------------------------------
// TTS 朗读
// -----------------------------------------------------------------------------

/**
 * 使用系统 TTS 引擎朗读文本。
 *
 * 受两个设置开关控制：
 *   - `ttsEnabled`：总开关，关闭后所有 TTS 静默（包括问候语）
 *   - `ttsResponseEnabled`：应答朗读开关，关闭后不再朗读 DeepSeek 回答和系统提示
 *     注意：助理小窗的问候语不通过本函数播放，不受 ttsResponseEnabled 影响
 */
internal fun MainActivity.speak(text: String) {
    val settings = settingsStore.load()
    if (!settings.ttsEnabled || !settings.ttsResponseEnabled || text.isBlank() || !isTtsReady()) return
    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "assistant_tts_${System.currentTimeMillis()}")
}

/** 停止当前 TTS 播放。语音输入前调用，避免 TTS 干扰录音。 */
internal fun MainActivity.stopTtsPlayback() {
    if (!isTtsReady()) return
    runCatching { tts.stop() }
}

/** 根据设置中的语言偏好切换 TTS 语言。 */
internal fun MainActivity.applyTtsLocale() {
    if (!isTtsReady()) return
    tts.language = Locale.forLanguageTag(settingsStore.load().speechLocale)
}

// -----------------------------------------------------------------------------
// 助理问候语 → 播完自动语音识别
// -----------------------------------------------------------------------------

/**
 * 播放问候语，同时立即开始录音。
 *
 * 策略：
 *   1. TTS 朗读问候语（如"你好，我在"）—— 播放的同时麦克风已经在录
 *   2. 录音期间自动停止的静音阈值临时拉长（5s），避免 TTS 音频触发误停
 *   3. 问候语播完后（字数 × 400ms），阈值恢复到正常的 1s
 *   4. ASR 识别完成后，用模糊匹配过滤掉问候语前缀，只保留用户真正说的话
 *
 * 这样就从根本上解决了时序问题：不需要猜 TTS 什么时候结束，
 * 直接边播边录，后台过滤掉问候语即可。
 */
internal fun MainActivity.playGreetingThenListen(text: String) {
    if (text.isBlank() || !isTtsReady()) return

    // 接管语音启动，避免 maybeStartAutoVoice 重复触发
    pendingAutoVoice = false

    // 记录问候语和截止时间：此时间前录音的自动停止阈值 > 5s
    greetingTextForFilter = text
    greetingModeUntilMs = System.currentTimeMillis() + text.length * 400L

    // 播放问候语
    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "assist_greeting")

    // 立即开始录音（TTS 还在播，但麦克风已经在录了）
    startVoiceInput()
}

// -----------------------------------------------------------------------------
// 日期时间 / 格式化工具
// -----------------------------------------------------------------------------

/** 解析 ISO-8601 带时区的时间字符串。 */
internal fun parseDateTime(value: String): OffsetDateTime? {
    return runCatching { OffsetDateTime.parse(value) }.getOrNull()
}

/** 格式化为用户可读的日期时间：yyyy-MM-dd HH:mm。 */
internal fun formatDateTime(value: OffsetDateTime): String {
    return value.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}

/** 生成闹钟详情描述文本（含重复规则和节假日说明）。 */
internal fun alarmDetail(result: AssistantIntent): String {
    val repeat = formatRepeatDays(result.repeatDays)
    val holiday = if (result.skipHolidays) "；请求跳过节假日（需系统时钟手动确认）" else ""
    return "系统时钟$repeat$holiday"
}

/** 将重复星期数组转为中文描述，如"；重复：周一、周二"。 */
internal fun formatRepeatDays(days: List<Int>): String {
    if (days.isEmpty()) return ""
    val labels = days.distinct().sorted().map {
        when (it) {
            1 -> "周日"; 2 -> "周一"; 3 -> "周二"; 4 -> "周三"
            5 -> "周四"; 6 -> "周五"; 7 -> "周六"
            else -> ""
        }
    }.filter { it.isNotBlank() }
    return if (labels.isEmpty()) "" else "；重复：${labels.joinToString("、")}"
}

/** 任务类型标签中文映射。 */
internal fun typeLabel(type: String): String {
    return when (type) {
        "alarm" -> "[闹钟]"
        "reminder" -> "[日程]"
        else -> "[任务]"
    }
}
