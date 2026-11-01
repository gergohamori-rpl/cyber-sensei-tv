package com.cybersensei.tvplayer.service

import org.junit.Assert.*
import org.junit.Test

class RecoveryTest {
    @Test fun entirelyMissingPlaylistStopsAfterOnePass() {
        var checks = 0
        assertNull(findPlayableIndex(10000, 6000) { checks++; false })
        assertEquals(10000, checks)
    }

    @Test fun missingFilesSkipForwardAndWrap() {
        assertEquals(1, findPlayableIndex(4, 2) { it == 1 })
        assertEquals(2, findPlayableIndex(4, 2) { true })
        assertNull(findPlayableIndex(0, 0) { fail("empty playlist must not inspect files"); true })
    }

    @Test fun frozenVideoIsDetectedEvenWhenPlayerClaimsPlaying() {
        val health = PlaybackHealth()
        health.reset(0)
        assertFalse(health.stalled(1000, 2000, false, 10000))
        assertTrue(health.stalled(47000, 2000, false, 10000))
    }

    @Test fun longVideoWithProgressIsNotRestarted() {
        val health = PlaybackHealth()
        health.reset(0)
        for (now in 0L..300000L step 15000L) {
            assertFalse(health.stalled(now, now, false, 10000))
        }
    }

    @Test fun imageHasItsOwnDisplayDeadline() {
        val health = PlaybackHealth()
        health.reset(0)
        assertFalse(health.stalled(60000, 0, true, 120000))
        assertTrue(health.stalled(150001, 0, true, 120000))
    }

    @Test fun recoveryResetsDeadline() {
        val health = PlaybackHealth()
        health.reset(0)
        assertTrue(health.stalled(50000, 0, true, 10000))
        health.reset(50000)
        assertFalse(health.stalled(60000, 0, true, 10000))
    }

    @Test fun failedUploadLeavesBatchPending() {
        val queue = PendingLogs<Any>()
        val log = Any()
        queue.add(log)
        assertEquals(listOf(log), queue.snapshot())
        assertEquals(listOf(log), queue.snapshot()) // another attempt after a network failure
    }

    @Test fun successfulUploadDoesNotDeleteNewLogs() {
        val queue = PendingLogs<Any>()
        queue.add(Any())
        val batch = queue.snapshot()
        val addedWhileUploading = Any()
        queue.add(addedWhileUploading)
        queue.acknowledge(batch)
        assertEquals(listOf(addedWhileUploading), queue.snapshot())
    }

    @Test fun overflowKeepsRecentLogsAndAcknowledgementIsSafe() {
        val queue = PendingLogs<Any>(2)
        val old = Any()
        val current = Any()
        queue.add(old)
        val batch = queue.snapshot()
        queue.add(current)
        val recent = Any()
        queue.add(recent)
        queue.acknowledge(batch)
        assertEquals(listOf(current, recent), queue.snapshot())
    }
}
