package com.cybersensei.tvplayer.service

import android.graphics.BitmapFactory
import android.view.View
import android.widget.ImageView
import kotlinx.coroutines.*
import java.io.File

class ImageDisplayManager(
    private val imageView: ImageView,
    private var durationSec: Int = 10
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var displayJob: Job? = null

    fun setDuration(seconds: Int) { durationSec = seconds.coerceIn(1, 86400) }
    fun getDuration(): Int = durationSec

    fun showImage(file: File, onComplete: () -> Unit) {
        hide()
        displayJob = scope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, options)
                    // Keep decoded images bounded even when the source is a large camera photo.
                    var sample = 1
                    while (options.outWidth / sample > 1920 || options.outHeight / sample > 1920) {
                        sample *= 2
                    }
                    options.inJustDecodeBounds = false
                    options.inSampleSize = sample
                    BitmapFactory.decodeFile(file.absolutePath, options)
                }
                if (bitmap == null) {
                    LogCollector.error("playback", "Failed to decode image: ${file.name}", "DECODE_ERROR")
                } else {
                    imageView.setImageBitmap(bitmap)
                    imageView.visibility = View.VISIBLE
                    delay(durationSec * 1000L)
                    imageView.visibility = View.GONE
                    imageView.setImageDrawable(null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogCollector.error("playback", "Image display error: ${e.message}", "IMAGE_ERROR")
            } catch (e: OutOfMemoryError) {
                LogCollector.error("playback", "Image too large: ${file.name}", "IMAGE_MEMORY")
            }
            onComplete()
        }
    }

    fun hide() {
        displayJob?.cancel()
        displayJob = null
        imageView.clearAnimation()
        imageView.visibility = View.GONE
        imageView.setImageDrawable(null)
    }

    fun release() {
        hide()
        scope.cancel()
    }
}
