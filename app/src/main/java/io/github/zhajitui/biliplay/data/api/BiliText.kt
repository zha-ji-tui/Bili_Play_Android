package io.github.zhajitui.biliplay.data.api

import java.net.URI

object BiliText {
    private val tags = Regex("<[^>]*>")
    private val mediaHosts = listOf(
        "bilivideo.com",
        "bilivideo.cn",
        "akamaized.net",
        "bilibili.com",
        "hdslb.com",
    )

    fun clean(value: String): String = value
        .replace(tags, "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")
        .trim()

    fun coverUrl(value: String, width: Int = 0): String {
        val url = mediaUrl(value)
        if (url.isEmpty() || width <= 0) return url
        return url.substringBefore('@') + "@${width}w.webp"
    }

    fun mediaUrl(value: String): String {
        if (value.isBlank()) return ""
        var url = value.trim()
        if (url.startsWith("//")) url = "https:$url"
        if (url.startsWith("http://")) url = "https://${url.removePrefix("http://")}"
        if (!url.startsWith("https://") || !isMediaHost(url)) return ""
        return url
    }

    fun parseDuration(value: String): Int {
        if (value.isBlank()) return 0
        var result = 0
        value.split(':').forEach { piece ->
            val number = piece.toIntOrNull() ?: return 0
            if (number < 0) return 0
            result = result * 60 + number
        }
        return result
    }

    fun formatDuration(seconds: Int): String {
        val total = seconds.coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val remaining = total % 60
        return if (hours > 0) {
            "$hours:${minutes.toString().padStart(2, '0')}:${remaining.toString().padStart(2, '0')}"
        } else {
            "$minutes:${remaining.toString().padStart(2, '0')}"
        }
    }

    private fun isMediaHost(url: String): Boolean {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        return mediaHosts.any { host == it || host.endsWith(".$it") }
    }
}
