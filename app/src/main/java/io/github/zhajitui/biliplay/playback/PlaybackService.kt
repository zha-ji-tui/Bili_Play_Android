package io.github.zhajitui.biliplay.playback

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.github.zhajitui.biliplay.BiliPlayApplication

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val container = (application as BiliPlayApplication).container
        val upstream = DefaultDataSource.Factory(
            this,
            OkHttpDataSource.Factory(container.playbackHttpClient),
        )
        val dataSourceFactory = ResolvingDataSource.Factory(
            upstream,
            ResolvingDataSource.Resolver { dataSpec ->
                if (dataSpec.uri.scheme == "bili") {
                    dataSpec.withUri(container.playbackUrlResolver.resolve(dataSpec.uri))
                } else {
                    dataSpec
                }
            },
        )
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING) {
                    prefetchUpcoming(player, container)
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                prefetchUpcoming(player, container)
            }
        })
        mediaSession = MediaSession.Builder(this, player).build()
    }

    private fun prefetchUpcoming(player: Player, container: io.github.zhajitui.biliplay.di.AppContainer) {
        val count = player.mediaItemCount
        val current = player.currentMediaItemIndex
        if (count <= 1 || current < 0) return
        val uris = (1..PREFETCH_AHEAD).mapNotNull { offset ->
            val index = current + offset
            if (index in 0 until count) {
                player.getMediaItemAt(index).localConfiguration?.uri
            } else {
                null
            }
        }
        if (uris.isNotEmpty()) container.playbackUrlResolver.prefetch(uris)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0 ||
            player.playbackState == Player.STATE_ENDED
        ) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private companion object {
        const val PREFETCH_AHEAD = 2
    }
}
