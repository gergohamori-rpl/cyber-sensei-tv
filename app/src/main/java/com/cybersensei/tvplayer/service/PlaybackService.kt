package com.cybersensei.tvplayer.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.cybersensei.tvplayer.CyberSenseiApp
import com.cybersensei.tvplayer.R
import com.cybersensei.tvplayer.data.db.MediaFileEntity
import com.cybersensei.tvplayer.ui.MainActivity
import java.io.File

class PlaybackService : Service() {

    private val tag = "PlaybackService"
    private var player: ExoPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val binder = PlaybackBinder()
    private var currentPlaylist: List<MediaFileEntity> = emptyList()
    private var currentIndex = 0
    private var isShowingImage = false

    var onMediaChanged: ((MediaFileEntity?, Boolean) -> Unit)? = null
    var imageDisplayManager: ImageDisplayManager? = null

    inner class PlaybackBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
        startForeground(1, createNotification("Initializing..."))
        initPlayer()
        LogCollector.info("service", "PlaybackService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        LogCollector.info("service", "PlaybackService destroyed")
        releaseWakeLock()
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CyberSenseiTV::PlaybackWakeLock"
        ).apply {
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun initPlayer() {
        player = ExoPlayer.Builder(this).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        playNext()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    val current = getCurrentMedia()
                    LogCollector.error(
                        "playback",
                        "Playback error: ${error.message}",
                        errorCode = error.errorCodeName,
                        details = mapOf(
                            "mediaItemId" to current?.mediaItemId,
                            "fileName" to current?.fileName
                        )
                    )
                    playNext()
                }
            })
        }
    }

    var muteAudio: Boolean = false

    fun loadPlaylist(files: List<MediaFileEntity>, shuffle: Boolean, loop: Boolean) {
        currentPlaylist = if (shuffle) files.shuffled() else files
        currentIndex = 0
        player?.repeatMode = if (loop) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_OFF
        player?.volume = if (muteAudio) 0f else 1f
        LogCollector.info("playback", "Playlist loaded", details = mapOf(
            "itemCount" to files.size,
            "shuffle" to shuffle,
            "loop" to loop,
            "muteAudio" to muteAudio
        ))
        if (currentPlaylist.isNotEmpty()) {
            playCurrentItem()
        }
    }

    fun reloadPlaylist(files: List<MediaFileEntity>, shuffle: Boolean) {
        val currentMedia = getCurrentMedia()
        currentPlaylist = if (shuffle) files.shuffled() else files
        player?.volume = if (muteAudio) 0f else 1f

        if (currentMedia != null) {
            val newIndex = currentPlaylist.indexOfFirst { it.mediaItemId == currentMedia.mediaItemId }
            if (newIndex >= 0) {
                currentIndex = newIndex
                return
            }
        }
        currentIndex = 0
        if (currentPlaylist.isNotEmpty()) {
            playCurrentItem()
        }
    }

    private fun playCurrentItem() {
        if (currentPlaylist.isEmpty()) return
        if (currentIndex >= currentPlaylist.size) {
            currentIndex = 0
        }

        val media = currentPlaylist[currentIndex]
        val file = File(media.filePath)

        if (!file.exists()) {
            LogCollector.warn("playback", "File missing: ${media.fileName}", details = mapOf("path" to media.filePath))
            playNext()
            return
        }

        val isImage = media.mediaType.startsWith("image")

        if (isImage) {
            isShowingImage = true
            player?.pause()
            onMediaChanged?.invoke(media, true)
            imageDisplayManager?.showImage(file) {
                playNext()
            }
            LogCollector.playback("Showing image: ${media.fileName}", media.mediaItemId, media.fileName)
        } else {
            isShowingImage = false
            imageDisplayManager?.hide()
            onMediaChanged?.invoke(media, false)
            val mediaItem = MediaItem.fromUri(android.net.Uri.fromFile(file))
            player?.setMediaItem(mediaItem)
            player?.volume = if (muteAudio) 0f else 1f
            player?.prepare()
            player?.play()
            LogCollector.playback("Playing video: ${media.fileName}", media.mediaItemId, media.fileName)
        }

        updateNotification("Playing: ${media.fileName}")
    }

    private fun playNext() {
        if (currentPlaylist.isEmpty()) return
        currentIndex++
        if (currentIndex >= currentPlaylist.size) {
            currentIndex = 0
        }
        playCurrentItem()
    }

    fun getCurrentMedia(): MediaFileEntity? {
        if (currentPlaylist.isEmpty() || currentIndex >= currentPlaylist.size) return null
        return currentPlaylist[currentIndex]
    }

    fun getPlayer(): ExoPlayer? = player

    fun isPlaying(): Boolean = player?.isPlaying == true || isShowingImage

    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CyberSenseiApp.CHANNEL_ID)
            .setContentTitle("Cyber Sensei TV")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(1, notification)
        } catch (_: Exception) {}
    }
}
