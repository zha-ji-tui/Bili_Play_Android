package io.github.zhajitui.biliplay.data.api

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class BilibiliApiIntegrationTest {
    @Test
    fun anonymousNavAndSignedVideoRequestWork() = runBlocking {
        assumeTrue(
            "设置 BILI_INTEGRATION=1 后运行联网测试",
            System.getenv("BILI_INTEGRATION") == "1",
        )

        val api = BilibiliApi(PersistentCookieJar())
        api.warmUp()

        val navError = runCatching { api.getAccount() }.exceptionOrNull()
        assertTrue(
            "匿名访问 nav 应返回未登录错误",
            navError is BilibiliException && navError.code == -101,
        )

        val video = api.getVideo("BV1GJ411x7h7")
        val title = video["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
        assertTrue("WBI 签名请求应能取回视频标题", title.isNotEmpty())

        val login = api.createLoginCode()
        assertTrue("扫码登录二维码地址无效", login.url.startsWith("https://"))
        assertTrue("扫码登录密钥为空", login.key.isNotEmpty())
        val pollStatus = api.pollLogin(login.key)
        assertTrue("未扫码时轮询应返回 86101，实际为 $pollStatus", pollStatus == 86101)

        val search = api.searchVideos("音乐", 1)
        assertTrue("视频搜索应返回结果", search.items.isNotEmpty())
        val uploaders = api.searchUploaders("音乐", 1)
        assertTrue("UP 主搜索应返回结果", uploaders.items.isNotEmpty())

        val feed = api.getHomeFeed(1)
        assertTrue("首页音乐推荐应返回内容", feed.items.isNotEmpty())
    }
}
