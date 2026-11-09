package io.github.zhajitui.biliplay.data.api

import java.net.URLEncoder
import java.security.MessageDigest

object WbiSigner {
    private val mixinKeyOrder = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
        37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
        22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
    )

    fun sign(
        parameters: Map<String, String>,
        imageKey: String,
        subKey: String,
        timestamp: Long,
    ): String {
        val raw = imageKey + subKey
        require(raw.length == 64) { "B 站签名密钥无效，请稍后重试。" }
        val key = buildString(32) {
            mixinKeyOrder.take(32).forEach { append(raw[it]) }
        }
        val values = parameters.toMutableMap().apply {
            remove("w_rid")
            this["wts"] = timestamp.toString()
        }
        val query = values.entries
            .sortedBy { it.key }
            .joinToString("&") { (name, value) ->
                "${encode(name)}=${encode(value.filterNot { it in FORBIDDEN_CHARS })}"
            }
        return "$query&w_rid=${md5(query + key)}"
    }

    private const val FORBIDDEN_CHARS = "!'()*"

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
