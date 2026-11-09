package io.github.zhajitui.biliplay.data.api

class BilibiliException(
    val code: Int,
    message: String,
) : Exception(message) {
    val requiresLogin: Boolean
        get() = code == -101 || code == -111
}
