package io.github.zhajitui.biliplay.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.zhajitui.biliplay.data.model.Track
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class RepeatMode {
    ListLoop,
    SingleLoop,
    Sequential,
    Shuffle,
}

data class PlayerUiState(
    val hasMedia: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val artworkUrl: String = "",
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.ListLoop,
)

data class PlaybackProgress(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)

class PlaybackConnection(
    context: Context,
    private val localUriProvider: (String) -> Uri? = { null },
) {
    private data class PendingQueue(
        val tracks: List<Track>,
        val startIndex: Int,
        val firstUrl: String?,
    )

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("biliplay.playback", Context.MODE_PRIVATE)

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var pendingQueue: PendingQueue? = null

    private var repeatMode: RepeatMode = runCatching {
        RepeatMode.valueOf(prefs.getString(KEY_REPEAT_MODE, RepeatMode.ListLoop.name).orEmpty())
    }.getOrDefault(RepeatMode.ListLoop)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(PlayerUiState(repeatMode = repeatMode))
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(PlaybackProgress())
    val progress: StateFlow<PlaybackProgress> = _progress.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync()

        override fun onPlayerError(error: PlaybackException) {
            _errors.tryEmit("音源加载失败，请重试")
        }
    }

    init {
        scope.launch {
            while (isActive) {
                if (controller != null) syncProgress()
                delay(500)
            }
        }
    }

    fun playQueue(tracks: List<Track>, startIndex: Int, firstUrl: String? = null): Boolean {
        if (tracks.isEmpty() || startIndex !in tracks.indices) return false
        val items = tracks.mapIndexed { index, track ->
            mediaItem(track, if (index == startIndex) firstUrl else null)
        }
        val current = controller
        if (current != null) {
            current.setMediaItems(items, startIndex, 0L)
            applyRepeatMode(current)
            current.prepare()
            current.play()
            sync()
            return true
        }
        pendingQueue = PendingQueue(tracks, startIndex, firstUrl)
        connect()
        return true
    }

    fun togglePlayPause() {
        val current = controller ?: return
        if (current.isPlaying) current.pause() else current.play()
        sync()
    }

    fun skipToNext() {
        controller?.seekToNextMediaItem()
        sync()
    }

    fun skipToPrevious() {
        controller?.seekToPreviousMediaItem()
        sync()
    }

    fun cycleRepeatMode(): RepeatMode {
        repeatMode = when (repeatMode) {
            RepeatMode.ListLoop -> RepeatMode.SingleLoop
            RepeatMode.SingleLoop -> RepeatMode.Sequential
            RepeatMode.Sequential -> RepeatMode.Shuffle
            RepeatMode.Shuffle -> RepeatMode.ListLoop
        }
        prefs.edit().putString(KEY_REPEAT_MODE, repeatMode.name).apply()
        controller?.let(::applyRepeatMode)
        sync()
        return repeatMode
    }

    fun stop() {
        pendingQueue = null
        controller?.run {
            stop()
            clearMediaItems()
        }
        _progress.value = PlaybackProgress()
        sync()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
        syncProgress()
    }

    private fun connect() {
        if (controllerFuture != null) return
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    handleDisconnected()
                }
            })
            .buildAsync()
        controllerFuture = future
        future.addListener({
            val connected = runCatching { future.get() }.getOrNull()
            if (connected == null) {
                runCatching { MediaController.releaseFuture(future) }
                controllerFuture = null
                controller = null
                pendingQueue = null
                _errors.tryEmit("播放服务连接失败，请重试")
                return@addListener
            }
            controller = connected.also { it.addListener(listener) }
            applyRepeatMode(connected)
            pendingQueue?.let { pending ->
                connected.setMediaItems(
                    pending.tracks.mapIndexed { index, track ->
                        mediaItem(track, if (index == pending.startIndex) pending.firstUrl else null)
                    },
                    pending.startIndex,
                    0L,
                )
                connected.prepare()
                connected.play()
                pendingQueue = null
            }
            sync()
        }, ContextCompat.getMainExecutor(appContext))
    }

    private fun handleDisconnected() {
        controllerFuture?.let { runCatching { MediaController.releaseFuture(it) } }
        controllerFuture = null
        controller = null
        pendingQueue = null
        _state.value = PlayerUiState(repeatMode = repeatMode)
        _progress.value = PlaybackProgress()
        _errors.tryEmit("播放服务已断开，请重试")
    }

    private fun applyRepeatMode(controller: MediaController) {
        controller.repeatMode = when (repeatMode) {
            RepeatMode.SingleLoop -> Player.REPEAT_MODE_ONE
            RepeatMode.Sequential -> Player.REPEAT_MODE_OFF
            RepeatMode.ListLoop, RepeatMode.Shuffle -> Player.REPEAT_MODE_ALL
        }
        controller.shuffleModeEnabled = repeatMode == RepeatMode.Shuffle
    }

    private fun mediaItem(track: Track, resolvedUrl: String? = null): MediaItem {
        val uri = localUriProvider(track.bvid)
            ?: resolvedUrl?.let(Uri::parse)
            ?: Uri.Builder()
                .scheme("bili")
                .authority("audio")
                .appendQueryParameter("bvid", track.bvid)
                .appendQueryParameter("aid", track.aid.toString())
                .build()
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.uploader)
            .apply {
                if (track.cover.isNotEmpty()) setArtworkUri(Uri.parse(track.cover))
            }
            .build()
        return MediaItem.Builder()
            .setMediaId(track.bvid)
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun sync() {
        val current = controller ?: return
        val metadata = current.currentMediaItem?.mediaMetadata
        _state.value = PlayerUiState(
            hasMedia = current.mediaItemCount > 0,
            title = metadata?.title?.toString().orEmpty(),
            artist = metadata?.artist?.toString().orEmpty(),
            artworkUrl = metadata?.artworkUri?.toString().orEmpty(),
            isPlaying = current.isPlaying,
            isBuffering = current.playbackState == Player.STATE_BUFFERING,
            repeatMode = repeatMode,
        )
        syncProgress()
    }

    private fun syncProgress() {
        val current = controller ?: return
        val duration = current.duration.coerceAtLeast(0)
        val position = current.currentPosition.coerceAtLeast(0)
        if (_progress.value.durationMs != duration || _progress.value.positionMs != position) {
            _progress.value = PlaybackProgress(positionMs = position, durationMs = duration)
        }
    }

    private companion object {
        const val KEY_REPEAT_MODE = "repeat_mode"
    }
}
