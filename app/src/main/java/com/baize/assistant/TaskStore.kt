package com.baize.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime

class TaskStore(context: Context) {
    private val prefs = context.getSharedPreferences("tasks", Context.MODE_PRIVATE)

    // 读取本地任务列表：用于首页“最近任务”，不影响系统闹钟或系统日历。
    fun load(): List<AssistantTask> {
        val array = JSONArray(prefs.getString("items", "[]"))
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            AssistantTask(
                id = item.optString("id"),
                type = item.optString("type"),
                title = item.optString("title"),
                detail = item.optString("detail"),
                createdAt = item.optString("createdAt"),
                scheduledAt = item.optString("scheduledAt"),
                status = item.optString("status")
            )
        }
    }

    // 新任务放在列表顶部，方便用户看到刚刚创建的闹钟或日程。
    fun add(task: AssistantTask) {
        save(listOf(task) + load())
    }

    /** 按标题 + 时间匹配删除本地任务记录。返回被删除的数量。 */
    fun deleteByTitleAndTime(title: String, scheduledAt: String?): Int {
        val all = load()
        val toRemove = all.filter { task ->
            task.title == title && (scheduledAt == null || task.scheduledAt == scheduledAt)
        }
        if (toRemove.isEmpty()) return 0
        save(all - toRemove.toSet())
        return toRemove.size
    }

    // 启动时清理创建超过 7 天的本地记录，避免后台定时任务。
    fun deleteExpiredTasks() {
        val cutoff = OffsetDateTime.now().minusDays(7)
        save(load().filter { task ->
            val createdAt = runCatching { OffsetDateTime.parse(task.createdAt) }.getOrNull()
            createdAt == null || createdAt.isAfter(cutoff)
        })
    }

    // 使用 SharedPreferences + JSON 保存 MVP 数据，暂不引入 Room。
    private fun save(tasks: List<AssistantTask>) {
        val array = JSONArray()
        tasks.forEach { task ->
            array.put(
                JSONObject()
                    .put("id", task.id)
                    .put("type", task.type)
                    .put("title", task.title)
                    .put("detail", task.detail)
                    .put("createdAt", task.createdAt)
                    .put("scheduledAt", task.scheduledAt)
                    .put("status", task.status)
            )
        }
        prefs.edit().putString("items", array.toString()).apply()
    }
}
