package com.cybersensei.tvplayer.service

/** Acknowledging an old batch must never delete messages added while uploading it. */
class PendingLogs<T>(private val capacity: Int = 500) {
    private val entries = mutableListOf<T>()

    @Synchronized fun add(entry: T) {
        entries.add(entry)
        while (entries.size > capacity) entries.removeAt(0)
    }

    @Synchronized fun snapshot(): List<T> = entries.toList()

    @Synchronized fun acknowledge(batch: List<T>) {
        entries.removeAll { entry -> batch.any { it === entry } }
    }
}
