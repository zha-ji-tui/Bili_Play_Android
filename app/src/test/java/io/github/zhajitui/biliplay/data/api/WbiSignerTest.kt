package io.github.zhajitui.biliplay.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WbiSignerTest {
    @Test
    fun `matches the reference implementation`() {
        val signature = WbiSigner.sign(
            parameters = mapOf(
                "foo" to "114",
                "bar" to "514",
                "zab" to "1919810",
                "中文" to "测试!*'()",
            ),
            imageKey = "0123456789abcdef0123456789abcdef",
            subKey = "fedcba9876543210fedcba9876543210",
            timestamp = 1702204169L,
        )

        assertEquals(
            "bar=514&foo=114&wts=1702204169&zab=1919810&%E4%B8%AD%E6%96%87=%E6%B5%8B%E8%AF%95&w_rid=e18ef7a17a413a232ce5bb7ad46c80c8",
            signature,
        )
    }

    @Test
    fun `rejects a signing key with an unexpected length`() {
        assertThrows(IllegalArgumentException::class.java) {
            WbiSigner.sign(mapOf("a" to "b"), "short", "key", 0L)
        }
    }
}
