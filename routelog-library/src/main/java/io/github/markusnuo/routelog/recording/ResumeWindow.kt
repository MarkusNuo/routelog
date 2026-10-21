package io.github.markusnuo.routelog.recording

import io.github.markusnuo.routelog.data.RecordingEntity
import java.util.concurrent.TimeUnit

/**
 * An interrupted recording can be resumed for 12 hours after the interruption. Later it can still
 * be exported, deleted, or finished and uploaded.
 */
object ResumeWindow {
    val MILLIS: Long = TimeUnit.HOURS.toMillis(12)

    /**
     * Where the 12 hours start: the latest sign of life of [recording], the later of its end (else
     * its last point, else its start) and its [RecordingEntity.aliveAtMillis]. A recording resumed
     * after a long gap and interrupted again without a fix counts from the resume, not from the old
     * last point. The alive time is best effort (about every minute while the processor
     * runs, at least the time of the last resume), so for a recording a dead process left behind
     * this is the interruption give or take that. The duration the list shows still ends at the
     * last point.
     */
    fun lastSignOfLife(recording: RecordingEntity): Long =
        maxOf(
            recording.endedAtMillis ?: recording.lastPointAtMillis ?: recording.startedAtMillis,
            recording.aliveAtMillis ?: recording.startedAtMillis,
        )

    fun isOpen(recording: RecordingEntity, nowMillis: Long): Boolean = nowMillis - lastSignOfLife(recording) <= MILLIS

    /** How much longer [recording] can be resumed; 0 if not anymore. */
    fun remainingMillis(recording: RecordingEntity, nowMillis: Long): Long =
        (lastSignOfLife(recording) + MILLIS - nowMillis).coerceAtLeast(0)
}
