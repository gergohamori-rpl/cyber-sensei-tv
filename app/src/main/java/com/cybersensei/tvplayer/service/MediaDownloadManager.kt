package com.cybersensei.tvplayer.service

import android.content.Context
import android.util.Log
import com.cybersensei.tvplayer.CyberSenseiApp
import com.cybersensei.tvplayer.data.api.ApiClient
import com.cybersensei.tvplayer.data.db.MediaFileEntity
import com.cybersensei.tvplayer.data.model.PlaylistItem
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
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
    private val fallbackDir = File(context.filesDir, "fallback").also { it.mkdirs() }
    private val prefs = context.getSharedPreferences("cyber_sensei_tv", Context.MODE_PRIVATE)
    private var syncJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val syncMutex = Mutex()

    var onPlaylistUpdated: (() -> Unit)? = null
    var onOfflineDetected: (() -> Unit)? = null
    var onOnlineRestored: (() -> Unit)? = null

    private var consecutiveFailures = 0
    private var wasOffline = false
    private val OFFLINE_THRESHOLD = 3

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
                val syncSuccess = try {
                    syncPlaylist()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(tag, "Sync error", e)
                    LogCollector.error("sync", "Playlist sync failed: ${e.message}", errorCode = "SYNC_ERROR")
                    false
                }

                if (syncSuccess) {
                    if (wasOffline) {
                        wasOffline = false
                        consecutiveFailures = 0
                        onOnlineRestored?.invoke()
                    } else {
                        consecutiveFailures = 0
                    }
                } else {
                    consecutiveFailures++
                    if (consecutiveFailures >= OFFLINE_THRESHOLD && !wasOffline) {
                        wasOffline = true
                        onOfflineDetected?.invoke()
                    }
                }

                delay(intervalMs)
            }
        }
    }

    fun stop() {
        syncJob?.cancel()
        scope.cancel()
    }

    // Returns true if server was reachable, false on network/HTTP error
    suspend fun syncPlaylist(): Boolean = syncMutex.withLock { syncPlaylistLocked() }

    private suspend fun syncPlaylistLocked(): Boolean {
        LogCollector.info("sync", "Starting playlist sync")

        val response = try {
            api.getPlaylist()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogCollector.warn("sync", "Playlist sync network error: ${e.message}")
            return false
        }

        if (!response.isSuccessful) {
            LogCollector.warn("sync", "Playlist sync HTTP error: ${response.code()}")
            return false
        }

        val body = response.body() ?: return false

        // Always sync fallback items regardless of playlist availability
        syncFallbackItems(body.fallbackItems)

        val playlist = body.playlist
        if (playlist == null) {
            LogCollector.warn("sync", "No playlist available from server")
            return true // Server was reachable, just no playlist configured
        }

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
        val localItemIds = localFiles.filter {
            File(it.filePath).let { file -> file.isFile && file.length() > 0 }
        }.map { it.mediaItemId }.toSet()

        val toDownload = serverItems.filter { it.mediaItemId !in localItemIds }
        val toDelete = localFiles.filter { it.mediaItemId !in serverItemIds }

        val sortedDownloads = toDownload.sortedBy { it.fileSize ?: Long.MAX_VALUE }

        for (item in sortedDownloads) {
            downloadWithRetry(item)
        }

        // Keep the last usable cache if every replacement failed to download.
        val canReplace = serverItems.isEmpty() || dao.getAll().any {
            it.mediaItemId in serverItemIds && File(it.filePath).let { file -> file.isFile && file.length() > 0 }
        }
        for (entity in if (canReplace) toDelete else emptyList()) {
            try {
                File(entity.filePath).delete()
                dao.delete(entity)
                LogCollector.info("sync", "Removed deleted media: ${entity.fileName}", details = mapOf("mediaItemId" to entity.mediaItemId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogCollector.error("sync", "Failed to delete media: ${entity.fileName}", errorCode = "DELETE_ERROR")
            }
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

        return true
    }

    // ─── Fallback media management ────────────────────────────────────────────

    private suspend fun syncFallbackItems(items: List<PlaylistItem>) {
        if (items.isEmpty()) {
            fallbackDir.listFiles()?.forEach { it.delete() }
            prefs.edit().remove("fallback_files").apply()
            return
        }

        val currentPaths = getFallbackPaths().associateBy { it.first }
        val newIds = items.map { it.mediaItemId }.toSet()

        // Delete stale fallback files
        for ((id, path, _) in currentPaths.values) {
            if (id !in newIds) {
                File(path).delete()
            }
        }

        // Download any missing fallback items
        for (item in items) {
            val existing = currentPaths[item.mediaItemId]
            if (existing == null || !File(existing.second).exists()) {
                try {
                    downloadFallbackFile(item)
                } catch (e: Exception) {
                    LogCollector.error("fallback", "Failed to download fallback: ${item.fileName}", errorCode = "FALLBACK_DOWNLOAD_ERROR")
                }
            }
        }

        saveFallbackPaths(items)
    }

    private suspend fun downloadFallbackFile(item: PlaylistItem): Boolean {
        val targetFile = File(fallbackDir, "${item.mediaItemId}_${item.fileName}")
        if (targetFile.exists() && targetFile.length() > 0) return true

        val tempFile = File(fallbackDir, "${item.mediaItemId}_${item.fileName}.tmp")
        val response = try {
            api.downloadMedia(item.mediaItemId, null)
        } catch (e: Exception) {
            return false
        }
        if (!response.isSuccessful) return false
        val body = response.body() ?: return false

        withContext(Dispatchers.IO) {
            FileOutputStream(tempFile).use { fos ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        fos.write(buffer, 0, bytesRead)
                    }
                }
            }
        }
        if (tempFile.exists() && tempFile.length() > 0) {
            tempFile.renameTo(targetFile)
            LogCollector.info("fallback", "Downloaded fallback: ${item.fileName}", details = mapOf("mediaItemId" to item.mediaItemId))
            return true
        }
        return false
    }

    // Returns list of Triple(mediaItemId, filePath, mediaType)
    private fun getFallbackPaths(): List<Triple<Int, String, String>> {
        val json = prefs.getString("fallback_files", "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                Triple(obj.getInt("id"), obj.getString("path"), obj.getString("mediaType"))
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun saveFallbackPaths(items: List<PlaylistItem>) {
        val arr = JSONArray()
        for (item in items) {
            val file = File(fallbackDir, "${item.mediaItemId}_${item.fileName}")
            if (file.exists() && file.length() > 0) {
                val obj = JSONObject()
                obj.put("id", item.mediaItemId)
                obj.put("path", file.absolutePath)
                obj.put("mediaType", item.mediaType)
                arr.put(obj)
            }
        }
        prefs.edit().putString("fallback_files", arr.toString()).apply()
    }

    /** Returns locally-cached fallback files as MediaFileEntity list for PlaybackService. */
    fun getFallbackLocalFiles(): List<MediaFileEntity> {
        return getFallbackPaths()
            .filter { File(it.second).exists() && File(it.second).length() > 0 }
            .map { (id, path, mediaType) ->
                MediaFileEntity(
                    mediaItemId = id,
                    fileName = File(path).name,
                    filePath = path,
                    fileSize = File(path).length(),
                    mediaType = mediaType
                )
            }
    }

    fun hasFallbackMedia(): Boolean = getFallbackLocalFiles().isNotEmpty()

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
            } catch (e: CancellationException) {
                throw e
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
            }
            if (attempt < maxRetries - 1) delay(delays[attempt])
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
                return false // Retry loop will start cleanly; never recurse on repeated 416.
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

        if (tempFile.length() == 0L || !tempFile.renameTo(targetFile)) {
            throw java.io.IOException("Could not commit downloaded media: ${item.fileName}")
        }

        dao.insert(
            MediaFileEntity(
                id = dao.getByMediaItemId(item.mediaItemId)?.id ?: 0,
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
        return dao.getAll().filter { File(it.filePath).let { file -> file.isFile && file.length() > 0 } }
    }

    fun getMediaDir(): File = mediaDir
}
