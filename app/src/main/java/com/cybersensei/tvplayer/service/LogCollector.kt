package com.cybersensei.tvplayer.service

import com.cybersensei.tvplayer.data.model.LogEntry
import com.cybersensei.tvplayer.util.TimeSync
import java.text.SimpleDateFormat
import java.util.*

object LogCollector {

    private val buffer = mutableListOf<LogEntry>()
    private val lock = Any()
    private const val MAX_BUFFER_SIZE = 500

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
            if (buffer.size > MAX_BUFFER_SIZE) {
                buffer.removeAt(0)
            }
        }
    }

    fun flush(): List<LogEntry> {
        synchronized(lock) {
            val entries = buffer.toList()
            buffer.clear()
            return entries
        }
    }

    fun restore(entries: List<LogEntry>) {
        synchronized(lock) {
            buffer.addAll(0, entries)
            while (buffer.size > MAX_BUFFER_SIZE) {
                buffer.removeAt(buffer.size - 1)
            }
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
