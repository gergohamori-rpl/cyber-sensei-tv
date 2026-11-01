package com.cybersensei.tvplayer.service

/** Monotonic-clock progress tracking; playing/buffering flags alone do not prove progress. */
class PlaybackHealth {
    private var lastPosition = -1L
    private var lastProgress = 0L

    fun reset(now: Long) {
        lastPosition = -1L
        lastProgress = now
    }

    fun stalled(now: Long, position: Long, image: Boolean, imageDurationMs: Long): Boolean {
        if (!image && position != lastPosition) {
            lastPosition = position
            lastProgress = now
        }
        val timeout = if (image) imageDurationMs + 30_000L else 45_000L
        return now - lastProgress > timeout
    }
}

/** A single bounded scan, including wraparound; null means wait for downloads. */
fun findPlayableIndex(size: Int, start: Int, playable: (Int) -> Boolean): Int? {
    if (size <= 0) return null
    for (offset in 0 until size) {
        val index = (start.coerceAtLeast(0) % size + offset) % size
        if (playable(index)) return index
    }
    return null
}
