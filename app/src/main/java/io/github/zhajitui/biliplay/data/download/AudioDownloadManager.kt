package io.github.zhajitui.biliplay.data.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import io.github.zhajitui.biliplay.data.model.Track
import io.github.zhajitui.biliplay.data.repository.ContentRepository
import io.github.zhajitui.biliplay.data.repository.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

sealed interface DownloadState {
    data class Downloading(val progress: Float? = null) : DownloadState
    data class Done(val uri: Uri) : DownloadState
    data class Failed(val message: String) : DownloadState
}

class AudioDownloadManager(
    context: Context,
    private val repository: ContentRepository,
    private val accountState: StateFlow<SessionState>,
    private val client: OkHttpClient,
    private val store: DownloadStore,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slots = Semaphore(2)
    private val active = mutableSetOf<String>()

    private val _downloads = MutableStateFlow<List<DownloadedTrack>>(emptyList())
    val downloads: StateFlow<List<DownloadedTrack>> = _downloads.asStateFlow()

    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private var enrichmentStarted = false

    init {
        scope.launch {
            val items = store.all()
            _downloads.update { current ->
                (items + current)
                    .distinctBy { it.track.bvid }
                    .sortedByDescending { it.savedAt }
            }
            _states.update { current ->
                items.associate { item ->
                    item.track.bvid to DownloadState.Done(Uri.parse(item.uri))
                } + current
            }
            _ready.value = true
        }
    }

    fun enrichMissingMetadata() {
        synchronized(this) {
            if (enrichmentStarted) return
            enrichmentStarted = true
        }
        scope.launch {
            var requestIndex = 0
            store.all().forEach { item ->
                if (item.track.cover.isNotEmpty() && item.track.uploader.isNotEmpty()) return@forEach
                if (requestIndex++ > 0) delay(600)
                val summary = runCatching { repository.videoSummary(item.track.bvid) }.getOrNull()
                    ?: return@forEach
                val updated = item.copy(
                    track = summary.copy(aid = item.track.aid, bvid = item.track.bvid),
                )
                store.put(updated)
                _downloads.update { list ->
                    list.map { saved ->
                        if (saved.track.bvid == updated.track.bvid) updated else saved
                    }
                }
                _states.update {
                    it + (updated.track.bvid to DownloadState.Done(Uri.parse(updated.uri)))
                }
            }
        }
    }

    fun sortOrder(): DownloadSort = store.sortOrder()

    fun setSortOrder(order: DownloadSort) = store.saveSortOrder(order)

    fun download(track: Track) {
        synchronized(active) {
            if (track.bvid in active) return
            if (_states.value[track.bvid] is DownloadState.Done) return
            active += track.bvid
        }
        _states.update { it + (track.bvid to DownloadState.Downloading()) }
        scope.launch {
            try {
                slots.withPermit {
                    val isVip = (accountState.value as? SessionState.LoggedIn)?.account?.isVip == true
                    val audio = repository.resolveAudio(track, isVip)
                    val uri = saveFile(track, audio.url, audio.mimeType)
                    val item = DownloadedTrack(track, uri.toString(), System.currentTimeMillis())
                    store.put(item)
                    _states.update { it + (track.bvid to DownloadState.Done(uri)) }
                    _downloads.update { list ->
                        listOf(item) + list.filterNot { saved -> saved.track.bvid == track.bvid }
                    }
                    _events.tryEmit("已保存到 音乐/BiliPlay")
                }
            } catch (error: Exception) {
                _states.update {
                    it + (track.bvid to DownloadState.Failed(error.message ?: "下载失败"))
                }
                _events.tryEmit("下载失败，请重试")
            } finally {
                synchronized(active) { active -= track.bvid }
            }
        }
    }

    fun delete(bvid: String) {
        synchronized(active) {
            if (bvid in active) return
        }
        val uri = store.uriOf(bvid) ?: return
        scope.launch {
            val deleteResult = runCatching {
                appContext.contentResolver.delete(Uri.parse(uri), null, null)
            }
            if (deleteResult.isFailure) {
                _events.tryEmit("删除本地文件失败，请重试")
                return@launch
            }
            store.remove(bvid)
            _states.update { it - bvid }
            _downloads.update { list -> list.filterNot { item -> item.track.bvid == bvid } }
            _events.tryEmit("已删除本地文件")
        }
    }

    fun localUri(bvid: String): Uri? = store.uriOf(bvid)?.let(Uri::parse)

    private fun saveFile(track: Track, url: String, mimeType: String): Uri {
        val isFlac = mimeType.contains("flac", ignoreCase = true)
        val extension = if (isFlac) "flac" else "m4a"
        val base = buildString {
            append(track.title.ifBlank { track.bvid })
            if (track.uploader.isNotBlank()) {
                append(" - ")
                append(track.uploader)
            }
        }
            .replace(Regex("[\\\\/:*?\"<>|]"), " ")
            .trim()
            .take(100)
            .ifBlank { track.bvid }
        val displayName = "$base.$extension"

        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, if (isFlac) "audio/flac" else "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/BiliPlay")
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            put(MediaStore.Audio.Media.TITLE, track.title)
            put(MediaStore.Audio.Media.ARTIST, track.uploader)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法创建音乐文件")

        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("下载失败（HTTP ${response.code}）")
                val body = response.body ?: throw IOException("下载失败")
                val total = body.contentLength().takeIf { it > 0 }
                var copied = 0L
                var lastPercent = -10
                resolver.openOutputStream(uri)?.use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            val percent = total?.let { ((copied * 100) / it).toInt() }
                            if (percent != null && percent - lastPercent >= 10) {
                                lastPercent = percent
                                _states.update {
                                    it + (track.bvid to DownloadState.Downloading(percent / 100f))
                                }
                            }
                        }
                    }
                } ?: throw IOException("无法写入音乐文件")
            }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            return uri
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }
}
