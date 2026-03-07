package com.cybersensei.tvplayer

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.cybersensei.tvplayer.data.db.AppDatabase

class CyberSenseiApp : Application() {

    lateinit var database: AppDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        database = AppDatabase.getInstance(this)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Cyber Sensei TV Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Media playback notification"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "cyber_sensei_playback"
        lateinit var instance: CyberSenseiApp
            private set
    }
}
