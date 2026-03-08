package com.cybersensei.tvplayer.service

import android.content.Context
import android.util.Log
import com.cybersensei.tvplayer.CyberSenseiApp
import com.cybersensei.tvplayer.data.api.ApiClient
import com.cybersensei.tvplayer.data.db.MediaFileEntity
import com.cybersensei.tvplayer.data.model.PlaylistItem
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

class MediaDownloadManager(
    private val context: Context,
    private val baseUrl: String,
    private val apiKey: String
) {
    private val tag = "MediaDownloadManager"
    private val api = ApiClient.getApi(baseUrl, apiKey)
    private val dao = CyberSenseiApp.instance.database.mediaFileDao()
    private val mediaDir = File(context.filesDir, "media").also { it.mkdirs() }
    private var syncJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var onPlaylistUpdated: (() -> Unit)? = null
    private var initialSyncDone = false
    var currentPlaylistItems: List<PlaylistItem> = emptyList()
        private set
    var shuffle: Boolean = false
        private set
    var loop: Boolean = true
        private set
    var imageDurationSec: Int = 10
        private set
    var showMediaTitle: Boolean = true
        private set
    var muteAudio: Boolean = false
        private set
    var videoRotation: Int = 0
        private set

    fun startPeriodicSync(intervalMs: Long = 60000L) {
        syncJob?.cancel()
        syncJob = scope.launch {
            while (isActive) {
                try {
                    syncPlaylist()
                } catch (e: Exception) {
                    Log.e(tag, "Sync error", e)
                    LogCollector.error("sync", "Playlist sync failed: ${e.message}", errorCode = "SYNC_ERROR")
                }
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        syncJob?.cancel()
        scope.cancel()
    }

    suspend fun syncPlaylist() {
        LogCollector.info("sync", "Starting playlist sync")
        val response = api.getPlaylist()
        if (!response.isSuccessful || response.body()?.playlist == null) {
            LogCollector.warn("sync", "No playlist available from server")
            return
        }

        val playlist = response.body()!!.playlist!!
        val serverItems = playlist.items

        val prevMuteAudio = muteAudio
        val prevVideoRotation = videoRotation

        shuffle = playlist.shuffle
        loop = playlist.loop
        imageDurationSec = playlist.imageDurationSec
        showMediaTitle = playlist.showMediaTitle
        muteAudio = playlist.muteAudio
        videoRotation = playlist.videoRotation
        currentPlaylistItems = serverItems

        val localFiles = dao.getAll()
        val serverItemIds = serverItems.map { it.mediaItemId }.toSet()
        val localItemIds = localFiles.map { it.mediaItemId }.toSet()

        val toDownload = serverItems.filter { it.mediaItemId !in localItemIds }
        val toDelete = localFiles.filter { it.mediaItemId !in serverItemIds }

        for (entity in toDelete) {
            try {
                File(entity.filePath).delete()
                dao.delete(entity)
                LogCollector.info("sync", "Removed deleted media: ${entity.fileName}", details = mapOf("mediaItemId" to entity.mediaItemId))
            } catch (e: Exception) {
                LogCollector.error("sync", "Failed to delete media: ${entity.fileName}", errorCode = "DELETE_ERROR")
            }
        }

        val sortedDownloads = toDownload.sortedBy { it.fileSize ?: Long.MAX_VALUE }

        for (item in sortedDownloads) {
            downloadWithRetry(item)
        }

        val settingsChanged = muteAudio != prevMuteAudio || videoRotation != prevVideoRotation
        val itemsChanged = toDownload.isNotEmpty() || toDelete.isNotEmpty()
        if (itemsChanged || settingsChanged || !initialSyncDone) {
            if (settingsChanged) {
                LogCollector.info("sync", "Settings changed", details = mapOf(
                    "muteAudio" to muteAudio,
                    "videoRotation" to videoRotation
                ))
            }
            initialSyncDone = true
            onPlaylistUpdated?.invoke()
        }

        val count = dao.getCount()
        val totalSize = dao.getTotalSize() ?: 0
        LogCollector.info("sync", "Sync complete", details = mapOf(
            "cachedFiles" to count,
            "totalSizeBytes" to totalSize,
            "downloaded" to toDownload.size,
            "deleted" to toDelete.size
        ))
    }

    private suspend fun downloadWithRetry(item: PlaylistItem, maxRetries: Int = 3) {
        val delays = longArrayOf(5000, 15000, 45000)
        for (attempt in 0 until maxRetries) {
            val startTime = System.currentTimeMillis()
            try {
                val success = downloadFile(item)
                val durationMs = (System.currentTimeMillis() - startTime).toInt()
                if (success) {
                    LogCollector.download(
                        message = "Downloaded: ${item.fileName}",
                        fileName = item.fileName,
                        fileSize = item.fileSize,
                        durationMs = durationMs,
                        success = true,
                        mediaItemId = item.mediaItemId,
                        retryCount = attempt
                    )
                    return
                }
            } catch (e: Exception) {
                val durationMs = (System.currentTimeMillis() - startTime).toInt()
                LogCollector.download(
                    message = "Download failed: ${item.fileName} - ${e.message}",
                    fileName = item.fileName,
                    fileSize = item.fileSize,
                    durationMs = durationMs,
                    success = false,
                    mediaItemId = item.mediaItemId,
                    errorCode = e.javaClass.simpleName,
                    retryCount = attempt
                )
                if (attempt < maxRetries - 1) {
                    delay(delays[attempt])
                }
            }
        }
        LogCollector.error("download", "Gave up downloading: ${item.fileName} after $maxRetries attempts",
            errorCode = "MAX_RETRIES", details = mapOf("mediaItemId" to item.mediaItemId))
    }

    private suspend fun downloadFile(item: PlaylistItem): Boolean {
        val targetFile = File(mediaDir, "${item.mediaItemId}_${item.fileName}")
        val tempFile = File(mediaDir, "${item.mediaItemId}_${item.fileName}.tmp")

        var rangeHeader: String? = null
        var existingBytes = 0L
        if (tempFile.exists()) {
            existingBytes = tempFile.length()
            rangeHeader = "bytes=$existingBytes-"
        }

        val response = api.downloadMedia(item.mediaItemId, rangeHeader)
        if (!response.isSuccessful) {
            if (response.code() == 416) {
                tempFile.delete()
                return downloadFile(item)
            }
            return false
        }

        val body = response.body() ?: return false
        val append = response.code() == 206

        withContext(Dispatchers.IO) {
            FileOutputStream(tempFile, append).use { fos ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        fos.write(buffer, 0, bytesRead)
                    }
                }
            }
        }

        tempFile.renameTo(targetFile)

        dao.insert(
            MediaFileEntity(
                mediaItemId = item.mediaItemId,
                fileName = item.fileName,
                filePath = targetFile.absolutePath,
                fileSize = targetFile.length(),
                mediaType = item.mediaType
            )
        )
        return true
    }

    suspend fun getLocalMediaFiles(): List<MediaFileEntity> {
        return dao.getAll()
    }

    fun getMediaDir(): File = mediaDir
}
