package io.github.zhajitui.biliplay.ui

import io.github.zhajitui.biliplay.data.api.BilibiliException
import java.io.IOException

fun Throwable.userMessage(): String = when (this) {
    is BilibiliException -> message ?: "请求未完成，请稍后重试"
    is IOException -> "网络连接失败，请检查网络后重试"
    else -> "加载失败，请稍后重试"
}
