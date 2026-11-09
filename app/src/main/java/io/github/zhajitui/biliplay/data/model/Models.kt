package io.github.zhajitui.biliplay.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Account(
    val id: Long,
    val name: String,
    val avatar: String,
    val level: Int = 0,
    val isVip: Boolean = false,
)

@Serializable
data class SavedCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val expiresAt: Long,
)

data class LoginCode(
    val url: String,
    val key: String,
)

@Serializable
data class Track(
    val aid: Long,
    val bvid: String,
    val title: String,
    val uploader: String,
    val cover: String,
    val duration: Int,
    val uploaderId: Long = 0,
)

data class UploaderItem(
    val id: Long,
    val name: String,
    val avatar: String,
    val signature: String,
    val fans: Long,
    val videos: Long,
)

data class PageResult<T>(
    val items: List<T>,
    val hasMore: Boolean,
)

data class AudioStream(
    val url: String,
    val mimeType: String,
    val backups: List<String>,
    val quality: String,
    val hasHiRes: Boolean = false,
)
