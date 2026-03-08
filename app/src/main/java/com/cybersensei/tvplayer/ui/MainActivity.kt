package com.cybersensei.tvplayer.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.ui.PlayerView
import com.cybersensei.tvplayer.R
import com.cybersensei.tvplayer.service.*
import com.cybersensei.tvplayer.util.TimeSync
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private var playbackService: PlaybackService? = null
    private var downloadManager: MediaDownloadManager? = null
    private var heartbeatManager: HeartbeatManager? = null
    private var otaManager: OtaUpdateManager? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var playerView: PlayerView
    private lateinit var imageView: ImageView
    private lateinit var statusIcon: View
    private lateinit var mediaTitle: TextView

    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as PlaybackService.PlaybackBinder).getService()
            playbackService = service

            service.imageDisplayManager = ImageDisplayManager(imageView, downloadManager?.imageDurationSec ?: 10)

            service.onMediaChanged = { media, isImage ->
                handler.post {
                    if (isImage) {
                        playerView.visibility = View.GONE
                        imageView.visibility = View.VISIBLE
                    } else {
                        imageView.visibility = View.GONE
                        playerView.visibility = View.VISIBLE
                        playerView.player = service.getPlayer()
                    }

                    media?.let {
                        if (downloadManager?.showMediaTitle != false) {
                            mediaTitle.text = it.fileName
                            mediaTitle.visibility = View.VISIBLE
                            mediaTitle.alpha = 1f
                            handler.removeCallbacksAndMessages("hide_title")
                            handler.postDelayed({
                                mediaTitle.animate().alpha(0f).setDuration(500).withEndAction {
                                    mediaTitle.visibility = View.GONE
                                }.start()
                            }, 5000)
                        } else {
                            mediaTitle.visibility = View.GONE
                        }
                    }
                }
            }

            bound = true
            startPlayback()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        hideSystemUI()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        playerView = findViewById(R.id.playerView)
        imageView = findViewById(R.id.imageView)
        statusIcon = findViewById(R.id.statusIcon)
        mediaTitle = findViewById(R.id.mediaTitle)

        playerView.useController = false

        val prefs = getSharedPreferences("cyber_sensei_tv", MODE_PRIVATE)
        val baseUrl = prefs.getString("server_url", "") ?: ""
        val apiKey = prefs.getString("api_key", "") ?: ""

        if (baseUrl.isBlank() || apiKey.isBlank()) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        TimeSync.loadOffset(prefs)
        LogCollector.info("app", "MainActivity started", details = mapOf(
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "android" to Build.VERSION.RELEASE
        ))

        downloadManager = MediaDownloadManager(this, baseUrl, apiKey).apply {
            onPlaylistUpdated = {
                scope.launch {
                    reloadCurrentPlaylist()
                }
            }
        }

        heartbeatManager = HeartbeatManager(this, baseUrl, apiKey).apply {
            getCurrentMediaId = { playbackService?.getCurrentMedia()?.mediaItemId }
            getCurrentMediaTitle = { playbackService?.getCurrentMedia()?.fileName }
            onConnectionStatusChanged = { online ->
                handler.post {
                    statusIcon.setBackgroundResource(
                        if (online) R.drawable.status_online else R.drawable.status_offline
                    )
                }
            }
            onCommandReceived = { cmd ->
                when (cmd) {
                    "clear_cache" -> {
                        scope.launch {
                            LogCollector.info("command", "Executing clear_cache command")
                            val dao = com.cybersensei.tvplayer.CyberSenseiApp.instance.database.mediaFileDao()
                            val files = dao.getAll()
                            for (entity in files) {
                                try { java.io.File(entity.filePath).delete() } catch (_: Exception) {}
                                dao.delete(entity)
                            }
                            LogCollector.info("command", "Cache cleared, ${files.size} files deleted")
                            downloadManager?.syncPlaylist()
                        }
                    }
                    "update" -> {
                        scope.launch(Dispatchers.IO) {
                            LogCollector.info("command", "Executing update command")
                            otaManager?.checkAndUpdate()
                        }
                    }
                }
            }
        }

        otaManager = OtaUpdateManager(this, baseUrl, apiKey)

        val serviceIntent = Intent(this, PlaybackService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

        downloadManager?.startPeriodicSync()
        heartbeatManager?.start()

        scope.launch(Dispatchers.IO) {
            TimeSync.sync(baseUrl, apiKey)
            TimeSync.saveOffset(prefs)
            otaManager?.checkAndUpdate()
            while (isActive) {
                delay(30 * 60 * 1000L)
                otaManager?.checkAndUpdate()
            }
        }
    }

    private fun startPlayback() {
        scope.launch {
            val files = downloadManager?.getLocalMediaFiles() ?: emptyList()
            if (files.isNotEmpty()) {
                playbackService?.muteAudio = downloadManager?.muteAudio ?: false
                applyRotation(downloadManager?.videoRotation ?: 0)
                playbackService?.loadPlaylist(
                    files,
                    downloadManager?.shuffle ?: false,
                    downloadManager?.loop ?: true
                )
            } else {
                LogCollector.info("playback", "No media files available yet, waiting for sync")
            }
        }
    }

    private suspend fun reloadCurrentPlaylist() {
        val files = downloadManager?.getLocalMediaFiles() ?: emptyList()
        if (files.isNotEmpty()) {
            playbackService?.let { service ->
                service.muteAudio = downloadManager?.muteAudio ?: false
                applyRotation(downloadManager?.videoRotation ?: 0)
                if (service.getCurrentMedia() != null) {
                    service.reloadPlaylist(files, downloadManager?.shuffle ?: false)
                } else {
                    service.loadPlaylist(files, downloadManager?.shuffle ?: false, downloadManager?.loop ?: true)
                }
                service.imageDisplayManager?.setDuration(downloadManager?.imageDurationSec ?: 10)
            }
        }
    }

    private var currentRotation: Int = 0

    private fun applyRotation(degrees: Int) {
        currentRotation = degrees
        handler.post {
            val displayMetrics = resources.displayMetrics
            val screenW = displayMetrics.widthPixels
            val screenH = displayMetrics.heightPixels

            if (degrees == 90 || degrees == 270) {
                val playerParams = FrameLayout.LayoutParams(screenH, screenW)
                playerParams.gravity = Gravity.CENTER
                playerView.layoutParams = playerParams

                val imageParams = FrameLayout.LayoutParams(screenH, screenW)
                imageParams.gravity = Gravity.CENTER
                imageView.layoutParams = imageParams
            } else {
                val playerParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                playerView.layoutParams = playerParams

                val imageParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                imageView.layoutParams = imageParams
            }

            playerView.rotation = degrees.toFloat()
            imageView.rotation = degrees.toFloat()
            playerView.scaleX = 1f
            playerView.scaleY = 1f
            imageView.scaleX = 1f
            imageView.scaleY = 1f

            LogCollector.info("rotation", "Applied rotation: ${degrees}°", details = mapOf(
                "screenW" to screenW,
                "screenH" to screenH,
                "viewW" to if (degrees == 90 || degrees == 270) screenH else screenW,
                "viewH" to if (degrees == 90 || degrees == 270) screenW else screenH
            ))
        }
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    override fun onDestroy() {
        if (bound) {
            unbindService(connection)
            bound = false
        }
        downloadManager?.stop()
        heartbeatManager?.stop()
        scope.cancel()
        super.onDestroy()
    }
}
