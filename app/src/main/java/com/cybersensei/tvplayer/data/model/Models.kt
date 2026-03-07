package com.cybersensei.tvplayer.data.model

import com.google.gson.annotations.SerializedName

data class PlaylistResponse(
    val playlist: PlaylistData?
)

data class PlaylistData(
    val id: Int,
    val name: String,
    val shuffle: Boolean,
    val loop: Boolean,
    val transitionType: String,
    val transitionDurationMs: Int,
    val imageDurationSec: Int,
    val items: List<PlaylistItem>
)

data class PlaylistItem(
    val id: Int,
    val position: Int,
    val mediaUrl: String?,
    val fileName: String,
    val fileSize: Long?,
    val mediaType: String,
    val customDurationSec: Int?,
    val mediaItemId: Int,
    val caption: String?,
    val thumbnailUrl: String?
)

data class HeartbeatRequest(
    val currentMediaId: Int?,
    val currentMediaTitle: String?,
    val appVersion: String?,
    val storageUsedBytes: Long?,
    val storageFreeBytes: Long?,
    val timezone: String?,
    val ipAddress: String?,
    val deviceModel: String?,
    val androidVersion: String?,
    val logs: List<LogEntry>
)

data class HeartbeatResponse(
    val serverTime: String,
    val timestamp: Long,
    val hasUpdate: Boolean,
    val latestVersion: LatestVersionInfo?
)

data class LatestVersionInfo(
    val version: String,
    val versionCode: Int,
    val fileSize: Long
)

data class LogEntry(
    val logType: String,
    val severity: String,
    val message: String,
    val details: Map<String, Any?>? = null,
    val mediaItemId: Int? = null,
    val fileName: String? = null,
    val fileSize: Long? = null,
    val durationMs: Int? = null,
    val success: Boolean? = null,
    val errorCode: String? = null,
    val retryCount: Int? = null,
    val deviceTimestamp: String? = null
)

data class UpdateCheckResponse(
    val hasUpdate: Boolean,
    val version: String? = null,
    val versionCode: Int? = null,
    val fileSize: Long? = null,
    val releaseNotes: String? = null
)

data class TimeResponse(
    val serverTime: String,
    val timestamp: Long
)
