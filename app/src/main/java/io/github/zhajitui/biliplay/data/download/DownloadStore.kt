package io.github.zhajitui.biliplay.data.download

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import io.github.zhajitui.biliplay.data.model.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class DownloadSort {
    Recent,
    Oldest,
    Title,
    Uploader,
}

@Serializable
data class DownloadedTrack(
    val track: Track,
    val uri: String,
    val savedAt: Long,
)

class DownloadStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("biliplay.downloads", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun get(bvid: String): DownloadedTrack? {
        val raw = prefs.getString("item:$bvid", null) ?: return null
        return runCatching { json.decodeFromString<DownloadedTrack>(raw) }.getOrNull()
    }

    fun uriOf(bvid: String): String? = get(bvid)?.uri ?: prefs.getString("uri:$bvid", null)

    fun put(item: DownloadedTrack) {
        prefs.edit()
            .putString("item:${item.track.bvid}", json.encodeToString(item))
            .remove("uri:${item.track.bvid}")
            .apply()
    }

    fun remove(bvid: String) {
        prefs.edit()
            .remove("item:$bvid")
            .remove("uri:$bvid")
            .apply()
    }

    fun sortOrder(): DownloadSort = runCatching {
        DownloadSort.valueOf(
            prefs.getString("sort", DownloadSort.Recent.name).orEmpty(),
        )
    }.getOrDefault(DownloadSort.Recent)

    fun saveSortOrder(order: DownloadSort) {
        prefs.edit().putString("sort", order.name).apply()
    }

    fun all(): List<DownloadedTrack> {
        val items = prefs.all.entries.mapNotNull { (key, value) ->
            if (!key.startsWith("item:") || value !is String) return@mapNotNull null
            runCatching { json.decodeFromString<DownloadedTrack>(value) }.getOrNull()
        }
        val legacy = prefs.all.entries.mapNotNull { (key, value) ->
            if (!key.startsWith("uri:") || value !is String) return@mapNotNull null
            val bvid = key.removePrefix("uri:")
            if (get(bvid) != null) return@mapNotNull null
            legacyItem(bvid, value)
        }
        return (items + legacy).sortedByDescending { it.savedAt }
    }

    private fun legacyItem(bvid: String, uri: String): DownloadedTrack {
        val metadata = readMetadata(Uri.parse(uri))
        return DownloadedTrack(
            track = Track(
                aid = 0,
                bvid = bvid,
                title = metadata.title.ifBlank { "已下载音频" },
                uploader = metadata.artist,
                cover = "",
                duration = metadata.durationSeconds,
            ),
            uri = uri,
            savedAt = 0,
        )
    }

    private data class AudioMetadata(
        val title: String,
        val artist: String,
        val durationSeconds: Int,
    )

    private fun readMetadata(uri: Uri): AudioMetadata {
        var title = ""
        var artist = ""
        var durationMs = 0L
        var displayName = ""

        runCatching {
            appContext.contentResolver.query(
                uri,
                arrayOf(
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    title = cursor.getString(0).orEmpty()
                    artist = cursor.getString(1).orEmpty()
                    durationMs = cursor.getLong(2)
                    displayName = cursor.getString(3).orEmpty()
                }
            }
        }

        if (durationMs <= 0) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(appContext, uri)
                    durationMs = retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                    if (title.isBlank()) {
                        title = retriever
                            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                            .orEmpty()
                    }
                    if (artist.isBlank()) {
                        artist = retriever
                            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                            .orEmpty()
                    }
                } finally {
                    retriever.release()
                }
            }
        }

        if (title.isBlank()) {
            title = displayName.substringBeforeLast('.').ifBlank { "已下载音频" }
        }
        return AudioMetadata(title, artist, (durationMs / 1000).toInt())
    }
}
