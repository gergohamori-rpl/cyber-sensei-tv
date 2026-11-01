package com.cybersensei.tvplayer.service

import com.cybersensei.tvplayer.data.model.LogEntry
import com.cybersensei.tvplayer.util.TimeSync
import java.text.SimpleDateFormat
import java.util.*

object LogCollector {

    private val buffer = PendingLogs<LogEntry>()
    private val lock = Any()
    private var diskFile: android.util.AtomicFile? = null
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val gson = com.google.gson.Gson()

    fun initialize(context: android.content.Context) {
        synchronized(lock) {
            diskFile = android.util.AtomicFile(java.io.File(context.filesDir, "pending-tv-logs.json"))
            try {
                diskFile!!.openRead().bufferedReader().use { reader ->
                    gson.fromJson(reader, Array<LogEntry>::class.java)?.forEach { buffer.add(it) }
                }
            } catch (_: java.io.FileNotFoundException) {
                // First launch has no persisted queue.
            } catch (e: Exception) {
                android.util.Log.w("LogCollector", "Cannot restore pending TV logs", e)
            }
        }
    }

    private fun persist() {
        val snapshot = buffer.snapshot()
        val file = diskFile ?: return
        writer.execute {
            var stream: java.io.FileOutputStream? = null
            try {
                stream = file.startWrite()
                stream.write(gson.toJson(snapshot).toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (e: Exception) {
                stream?.let { file.failWrite(it) }
                android.util.Log.w("LogCollector", "Cannot persist pending TV logs", e)
            }
        }
    }

    fun log(
        logType: String,
        severity: String,
        message: String,
        details: Map<String, Any?>? = null,
        mediaItemId: Int? = null,
        fileName: String? = null,
        fileSize: Long? = null,
        durationMs: Int? = null,
        success: Boolean? = null,
        errorCode: String? = null,
        retryCount: Int? = null
    ) {
        val entry = LogEntry(
            logType = logType,
            severity = severity,
            message = message,
            details = details,
            mediaItemId = mediaItemId,
            fileName = fileName,
            fileSize = fileSize,
            durationMs = durationMs,
            success = success,
            errorCode = errorCode,
            retryCount = retryCount,
            deviceTimestamp = getTimestamp()
        )
        synchronized(lock) {
            buffer.add(entry)
            persist()
        }
    }

    fun pending(): List<LogEntry> = synchronized(lock) { buffer.snapshot() }

    fun acknowledge(entries: List<LogEntry>) {
        synchronized(lock) {
            buffer.acknowledge(entries)
            persist()
        }
    }

    fun info(logType: String, message: String, details: Map<String, Any?>? = null) {
        log(logType, "info", message, details)
    }

    fun warn(logType: String, message: String, details: Map<String, Any?>? = null) {
        log(logType, "warning", message, details)
    }

    fun error(logType: String, message: String, errorCode: String? = null, details: Map<String, Any?>? = null) {
        log(logType, "error", message, details, errorCode = errorCode)
    }

    fun download(
        message: String,
        fileName: String,
        fileSize: Long? = null,
        durationMs: Int? = null,
        success: Boolean,
        mediaItemId: Int? = null,
        errorCode: String? = null,
        retryCount: Int? = null
    ) {
        log(
            logType = "download",
            severity = if (success) "info" else "error",
            message = message,
            fileName = fileName,
            fileSize = fileSize,
            durationMs = durationMs,
            success = success,
            mediaItemId = mediaItemId,
            errorCode = errorCode,
            retryCount = retryCount
        )
    }

    fun playback(message: String, mediaItemId: Int? = null, fileName: String? = null) {
        log(logType = "playback", severity = "info", message = message, mediaItemId = mediaItemId, fileName = fileName)
    }

    private fun getTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(TimeSync.getServerTime()))
    }
}
