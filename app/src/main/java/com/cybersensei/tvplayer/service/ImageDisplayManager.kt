package com.cybersensei.tvplayer.service

import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AlphaAnimation
import android.widget.ImageView
import java.io.File

class ImageDisplayManager(
    private val imageView: ImageView,
    private var durationSec: Int = 10
) {
    private val handler = Handler(Looper.getMainLooper())
    private var nextCallback: Runnable? = null

    fun setDuration(seconds: Int) {
        durationSec = seconds
    }

    fun showImage(file: File, onComplete: () -> Unit) {
        nextCallback?.let { handler.removeCallbacks(it) }

        handler.post {
            try {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                    imageView.visibility = View.VISIBLE

                    val fadeIn = AlphaAnimation(0f, 1f).apply {
                        duration = 500
                        fillAfter = true
                    }
                    imageView.startAnimation(fadeIn)

                    nextCallback = Runnable {
                        val fadeOut = AlphaAnimation(1f, 0f).apply {
                            duration = 500
                            fillAfter = true
                        }
                        imageView.startAnimation(fadeOut)
                        handler.postDelayed({
                            imageView.visibility = View.GONE
                            onComplete()
                        }, 500)
                    }
                    handler.postDelayed(nextCallback!!, durationSec * 1000L)
                } else {
                    LogCollector.error("playback", "Failed to decode image: ${file.name}", errorCode = "DECODE_ERROR")
                    onComplete()
                }
            } catch (e: Exception) {
                LogCollector.error("playback", "Image display error: ${e.message}", errorCode = "IMAGE_ERROR")
                onComplete()
            }
        }
    }

    fun hide() {
        nextCallback?.let { handler.removeCallbacks(it) }
        handler.post {
            imageView.visibility = View.GONE
        }
    }
}
