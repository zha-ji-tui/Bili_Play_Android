package io.github.zhajitui.biliplay.data.api

import io.github.zhajitui.biliplay.data.model.SavedCookie
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

class PersistentCookieJar(
    private val onChanged: ((List<SavedCookie>) -> Unit)? = null,
) : CookieJar {
    private val cookies = mutableListOf<SavedCookie>()

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(cookies) {
        cookies.asSequence()
            .filter { !it.isExpired() && it.matches(url) }
            .mapNotNull { it.toCookie(url) }
            .toList()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val snapshot = synchronized(this.cookies) {
            var changed = false
            cookies.forEach { cookie ->
                if (!isBilibiliDomain(cookie.domain)) return@forEach
                val saved = SavedCookie(
                    name = cookie.name,
                    value = cookie.value,
                    domain = cookie.domain,
                    path = cookie.path,
                    expiresAt = cookie.expiresAt,
                )
                this.cookies.removeAll { existing ->
                    existing.name == saved.name &&
                        existing.domain.equals(saved.domain, ignoreCase = true) &&
                        existing.path == saved.path
                }
                this.cookies += saved
                changed = true
            }
            if (changed) this.cookies.toList() else null
        }
        snapshot?.let { onChanged?.invoke(it) }
    }

    fun snapshot(): List<SavedCookie> = synchronized(cookies) { cookies.toList() }

    fun import(cookies: List<SavedCookie>) {
        synchronized(this.cookies) {
            this.cookies.clear()
            this.cookies += cookies.filter { !it.isExpired() && isBilibiliDomain(it.domain) }
        }
    }

    fun clear() {
        synchronized(cookies) { cookies.clear() }
    }

    fun find(name: String): SavedCookie? =
        synchronized(cookies) { cookies.firstOrNull { it.name == name } }

    fun putIfAbsent(cookie: SavedCookie) {
        if (!isBilibiliDomain(cookie.domain)) return
        synchronized(cookies) {
            if (cookies.none { it.name == cookie.name }) cookies += cookie
        }
    }

    private fun SavedCookie.isExpired(): Boolean =
        expiresAt != Long.MAX_VALUE && expiresAt <= System.currentTimeMillis()

    private fun SavedCookie.matches(url: HttpUrl): Boolean {
        if (!hostMatches(url.host, domain)) return false
        val requestPath = url.encodedPath.ifEmpty { "/" }
        val cookiePath = path.ifEmpty { "/" }
        return requestPath == cookiePath ||
            (requestPath.startsWith(cookiePath) &&
                (cookiePath.endsWith("/") || requestPath.getOrNull(cookiePath.length) == '/'))
    }

    private fun SavedCookie.toCookie(url: HttpUrl): Cookie? = runCatching {
        Cookie.Builder()
            .name(name)
            .value(value)
            .path(path.ifEmpty { "/" })
            .expiresAt(expiresAt)
            .apply {
                if (domain.startsWith(".")) {
                    domain(domain.removePrefix("."))
                } else {
                    hostOnlyDomain(url.host)
                }
            }
            .secure()
            .build()
    }.getOrNull()

    private companion object {
        fun isBilibiliDomain(domain: String): Boolean {
            val normalized = domain.removePrefix(".").lowercase()
            return normalized == "bilibili.com" || normalized.endsWith(".bilibili.com")
        }

        fun hostMatches(host: String, domain: String): Boolean {
            val normalized = domain.removePrefix(".").lowercase()
            val requestHost = host.lowercase()
            return requestHost == normalized || requestHost.endsWith(".$normalized")
        }
    }
}
