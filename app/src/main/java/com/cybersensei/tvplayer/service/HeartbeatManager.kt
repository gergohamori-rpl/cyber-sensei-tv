package com.cybersensei.tvplayer.service

import android.content.Context
import android.os.Build
import android.os.StatFs
import android.util.Log
import com.cybersensei.tvplayer.BuildConfig
import com.cybersensei.tvplayer.data.api.ApiClient
import com.cybersensei.tvplayer.data.model.HeartbeatRequest
import com.cybersensei.tvplayer.util.TimeSync
import kotlinx.coroutines.*
import java.io.File
import java.util.*

class HeartbeatManager(
    private val context: Context,
    private val baseUrl: String,
    private val apiKey: String
) {
    private val tag = "HeartbeatManager"
    private val api = ApiClient.getApi(baseUrl, apiKey)
    private var heartbeatJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var timeSyncCounter = 0

    var getCurrentMediaId: (() -> Int?)? = null
    var getCurrentMediaTitle: (() -> String?)? = null
    var isOnline: Boolean = true
        private set
    var onConnectionStatusChanged: ((Boolean) -> Unit)? = null
    var onCommandReceived: ((String) -> Unit)? = null

    fun start(intervalMs: Long = 60000L) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                sendHeartbeat()
                timeSyncCounter++
                if (timeSyncCounter >= 60) {
                    TimeSync.sync(baseUrl, apiKey)
                    timeSyncCounter = 0
                }
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        heartbeatJob?.cancel()
        scope.cancel()
    }

    private suspend fun sendHeartbeat() {
        try {
            val logs = LogCollector.flush()
            val mediaDir = File(context.filesDir, "media")
            val storageUsed = calculateDirSize(mediaDir)

            val stat = StatFs(context.filesDir.absolutePath)
            val storageFree = stat.availableBlocksLong * stat.blockSizeLong

            val request = HeartbeatRequest(
                currentMediaId = getCurrentMediaId?.invoke(),
                currentMediaTitle = getCurrentMediaTitle?.invoke(),
                appVersion = BuildConfig.VERSION_NAME,
                storageUsedBytes = storageUsed,
                storageFreeBytes = storageFree,
                timezone = TimeZone.getDefault().id,
                ipAddress = null,
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                androidVersion = Build.VERSION.RELEASE,
                logs = logs
            )

            val response = api.sendHeartbeat(request)
            if (response.isSuccessful) {
                if (!isOnline) {
                    isOnline = true
                    onConnectionStatusChanged?.invoke(true)
                    LogCollector.info("heartbeat", "Connection restored")
                }
                val body = response.body()
                body?.command?.let { cmd ->
                    LogCollector.info("heartbeat", "Received command: $cmd")
                    onCommandReceived?.invoke(cmd)
                }
            } else {
                LogCollector.restore(logs)
                setOffline()
            }
        } catch (e: Exception) {
            Log.e(tag, "Heartbeat failed", e)
            setOffline()
        }
    }

    private fun setOffline() {
        if (isOnline) {
            isOnline = false
            onConnectionStatusChanged?.invoke(false)
            LogCollector.warn("heartbeat", "Connection lost")
        }
    }

    private fun calculateDirSize(dir: File): Long {
        if (!dir.exists()) return 0
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
