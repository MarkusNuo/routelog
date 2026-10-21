package io.github.markusnuo.routelog.recording

import io.github.markusnuo.routelog.data.RecordingEntity
import io.github.markusnuo.routelog.data.RecordingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class ResumeWindowTest {
    private val hours12 = TimeUnit.HOURS.toMillis(12)

    private fun interrupted(started: Long, lastPoint: Long? = null, ended: Long? = null, alive: Long? = null) = RecordingEntity(
        startedAtMillis = started,
        lastPointAtMillis = lastPoint,
        endedAtMillis = ended,
        aliveAtMillis = alive,
        state = RecordingState.INTERRUPTED,
    )

    @Test
    fun twelveHoursFromTheInterruption() {
        val recording = interrupted(started = 1_000, lastPoint = 4_000, ended = 5_000)
        assertTrue(ResumeWindow.isOpen(recording, 5_000))
        assertTrue(ResumeWindow.isOpen(recording, 5_000 + hours12))
        assertFalse(ResumeWindow.isOpen(recording, 5_001 + hours12))
    }

    @Test
    fun withoutAnEndTheLastPointCountsThenTheStart() {
        // A recording a dead process left as RECORDING has no end yet.
        assertEquals(4_000L, ResumeWindow.lastSignOfLife(interrupted(started = 1_000, lastPoint = 4_000)))
        assertEquals(1_000L, ResumeWindow.lastSignOfLife(interrupted(started = 1_000)))
    }

    @Test
    fun aLaterSignOfTheRecorderExtendsTheWindow() {
        // Interrupted at 08:00 (the end), resumed at 19:00 and interrupted again without a fix: it
        // counts from 19:00, although the shown end is still the last point.
        val recording = interrupted(started = 1_000, lastPoint = 8_000, ended = 8_000, alive = 19_000)
        assertEquals(19_000L, ResumeWindow.lastSignOfLife(recording))
        assertTrue(ResumeWindow.isOpen(recording, 19_000 + hours12))
        assertFalse(ResumeWindow.isOpen(recording, 19_001 + hours12))
        assertEquals(hours12, ResumeWindow.remainingMillis(recording, 19_000))
        // Without an end yet (a dead process) the last point is compared with the alive time.
        assertEquals(19_000L, ResumeWindow.lastSignOfLife(interrupted(started = 1_000, lastPoint = 8_000, alive = 19_000)))
        assertEquals(19_030L, ResumeWindow.lastSignOfLife(interrupted(started = 1_000, lastPoint = 19_030, alive = 19_000)))
    }

    @Test
    fun anEarlierSignOfTheRecorderNeverShortensTheWindow() {
        // A fix after the last minute mark ends later than the alive time: the end wins.
        assertEquals(5_000L, ResumeWindow.lastSignOfLife(interrupted(started = 1_000, lastPoint = 4_000, ended = 5_000, alive = 3_000)))
        // Rows from before the column have no alive time.
        assertEquals(5_000L, ResumeWindow.lastSignOfLife(interrupted(started = 1_000, lastPoint = 4_000, ended = 5_000)))
        assertEquals(1_000L, ResumeWindow.lastSignOfLife(interrupted(started = 1_000, alive = 500)))
    }

    @Test
    fun remainingTimeNeverGoesBelowZero() {
        val recording = interrupted(started = 0, ended = 0)
        assertEquals(hours12, ResumeWindow.remainingMillis(recording, 0))
        assertEquals(1_000L, ResumeWindow.remainingMillis(recording, hours12 - 1_000))
        assertEquals(0L, ResumeWindow.remainingMillis(recording, hours12 + 5_000))
    }
}
