package io.github.zhajitui.biliplay.data.repository

import io.github.zhajitui.biliplay.data.api.BilibiliApi
import io.github.zhajitui.biliplay.data.model.AudioStream
import io.github.zhajitui.biliplay.data.model.PageResult
import io.github.zhajitui.biliplay.data.model.Track
import io.github.zhajitui.biliplay.data.model.UploaderItem

class ContentRepository(
    private val api: BilibiliApi,
) {
    suspend fun homeFeed(page: Int): PageResult<Track> = api.getHomeFeed(page)

    suspend fun searchVideos(query: String, page: Int): PageResult<Track> =
        api.searchVideos(query, page)

    suspend fun searchUploaders(query: String, page: Int): PageResult<UploaderItem> =
        api.searchUploaders(query, page)

    suspend fun videoSummary(bvid: String): Track? = api.getVideoSummary(bvid)

    suspend fun resolveAudio(track: Track, isVip: Boolean): AudioStream =
        api.resolveAudio(track, isVip)
}
