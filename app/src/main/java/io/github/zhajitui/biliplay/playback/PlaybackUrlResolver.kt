package io.github.zhajitui.biliplay.playback

import android.net.Uri
import io.github.zhajitui.biliplay.data.api.BilibiliApi
import io.github.zhajitui.biliplay.data.model.Track
import io.github.zhajitui.biliplay.data.repository.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class PlaybackUrlResolver(
    private val api: BilibiliApi,
    private val accountState: StateFlow<SessionState>,
) {
    private data class CachedUrl(val url: String, val at: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = ConcurrentHashMap<String, CachedUrl>()
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<Uri>>()

    fun resolve(uri: Uri): Uri {
        val bvid = uri.getQueryParameter("bvid") ?: throw IOException("缺少音频标识")
        val aid = uri.getQueryParameter("aid")?.toLongOrNull() ?: 0L
        cachedUrl(bvid)?.let { return Uri.parse(it) }

        val future = inFlight.computeIfAbsent(bvid) { startResolve(bvid, aid) }
        return try {
            future.get(RESOLVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (error: Exception) {
            inFlight.remove(bvid)
            throw IOException(
                "音源解析失败：${error.cause?.message ?: error.message ?: "请稍后重试"}",
                error,
            )
        }
    }

    fun prefetch(uris: List<Uri>) {
        uris.forEach { uri ->
            if (uri.scheme != "bili") return@forEach
            val bvid = uri.getQueryParameter("bvid") ?: return@forEach
            if (cachedUrl(bvid) != null || inFlight.containsKey(bvid)) return@forEach
            val aid = uri.getQueryParameter("aid")?.toLongOrNull() ?: 0L
            inFlight.computeIfAbsent(bvid) { startResolve(bvid, aid) }
        }
    }

    private fun cachedUrl(bvid: String): String? {
        val item = cache[bvid] ?: return null
        if (System.currentTimeMillis() - item.at <= CACHE_TTL_MS) return item.url
        cache.remove(bvid)
        inFlight.remove(bvid)
        return null
    }

    private fun startResolve(bvid: String, aid: Long): CompletableFuture<Uri> {
        val future = CompletableFuture<Uri>()
        scope.launch {
            try {
                val isVip = (accountState.value as? SessionState.LoggedIn)?.account?.isVip == true
                val stream = api.resolveAudio(
                    track = Track(
                        aid = aid,
                        bvid = bvid,
                        title = "",
                        uploader = "",
                        cover = "",
                        duration = 0,
                    ),
                    isVip = isVip,
                )
                val uri = Uri.parse(stream.url)
                cache[bvid] = CachedUrl(stream.url, System.currentTimeMillis())
                inFlight.remove(bvid)
                future.complete(uri)
            } catch (error: Exception) {
                inFlight.remove(bvid)
                future.completeExceptionally(error)
            }
        }
        return future
    }

    private companion object {
        const val CACHE_TTL_MS = 10 * 60 * 1000L
        const val RESOLVE_TIMEOUT_SECONDS = 20L
    }
}
