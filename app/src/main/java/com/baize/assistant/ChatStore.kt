package com.baize.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

class ChatStore(context: Context) {
    private val prefs = context.getSharedPreferences("chat", Context.MODE_PRIVATE)

    init {
        migrateLegacySingleConversation()
    }

    fun currentConversationId(): String {
        val saved = prefs.getString(KEY_CURRENT_ID, "").orEmpty()
        return if (saved.isNotBlank() && conversations().any { it.id == saved }) saved else ""
    }

    fun conversations(): List<ChatConversation> {
        val array = JSONArray(prefs.getString(KEY_CONVERSATIONS, "[]"))
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id")
            if (id.isBlank()) return@mapNotNull null
            ChatConversation(
                id = id,
                title = item.optString("title").ifBlank { DEFAULT_TITLE },
                updatedAt = item.optString("updatedAt")
            )
        }.filter { load(it.id).isNotEmpty() }
            .sortedByDescending { it.updatedAt }
    }

    fun createConversation(title: String = DEFAULT_TITLE): ChatConversation {
        val conversation = ChatConversation(
            id = UUID.randomUUID().toString(),
            title = title,
            updatedAt = now()
        )
        saveConversations(listOf(conversation) + rawConversations())
        prefs.edit()
            .putString(KEY_CURRENT_ID, conversation.id)
            .putString(messagesKey(conversation.id), "[]")
            .apply()
        return conversation
    }

    fun selectConversation(id: String) {
        prefs.edit().putString(KEY_CURRENT_ID, id).apply()
    }

    fun startDraftConversation() {
        prefs.edit().putString(KEY_CURRENT_ID, "").apply()
    }

    fun deleteConversation(id: String) {
        val wasCurrent = prefs.getString(KEY_CURRENT_ID, "").orEmpty() == id
        val remaining = rawConversations().filterNot { it.id == id }
        prefs.edit().remove(messagesKey(id)).apply()
        saveConversations(remaining)
        if (wasCurrent || remaining.isEmpty()) {
            startDraftConversation()
        }
    }

    fun clearCurrentConversation() {
        val id = currentConversationId()
        if (id.isBlank()) return
        prefs.edit().remove(messagesKey(id)).apply()
        saveConversations(rawConversations().filterNot { it.id == id })
        startDraftConversation()
    }

    fun load(): List<ChatMessage> {
        val id = currentConversationId()
        if (id.isBlank()) return emptyList()
        return load(id)
    }

    fun load(conversationId: String): List<ChatMessage> {
        if (conversationId.isBlank()) return emptyList()
        val array = JSONArray(prefs.getString(messagesKey(conversationId), "[]"))
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            ChatMessage(
                id = item.optString("id"),
                role = item.optString("role"),
                content = item.optString("content"),
                createdAt = item.optString("createdAt")
            )
        }
    }

    fun add(message: ChatMessage) {
        val id = currentConversationId().ifBlank {
            createConversation(DEFAULT_TITLE).id
        }
        addToConversation(id, message)
    }

    fun addToConversation(id: String, message: ChatMessage) {
        if (id.isBlank()) return
        val messages = load(id) + message
        saveMessages(id, messages)
        val firstUser = messages.firstOrNull { it.role == "user" }?.content.orEmpty()
        val title = firstUser.take(24).ifBlank { DEFAULT_TITLE }
        updateConversation(id, title)
    }

    fun deleteMessage(id: String) {
        val conversationId = currentConversationId()
        if (conversationId.isBlank()) return
        val messages = load(conversationId).filterNot { it.id == id }
        if (messages.isEmpty()) {
            clearCurrentConversation()
            return
        }
        saveMessages(conversationId, messages)
        val title = messages.firstOrNull { it.role == "user" }?.content?.take(24).orEmpty()
        updateConversation(conversationId, title.ifBlank { DEFAULT_TITLE })
    }

    private fun rawConversations(): List<ChatConversation> {
        val array = JSONArray(prefs.getString(KEY_CONVERSATIONS, "[]"))
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id")
            if (id.isBlank()) return@mapNotNull null
            ChatConversation(
                id = id,
                title = item.optString("title").ifBlank { DEFAULT_TITLE },
                updatedAt = item.optString("updatedAt")
            )
        }.sortedByDescending { it.updatedAt }
    }

    private fun updateConversation(id: String, title: String) {
        val existing = rawConversations()
        if (existing.none { it.id == id }) return
        saveConversations(existing.map {
            if (it.id == id) it.copy(title = title, updatedAt = now()) else it
        })
    }

    private fun saveConversations(conversations: List<ChatConversation>) {
        val array = JSONArray()
        conversations.distinctBy { it.id }.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("title", it.title)
                    .put("updatedAt", it.updatedAt)
            )
        }
        prefs.edit().putString(KEY_CONVERSATIONS, array.toString()).apply()
    }

    private fun saveMessages(conversationId: String, messages: List<ChatMessage>) {
        val array = JSONArray()
        messages.forEach { message ->
            array.put(
                JSONObject()
                    .put("id", message.id)
                    .put("role", message.role)
                    .put("content", message.content)
                    .put("createdAt", message.createdAt)
            )
        }
        prefs.edit().putString(messagesKey(conversationId), array.toString()).apply()
    }

    private fun messagesKey(conversationId: String) = "messages_$conversationId"

    private fun now(): String = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    private fun migrateLegacySingleConversation() {
        if (prefs.getBoolean(KEY_LEGACY_MIGRATED, false)) return
        if (rawConversations().isNotEmpty()) {
            prefs.edit().putBoolean(KEY_LEGACY_MIGRATED, true).apply()
            return
        }
        val legacyRaw = prefs.getString("messages", "[]").orEmpty()
        val legacyArray = JSONArray(legacyRaw)
        if (legacyArray.length() == 0) {
            prefs.edit().putBoolean(KEY_LEGACY_MIGRATED, true).apply()
            return
        }

        val conversation = createConversation("Legacy Chat")
        prefs.edit()
            .putString(messagesKey(conversation.id), legacyRaw)
            .remove("messages")
            .putBoolean(KEY_LEGACY_MIGRATED, true)
            .apply()
        val title = load(conversation.id).firstOrNull { it.role == "user" }?.content?.take(24).orEmpty()
        updateConversation(conversation.id, title.ifBlank { "Legacy Chat" })
    }

    companion object {
        private const val KEY_CURRENT_ID = "current_conversation_id"
        private const val KEY_CONVERSATIONS = "conversations"
        private const val KEY_LEGACY_MIGRATED = "legacy_migrated"
        private const val DEFAULT_TITLE = "New Chat"
    }
}
