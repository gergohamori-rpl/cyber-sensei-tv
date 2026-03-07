package com.cybersensei.tvplayer.util

import android.content.Context
import android.content.SharedPreferences
import com.cybersensei.tvplayer.data.api.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object TimeSync {

    private var offsetMs: Long = 0
    private var lastSyncTime: Long = 0
    private const val SYNC_INTERVAL_MS = 3600000L

    fun getServerTime(): Long {
        return System.currentTimeMillis() + offsetMs
    }

    fun needsSync(): Boolean {
        return System.currentTimeMillis() - lastSyncTime > SYNC_INTERVAL_MS
    }

    suspend fun sync(baseUrl: String, apiKey: String) {
        withContext(Dispatchers.IO) {
            try {
                val api = ApiClient.getApi(baseUrl, apiKey)
                val beforeMs = System.currentTimeMillis()
                val response = api.getTime()
                val afterMs = System.currentTimeMillis()

                if (response.isSuccessful) {
                    val body = response.body()
                    if (body != null) {
                        val roundTripMs = afterMs - beforeMs
                        val serverTimeMs = body.timestamp
                        val localTimeAtResponse = beforeMs + (roundTripMs / 2)
                        offsetMs = serverTimeMs - localTimeAtResponse
                        lastSyncTime = System.currentTimeMillis()
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun saveOffset(prefs: SharedPreferences) {
        prefs.edit().putLong("time_offset_ms", offsetMs).apply()
    }

    fun loadOffset(prefs: SharedPreferences) {
        offsetMs = prefs.getLong("time_offset_ms", 0)
    }
}
