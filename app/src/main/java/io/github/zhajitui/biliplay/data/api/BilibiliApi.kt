package io.github.zhajitui.biliplay.data.api

import io.github.zhajitui.biliplay.data.local.RateLimitStore
import io.github.zhajitui.biliplay.data.model.Account
import io.github.zhajitui.biliplay.data.model.AudioStream
import io.github.zhajitui.biliplay.data.model.LoginCode
import io.github.zhajitui.biliplay.data.model.PageResult
import io.github.zhajitui.biliplay.data.model.SavedCookie
import io.github.zhajitui.biliplay.data.model.Track
import io.github.zhajitui.biliplay.data.model.UploaderItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class BilibiliApi(
    private val cookieJar: PersistentCookieJar,
    private val client: OkHttpClient = defaultClient(cookieJar),
    private val rateLimits: RateLimitStore? = null,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    },
) {
    private data class SigningKeys(val image: String, val sub: String, val at: Long)

    private data class Cooldown(var until: Long = 0L, var backoff: Long = 0L)

    private val keyMutex = Mutex()
    private val throttleMutex = Mutex()
    private val cooldowns = mutableMapOf<String, Cooldown>()

    @Volatile
    private var signingKeys: SigningKeys? = null

    @Volatile
    private var lastRequestAt = 0L

    init {
        if (rateLimits != null) {
            listOf(SCOPE_DEFAULT, SCOPE_FAVORITES).forEach { scope ->
                rateLimits.load(scope)?.let { state ->
                    cooldowns[scope] = Cooldown(state.until, state.backoff)
                }
            }
        }
    }

    suspend fun warmUp() {
        ensureNotBlocked(SCOPE_DEFAULT)
        awaitRequestSlot()
        val request = Request.Builder()
            .url(SITE)
            .header("User-Agent", USER_AGENT)
            .header("Referer", SITE)
            .build()
        withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                if (response.code == 412 || response.code == 429) {
                    onRateLimited(SCOPE_DEFAULT)
                    throw BilibiliException(-352, rateLimitMessage(SCOPE_DEFAULT))
                }
                if (!response.isSuccessful) {
                    throw IOException("无法连接哔哩哔哩（HTTP ${response.code}）")
                }
                onRequestSucceeded(SCOPE_DEFAULT)
            }
        }
    }

    suspend fun createLoginCode(): LoginCode {
        val data = requestData(
            "${PASSPORT}x/passport-login/web/qrcode/generate",
            emptyMap(),
            signed = false,
        )
        val url = data.string("url")
        val key = data.string("qrcode_key")
        val host = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
        if (key.isEmpty() || (host != "bilibili.com" && !host.endsWith(".bilibili.com"))) {
            throw IOException("B 站返回了无效的登录二维码，请重试。")
        }
        return LoginCode(url, key)
    }

    suspend fun pollLogin(key: String): Int {
        val data = requestData(
            "${PASSPORT}x/passport-login/web/qrcode/poll",
            mapOf("qrcode_key" to key),
            signed = false,
        )
        val code = data.int("code")
        if (code == 0) importCookiesFromUrl(data.string("url"))
        return code
    }

    suspend fun getAccount(): Account {
        val data = requestData("${API}x/web-interface/nav", emptyMap(), signed = false)
        if (!data.bool("isLogin")) throw BilibiliException(-101, "登录已过期，请重新扫码登录。")
        rememberKeys(data)
        return Account(
            id = data.long("mid"),
            name = data.string("uname"),
            avatar = data.string("face"),
            level = data.obj("level_info")?.int("current_level") ?: 0,
            isVip = data.int("vipStatus") == 1 || (data.obj("vip")?.int("status") ?: 0) == 1,
        )
    }

    suspend fun getVideo(bvid: String): JsonObject = requestData(
        "${API}x/web-interface/wbi/view",
        mapOf("bvid" to bvid),
        signed = true,
    )

    suspend fun getVideoSummary(bvid: String): Track? {
        val data = getVideo(bvid)
        val resolved = data.string("bvid")
        if (resolved.isEmpty()) return null
        val owner = data.obj("owner")
        return Track(
            aid = data.long("aid"),
            bvid = resolved,
            title = BiliText.clean(data.string("title")),
            uploader = BiliText.clean(owner?.string("name").orEmpty()),
            cover = BiliText.coverUrl(data.string("pic"), 480),
            duration = data.int("duration"),
            uploaderId = owner?.long("mid") ?: 0,
        )
    }

    suspend fun getHomeFeed(page: Int): PageResult<Track> {
        val data = try {
            requestData(
                "${API}x/web-interface/region/feed/rcmd",
                mapOf(
                    "display_id" to page.toString(),
                    "request_cnt" to "15",
                    "from_region" to "1003",
                    "device" to "web",
                    "plat" to "30",
                    "web_location" to "333.40138",
                ),
                signed = false,
                referer = MUSIC_PAGE,
            )
        } catch (error: BilibiliException) {
            if (error.code == 62013) return PageResult(emptyList(), false)
            throw error
        }
        val items = data.array("archives").mapNotNull { element ->
            val x = element as? JsonObject ?: return@mapNotNull null
            val bvid = x.string("bvid")
            if (bvid.isEmpty()) return@mapNotNull null
            Track(
                aid = x.long("aid"),
                bvid = bvid,
                title = BiliText.clean(x.string("title")),
                uploader = BiliText.clean(x.obj("author")?.string("name").orEmpty()),
                cover = BiliText.coverUrl(x.string("cover"), 480),
                duration = x.int("duration"),
                uploaderId = x.obj("author")?.long("mid") ?: 0,
            )
        }
        return PageResult(items, items.isNotEmpty())
    }

    suspend fun searchVideos(keyword: String, page: Int): PageResult<Track> {
        val data = requestData(
            "${API}x/web-interface/wbi/search/type",
            mapOf(
                "search_type" to "video",
                "keyword" to keyword,
                "page" to page.toString(),
                "page_size" to "20",
                "order" to "totalrank",
            ),
            signed = true,
        )
        val items = data.array("result").mapNotNull { element ->
            val x = element as? JsonObject ?: return@mapNotNull null
            val bvid = x.string("bvid")
            if (bvid.isEmpty()) return@mapNotNull null
            Track(
                aid = x.long("aid"),
                bvid = bvid,
                title = BiliText.clean(x.string("title")),
                uploader = BiliText.clean(x.string("author")),
                cover = BiliText.coverUrl(x.string("pic"), 480),
                duration = BiliText.parseDuration(x.string("duration")),
                uploaderId = x.long("mid"),
            )
        }
        return PageResult(items, data.int("numPages") > page)
    }

    suspend fun searchUploaders(keyword: String, page: Int): PageResult<UploaderItem> {
        val data = requestData(
            "${API}x/web-interface/wbi/search/type",
            mapOf(
                "search_type" to "bili_user",
                "user_type" to "1",
                "keyword" to keyword,
                "page" to page.toString(),
                "page_size" to "20",
            ),
            signed = true,
        )
        val items = data.array("result").mapNotNull { element ->
            val x = element as? JsonObject ?: return@mapNotNull null
            val mid = x.long("mid")
            if (mid <= 0) return@mapNotNull null
            UploaderItem(
                id = mid,
                name = BiliText.clean(x.string("uname")),
                avatar = BiliText.coverUrl(x.string("upic"), 160),
                signature = BiliText.clean(x.string("usign")),
                fans = x.long("fans"),
                videos = x.long("videos"),
            )
        }
        return PageResult(items, data.int("numPages") > page)
    }

    suspend fun resolveAudio(track: Track, isVip: Boolean): AudioStream {
        val video = requestData(
            "${API}x/web-interface/wbi/view",
            mapOf("bvid" to track.bvid),
            signed = true,
        )
        val cid = video.array("pages")
            .asSequence()
            .mapNotNull { it as? JsonObject }
            .map { it.long("cid") }
            .firstOrNull { it > 0 }
            ?: throw IOException("这个视频没有可播放的音轨。")
        val params = mapOf(
            "bvid" to track.bvid,
            "avid" to track.aid.toString(),
            "cid" to cid.toString(),
            "fnval" to if (isVip) "4048" else "16",
            "fnver" to "0",
            "qn" to "80",
        )
        val data = try {
            requestData("${API}x/player/wbi/playurl", params, signed = true)
        } catch (error: BilibiliException) {
            requestData("${API}x/player/playurl", params, signed = false)
        } catch (error: IOException) {
            requestData("${API}x/player/playurl", params, signed = false)
        }
        return parseAudio(data, isVip)
    }

    private fun parseAudio(data: JsonObject, isVip: Boolean): AudioStream {
        val dash = data.obj("dash") ?: throw IOException("这个视频没有可播放的独立音频。")
        val standard = dash.array("audio")
            .mapNotNull { it as? JsonObject }
            .sortedWith(
                compareByDescending<JsonObject> { it.string("codecs").startsWith("mp4a", ignoreCase = true) }
                    .thenByDescending { it.long("bandwidth") },
            )
            .firstNotNullOfOrNull { parseAudioEntry(it, "标准") }
            ?: throw IOException("这个视频没有可播放的独立音频，或当前账号没有播放权限。")

        val flacInfo = dash.obj("flac")
        val flacAudio = flacInfo?.obj("audio")
        val flacId = flacAudio?.long("id") ?: 0L
        val hasHiRes = flacId == 30251L || (!isVip && flacInfo?.bool("display") == true)
        if (isVip && flacId == 30251L && flacAudio != null) {
            parseAudioEntry(flacAudio, "Hi-Res 无损")?.let { return it.copy(hasHiRes = true) }
        }
        return standard.copy(hasHiRes = hasHiRes)
    }

    private fun parseAudioEntry(x: JsonObject, quality: String): AudioStream? {
        val candidates = buildList {
            add(x.string("baseUrl"))
            add(x.string("base_url"))
            x.array("backupUrl").forEach { element ->
                (element as? JsonPrimitive)?.contentOrNull?.let(::add)
            }
            x.array("backup_url").forEach { element ->
                (element as? JsonPrimitive)?.contentOrNull?.let(::add)
            }
        }
        val urls = candidates
            .map { BiliText.mediaUrl(it) }
            .filter { it.isNotEmpty() }
            .distinct()
        if (urls.isEmpty()) return null
        val mime = x.string("mimeType").ifEmpty {
            x.string("mime_type").ifEmpty { "audio/mp4" }
        }
        return AudioStream(
            url = urls.first(),
            mimeType = mime,
            backups = urls.drop(1),
            quality = quality,
        )
    }

    fun exportCookies(): List<SavedCookie> = cookieJar.snapshot()

    fun importCookies(cookies: List<SavedCookie>) = cookieJar.import(cookies)

    fun clearSession() = cookieJar.clear()

    private suspend fun requestData(
        path: String,
        params: Map<String, String>,
        signed: Boolean,
        referer: String = SITE,
    ): JsonObject {
        val scope = if (path.contains("/fav/")) SCOPE_FAVORITES else SCOPE_DEFAULT
        ensureNotBlocked(scope)
        awaitRequestSlot()
        val query = if (signed) signedQuery(params) else encodeQuery(params)
        val base = if (path.startsWith("https://")) path else API + path
        val url = if (query.isEmpty()) base else "$base?$query"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .build()
        return withContext(Dispatchers.IO) { execute(request, scope) }
    }

    private fun execute(request: Request, scope: String): JsonObject {
        client.newCall(request).execute().use { response ->
            if (response.code == 412 || response.code == 429) {
                onRateLimited(scope)
                throw BilibiliException(-352, rateLimitMessage(scope))
            }
            if (!response.isSuccessful) {
                throw IOException("网络请求失败（HTTP ${response.code}），请稍后重试。")
            }
            val body = response.body?.string().orEmpty()
            if (body.isEmpty()) throw IOException("B 站返回了空响应，请稍后重试。")
            val root = runCatching { json.parseToJsonElement(body).jsonObject }
                .getOrElse { throw IOException("B 站返回了无法识别的响应，请稍后重试。") }
            val code = root.int("code")
            if (code == -352) {
                onRateLimited(scope)
                throw BilibiliException(-352, rateLimitMessage(scope))
            }
            if (code != 0) throw BilibiliException(code, messageFor(root, code))
            onRequestSucceeded(scope)
            val data = root["data"]
            if (data == null || data is JsonNull) return JsonObject(emptyMap())
            val payload = data.jsonObject
            if (payload.string("v_voucher").isNotEmpty()) {
                throw BilibiliException(-352, "当前请求需要在 B 站官网完成验证，请验证后重试。")
            }
            return payload
        }
    }

    private suspend fun signedQuery(params: Map<String, String>): String {
        val keys = ensureSigningKeys()
        return WbiSigner.sign(params, keys.image, keys.sub, System.currentTimeMillis() / 1000)
    }

    private suspend fun ensureSigningKeys(): SigningKeys {
        signingKeys?.takeIf { System.currentTimeMillis() - it.at < KEY_LIFETIME_MS }?.let { return it }
        return keyMutex.withLock {
            signingKeys?.takeIf { System.currentTimeMillis() - it.at < KEY_LIFETIME_MS }?.let {
                return@withLock it
            }
            rememberKeys(fetchNavData())
            signingKeys ?: throw IOException("暂时无法获取 B 站请求签名，请稍后重试。")
        }
    }

    // 匿名请求 nav 的 code 为 -101，但 data 中仍带有 WBI 密钥，因此这里不校验业务 code。
    private suspend fun fetchNavData(): JsonObject = withContext(Dispatchers.IO) {
        ensureNotBlocked(SCOPE_DEFAULT)
        awaitRequestSlot()
        val request = Request.Builder()
            .url("${API}x/web-interface/nav")
            .header("User-Agent", USER_AGENT)
            .header("Referer", SITE)
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 412 || response.code == 429) {
                onRateLimited(SCOPE_DEFAULT)
                throw BilibiliException(-352, rateLimitMessage(SCOPE_DEFAULT))
            }
            if (!response.isSuccessful) {
                throw IOException("网络请求失败（HTTP ${response.code}），请稍后重试。")
            }
            val body = response.body?.string().orEmpty()
            if (body.isEmpty()) throw IOException("B 站返回了空响应，请稍后重试。")
            val root = runCatching { json.parseToJsonElement(body).jsonObject }
                .getOrElse { throw IOException("B 站返回了无法识别的响应，请稍后重试。") }
            onRequestSucceeded(SCOPE_DEFAULT)
            root["data"] as? JsonObject ?: JsonObject(emptyMap())
        }
    }

    private fun cooldown(scope: String): Cooldown = synchronized(cooldowns) {
        cooldowns.getOrPut(scope) { Cooldown() }
    }

    private fun ensureNotBlocked(scope: String) {
        if (cooldown(scope).until > System.currentTimeMillis()) {
            throw BilibiliException(-352, rateLimitMessage(scope))
        }
    }

    private suspend fun awaitRequestSlot() {
        throttleMutex.withLock {
            val waitMs = lastRequestAt + MIN_REQUEST_INTERVAL_MS - System.currentTimeMillis()
            if (waitMs > 0) delay(waitMs)
            lastRequestAt = System.currentTimeMillis()
        }
    }

    private fun onRateLimited(scope: String) {
        synchronized(cooldowns) {
            val item = cooldowns.getOrPut(scope) { Cooldown() }
            val initial = if (scope == SCOPE_FAVORITES) FAVORITES_INITIAL_BACKOFF_MS else INITIAL_BACKOFF_MS
            val maximum = if (scope == SCOPE_FAVORITES) FAVORITES_MAX_BACKOFF_MS else MAX_BACKOFF_MS
            item.backoff = if (item.backoff == 0L) initial else (item.backoff * 2).coerceAtMost(maximum)
            item.until = System.currentTimeMillis() + item.backoff
            rateLimits?.save(scope, item.until, item.backoff)
        }
    }

    private fun onRequestSucceeded(scope: String) {
        synchronized(cooldowns) {
            val item = cooldowns[scope] ?: return
            if (item.until != 0L && System.currentTimeMillis() >= item.until) {
                item.until = 0L
                item.backoff = 0L
                rateLimits?.clear(scope)
            }
        }
    }

    private fun rateLimitMessage(scope: String): String {
        val remainingMs = (cooldown(scope).until - System.currentTimeMillis()).coerceAtLeast(1_000L)
        val seconds = (remainingMs + 999) / 1000
        val wait = if (seconds >= 60) "${(seconds + 59) / 60} 分钟" else "$seconds 秒"
        val prefix = if (scope == SCOPE_FAVORITES) "收藏夹访问过于频繁" else "访问过于频繁"
        return "$prefix，请 $wait 后再试。"
    }

    private fun rememberKeys(data: JsonObject) {
        val wbi = data.obj("wbi_img") ?: return
        val image = wbi.string("img_url")
        val sub = wbi.string("sub_url")
        if (image.isEmpty() || sub.isEmpty()) return
        val imageKey = image.substringAfterLast('/').substringBeforeLast('.')
        val subKey = sub.substringAfterLast('/').substringBeforeLast('.')
        if (imageKey.length == 32 && subKey.length == 32) {
            signingKeys = SigningKeys(imageKey, subKey, System.currentTimeMillis())
        }
    }

    private fun importCookiesFromUrl(url: String) {
        val rawQuery = runCatching { URI(url).rawQuery.orEmpty() }.getOrDefault("")
        if (rawQuery.isEmpty()) return
        val values = rawQuery.split("&").mapNotNull { part ->
            val separator = part.indexOf('=')
            if (separator <= 0) {
                null
            } else {
                val name = runCatching {
                    URLDecoder.decode(part.substring(0, separator), Charsets.UTF_8.name())
                }.getOrNull()
                val value = runCatching {
                    URLDecoder.decode(part.substring(separator + 1), Charsets.UTF_8.name())
                }.getOrNull()
                if (name != null && value != null) name to value else null
            }
        }.toMap()
        COOKIE_NAMES.forEach { name ->
            val value = values[name] ?: return@forEach
            if (cookieJar.find(name) == null) {
                cookieJar.putIfAbsent(
                    SavedCookie(
                        name = name,
                        value = value,
                        domain = ".bilibili.com",
                        path = "/",
                        expiresAt = System.currentTimeMillis() + COOKIE_LIFETIME_MS,
                    ),
                )
            }
        }
    }

    private fun messageFor(root: JsonObject, code: Int): String = when (code) {
        -101 -> "登录已过期，请重新扫码登录。"
        -111 -> "登录校验失败，请重新扫码登录。"
        -352, -403 -> "B 站拒绝了请求，请稍后重试，或在官网确认访问权限。"
        else -> "B 站返回错误 $code：${root.string("message").ifEmpty { "请求未完成" }}"
    }

    private fun encodeQuery(params: Map<String, String>): String =
        params.entries.joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull ?: ""

    private fun JsonObject.int(key: String): Int =
        (this[key] as? JsonPrimitive)?.intOrNull ?: 0

    private fun JsonObject.long(key: String): Long =
        (this[key] as? JsonPrimitive)?.longOrNull ?: 0L

    private fun JsonObject.bool(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.array(key: String): List<JsonElement> =
        (this[key] as? JsonArray)?.toList() ?: emptyList()

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private const val SITE = "https://www.bilibili.com/"
        private const val API = "https://api.bilibili.com/"
        private const val PASSPORT = "https://passport.bilibili.com/"
        private const val MUSIC_PAGE = "https://www.bilibili.com/c/music/"
        private const val SCOPE_DEFAULT = "default"
        private const val SCOPE_FAVORITES = "favorites"
        private const val MIN_REQUEST_INTERVAL_MS = 250L
        private const val INITIAL_BACKOFF_MS = 20_000L
        private const val MAX_BACKOFF_MS = 5 * 60_000L
        private const val FAVORITES_INITIAL_BACKOFF_MS = 10 * 60_000L
        private const val FAVORITES_MAX_BACKOFF_MS = 30 * 60_000L
        private const val KEY_LIFETIME_MS = 15 * 60 * 1000L
        private const val COOKIE_LIFETIME_MS = 180L * 24 * 60 * 60 * 1000
        private val COOKIE_NAMES = listOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5")

        fun defaultClient(cookieJar: CookieJar): OkHttpClient = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .build()
    }
}
