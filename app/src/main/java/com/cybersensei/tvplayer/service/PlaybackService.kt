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
    private var imageSessionToken = 0
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val health = PlaybackHealth()
    private val nextItem = Runnable {
        if (currentPlaylist.isNotEmpty()) {
            currentIndex = (currentIndex + 1) % currentPlaylist.size
            playCurrentItem()
        }
    }

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
        handler.removeCallbacksAndMessages(null)
        imageDisplayManager?.hide()
        onMediaChanged = null
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
        handler.removeCallbacks(nextItem)
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
        playCurrentItem()
    }

    fun reloadPlaylist(files: List<MediaFileEntity>, shuffle: Boolean) {
        val currentMedia = getCurrentMedia()
        currentPlaylist = if (shuffle) files.shuffled() else files
        player?.volume = if (muteAudio) 0f else 1f

        if (currentMedia != null) {
            val newIndex = currentPlaylist.indexOfFirst { it.mediaItemId == currentMedia.mediaItemId }
            if (newIndex >= 0 && currentPlaylist[newIndex].filePath == currentMedia.filePath &&
                File(currentMedia.filePath).isFile && isPlaying()) {
                currentIndex = newIndex
                return
            }
        }
        currentIndex = 0
        playCurrentItem()
    }

    private fun playCurrentItem() {
        handler.removeCallbacks(nextItem)
        imageSessionToken++
        isShowingImage = false
        imageDisplayManager?.hide()
        player?.stop()
        if (currentPlaylist.isEmpty()) {
            updateNotification("Waiting for media...")
            return
        }
        if (currentIndex >= currentPlaylist.size) {
            currentIndex = 0
        }

        // At most one pass: an entirely missing playlist must never recurse.
        val playableIndex = findPlayableIndex(currentPlaylist.size, currentIndex) { index ->
                File(currentPlaylist[index].filePath).let { it.isFile && it.length() > 0 }
            }
        if (playableIndex == null) {
            LogCollector.warn("playback", "No playable local files; waiting for cache repair")
            updateNotification("Waiting for media download...")
            return
        }
        currentIndex = playableIndex
        val media = currentPlaylist[currentIndex]
        val file = File(media.filePath)
        health.reset(android.os.SystemClock.elapsedRealtime())

        val imageExtensions = listOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
        val isImage = media.mediaType.startsWith("image") || media.mediaType == "carousel" || file.extension.lowercase() in imageExtensions

        if (isImage) {
            isShowingImage = true
            imageSessionToken++
            val capturedToken = imageSessionToken
            player?.pause()
            onMediaChanged?.invoke(media, true)
            imageDisplayManager?.showImage(file) {
                if (capturedToken == imageSessionToken) {
                    playNext()
                }
            }
            LogCollector.playback("Showing image: ${media.fileName}", media.mediaItemId, media.fileName)
        } else {
            isShowingImage = false
            imageSessionToken++
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
        handler.removeCallbacks(nextItem)
        // Posting also bounds repeated decoder errors for a corrupt single-item playlist.
        handler.postDelayed(nextItem, 250L)
    }

    fun recoverIfStalled(): Boolean {
        if (currentPlaylist.isEmpty()) return false
        val stuck = health.stalled(
            android.os.SystemClock.elapsedRealtime(), player?.currentPosition ?: 0L,
            isShowingImage, (imageDisplayManager?.getDuration() ?: 10) * 1000L
        )
        if (stuck) {
            LogCollector.warn("playback", "Watchdog: playback made no progress; rebuilding player",
                details = mapOf("mediaItemId" to getCurrentMedia()?.mediaItemId))
            player?.release()
            initPlayer()
            onMediaChanged?.invoke(getCurrentMedia(), isShowingImage)
            playNext()
            health.reset(android.os.SystemClock.elapsedRealtime())
        }
        return stuck
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
