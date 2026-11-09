package io.github.zhajitui.biliplay.di

import android.content.Context
import io.github.zhajitui.biliplay.data.api.BilibiliApi
import io.github.zhajitui.biliplay.data.api.PersistentCookieJar
import io.github.zhajitui.biliplay.data.download.AudioDownloadManager
import io.github.zhajitui.biliplay.data.download.DownloadStore
import io.github.zhajitui.biliplay.data.local.CookieSessionWriter
import io.github.zhajitui.biliplay.data.local.RateLimitStore
import io.github.zhajitui.biliplay.data.local.SearchHistoryStore
import io.github.zhajitui.biliplay.data.local.SecureSessionStore
import io.github.zhajitui.biliplay.data.repository.AccountRepository
import io.github.zhajitui.biliplay.data.repository.ContentRepository
import io.github.zhajitui.biliplay.playback.PlaybackConnection
import io.github.zhajitui.biliplay.playback.PlaybackUrlResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

class AppContainer(context: Context) {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionStore = SecureSessionStore(context)
    private val rateLimitStore = RateLimitStore(context)
    private val downloadStore = DownloadStore(context)
    private val cookieSessionWriter = CookieSessionWriter(sessionStore)
    private val cookieJar = PersistentCookieJar { cookies ->
        cookieSessionWriter.schedule(cookies)
    }
    private val api = BilibiliApi(cookieJar, rateLimits = rateLimitStore)

    val accountRepository = AccountRepository(api, sessionStore, applicationScope)
    val contentRepository = ContentRepository(api)
    val searchHistoryStore = SearchHistoryStore(context)
    val playbackUrlResolver = PlaybackUrlResolver(api, accountRepository.state)

    val imageHttpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", BilibiliApi.USER_AGENT)
                .header("Referer", "https://www.bilibili.com/")
                .build()
            chain.proceed(request)
        }
        .build()

    val playbackHttpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", BilibiliApi.USER_AGENT)
                .header("Referer", "https://www.bilibili.com/")
                .build()
            chain.proceed(request)
        }
        .build()

    val audioDownloadManager = AudioDownloadManager(
        context = context,
        repository = contentRepository,
        accountState = accountRepository.state,
        client = playbackHttpClient,
        store = downloadStore,
    )

    val playbackConnection = PlaybackConnection(context) { bvid ->
        audioDownloadManager.localUri(bvid)
    }
}
