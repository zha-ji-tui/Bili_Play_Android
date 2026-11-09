package io.github.zhajitui.biliplay.data.local

import android.content.Context

data class RateLimitState(
    val until: Long,
    val backoff: Long,
)

class RateLimitStore(context: Context) {
    private val prefs = context.getSharedPreferences("biliplay.rate_limit", Context.MODE_PRIVATE)

    fun load(scope: String): RateLimitState? {
        val until = prefs.getLong("until:$scope", 0L)
        if (until <= 0L) return null
        return RateLimitState(until, prefs.getLong("backoff:$scope", 0L))
    }

    fun save(scope: String, until: Long, backoff: Long) {
        prefs.edit()
            .putLong("until:$scope", until)
            .putLong("backoff:$scope", backoff)
            .apply()
    }

    fun clear(scope: String) {
        prefs.edit()
            .remove("until:$scope")
            .remove("backoff:$scope")
            .apply()
    }
}
