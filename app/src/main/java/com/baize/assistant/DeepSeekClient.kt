package com.baize.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class DeepSeekClient(private val settings: DeepSeekSettings) {

    private val history = mutableListOf<JSONObject>()

    fun send(userText: String, contextMessages: List<ChatMessage> = emptyList()): Result<AssistantIntent> {
        return runCatching {
            val url = URL(settings.baseUrl.trimEnd('/') + "/chat/completions")

            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 30_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
                setRequestProperty("Content-Type", "application/json")
            }

            val requestJson = buildRequest(userText, contextMessages)

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use {
                it.write(requestJson.toString())
            }

            val body = if (connection.responseCode in 200..299) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                val error = connection.errorStream
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }

                throw IllegalStateException("HTTP ${connection.responseCode}: ${error.orEmpty()}")
            }

            val content = JSONObject(body)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            history.add(JSONObject().put("role", "user").put("content", userText))
            history.add(JSONObject().put("role", "assistant").put("content", content))
            trimHistory()

            parseAssistantIntent(content)
        }
    }

    fun clearContext() {
        history.clear()
    }

    private fun buildRequest(userText: String, contextMessages: List<ChatMessage>): JSONObject {
        val messages = JSONArray()

        messages.put(
            JSONObject()
                .put("role", "system")
                .put("content", buildSystemPrompt())
        )

        contextMessages
            .filter { it.role == "user" || it.role == "assistant" }
            .takeLast(12)
            .forEach {
                messages.put(
                    JSONObject()
                        .put("role", it.role)
                        .put("content", it.content)
                )
            }

        history.forEach { messages.put(it) }

        messages.put(
            JSONObject()
                .put("role", "user")
                .put("content", userText)
        )

        return JSONObject()
            .put("model", settings.model)
            .put("temperature", 0.1)
            .put("messages", messages)
    }

    private fun buildSystemPrompt(): String {
        val now = OffsetDateTime.now(ZoneId.systemDefault())
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

        return """
你是 Android 手机轻量 Assistant 意图解析器。当前时间 $now。
只返回一个 JSON 对象，不要 Markdown、不要解释、不要多余内容。

intent: set_alarm|dismiss_alarm|create_reminder|delete_reminder|list_reminders|question|unknown

规则：
1. set_alarm/create_reminder → 返回 title+datetime(ISO-8601+时区)。
   可选 repeat_days(1=周日..7=周六,如工作日[2,3,4,5,6])、skip_holidays。

2. dismiss_alarm → 返回 title 或 datetime 定位闹钟。仅停用，Android 无删除闹钟 API。

3. delete_reminder → 返回 title 关键词(模糊搜索)。"删除全部日程"→title=""。"第二个"等编号需结合上文列表推断 title。

4. list_reminders → 查看日程，无需额外字段。

5. question → 返回 answer。unknown → 可返回 answer。

时间：用户说"几点开会"=事件时间，"几点叫我"=日程时间。不确定意图返回 unknown。闹钟优先用原时间，除非明确说"提前"。

上下文：结合上文理解"改成9点""取消它""第二个"。不确定指向→unknown。

示例：
{"intent":"set_alarm","title":"起床","datetime":"2026-06-10T07:00:00+08:00","repeat_days":[2,3,4,5,6],"skip_holidays":false}
{"intent":"delete_reminder","title":"开会"}
{"intent":"list_reminders"}
        """.trimIndent()
    }

    private fun trimHistory() {
        val maxMessages = 12

        while (history.size > maxMessages) {
            history.removeAt(0)
        }
    }

    private fun parseAssistantIntent(rawContent: String): AssistantIntent {
        val cleaned = rawContent
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val json = JSONObject(cleaned)

        val repeatDays = json.optJSONArray("repeat_days")
        return AssistantIntent(
            intent = json.optString("intent", "unknown"),
            title = json.optString("title"),
            datetime = json.optString("datetime"),
            detail = json.optString("detail"),
            repeatDays = if (repeatDays == null) {
                emptyList()
            } else {
                (0 until repeatDays.length())
                    .mapNotNull { repeatDays.optInt(it).takeIf { day -> day in 1..7 } }
            },
            skipHolidays = json.optBoolean("skip_holidays", false),
            answer = json.optString("answer")
        )
    }
}
