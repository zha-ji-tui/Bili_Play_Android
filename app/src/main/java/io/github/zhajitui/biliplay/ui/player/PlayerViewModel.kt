package io.github.zhajitui.biliplay.ui.player

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.zhajitui.biliplay.data.model.Account
import io.github.zhajitui.biliplay.data.model.Track
import io.github.zhajitui.biliplay.data.repository.ContentRepository
import io.github.zhajitui.biliplay.playback.PlaybackConnection
import io.github.zhajitui.biliplay.playback.PlaybackProgress
import io.github.zhajitui.biliplay.playback.PlayerUiState
import io.github.zhajitui.biliplay.ui.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class PlayerViewModel(
    private val repository: ContentRepository,
    private val account: Account,
    private val connection: PlaybackConnection,
    private val localUriProvider: (String) -> Uri?,
) : ViewModel() {
    val playback: StateFlow<PlayerUiState> = connection.state
    val progress: StateFlow<PlaybackProgress> = connection.progress

    private val _preparingTrack = MutableStateFlow<Track?>(null)
    val preparingTrack: StateFlow<Track?> = _preparingTrack.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private var playJob: Job? = null

    init {
        viewModelScope.launch {
            connection.errors.collect { message -> _errors.emit(message) }
        }
    }

    fun play(tracks: List<Track>, startIndex: Int) {
        playJob?.cancel()
        val track = tracks.getOrNull(startIndex) ?: return

        // 已下载的歌曲直接播放本地文件，不走网络解析。
        if (localUriProvider(track.bvid) != null) {
            if (!connection.playQueue(tracks, startIndex)) {
                _errors.tryEmit("没有可播放的歌曲")
            }
            return
        }

        playJob = viewModelScope.launch {
            _preparingTrack.value = track
            try {
                val audio = repository.resolveAudio(track, account.isVip)
                if (!connection.playQueue(tracks, startIndex, audio.url)) {
                    _errors.emit("没有可播放的歌曲")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _errors.emit("无法播放：${error.userMessage()}")
            } finally {
                _preparingTrack.value = null
            }
        }
    }

    fun togglePlayPause() = connection.togglePlayPause()

    fun skipToNext() = connection.skipToNext()

    fun skipToPrevious() = connection.skipToPrevious()

    fun cycleRepeatMode() = connection.cycleRepeatMode()

    fun stop() = connection.stop()

    fun seekTo(positionMs: Long) = connection.seekTo(positionMs)

    override fun onCleared() {
        playJob?.cancel()
    }

    companion object {
        fun factory(
            repository: ContentRepository,
            account: Account,
            connection: PlaybackConnection,
            localUriProvider: (String) -> Uri?,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                PlayerViewModel(repository, account, connection, localUriProvider)
            }
        }
    }
}
