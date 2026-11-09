package io.github.zhajitui.biliplay.data.local

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SearchHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("biliplay.search_history", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun load(accountId: Long): List<String> {
        val raw = prefs.getString("history:$accountId", null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<String>>(raw) }.getOrElse { emptyList() }
    }

    fun add(accountId: Long, keyword: String): List<String> {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return load(accountId)
        val updated = (listOf(trimmed) + load(accountId).filterNot { it.equals(trimmed, ignoreCase = true) })
            .take(MAX_ITEMS)
        save(accountId, updated)
        return updated
    }

    fun remove(accountId: Long, keyword: String): List<String> {
        val updated = load(accountId).filterNot { it.equals(keyword, ignoreCase = true) }
        save(accountId, updated)
        return updated
    }

    fun clear(accountId: Long) {
        prefs.edit().remove("history:$accountId").apply()
    }

    private fun save(accountId: Long, history: List<String>) {
        prefs.edit().putString("history:$accountId", json.encodeToString(history)).apply()
    }

    private companion object {
        const val MAX_ITEMS = 30
    }
}
