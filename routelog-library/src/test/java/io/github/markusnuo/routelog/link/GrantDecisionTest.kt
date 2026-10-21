package io.github.markusnuo.routelog.link

import io.github.markusnuo.routelog.data.GrantEntity
import io.github.markusnuo.routelog.data.RecordingEntity
import io.github.markusnuo.routelog.data.RecordingState
import io.github.markusnuo.routelog.recording.ResumeWindow
import org.junit.Assert.assertEquals
import org.junit.Test

/** "One link = one recording": what a link whose grant is known does. */
class GrantDecisionTest {
    private fun grant(recordingId: Long?) = GrantEntity("hash", recordingId, expiresAtMillis = 10_000)

    private companion object {
        const val NOW = 2_000L
    }

    private fun recording(id: Long, state: RecordingState) = RecordingEntity(id = id, startedAtMillis = 1_000, state = state)

    @Test
    fun aGrantNobodyUsedYetIsANewLink() {
        assertEquals(LinkGrantAction.NewLink, GrantDecision.decide(null, null, runningRecordingId = null, nowMillis = NOW))
        assertEquals(LinkGrantAction.NewLink, GrantDecision.decide(null, null, runningRecordingId = 7, nowMillis = NOW))
    }

    @Test
    fun theRunningRecordingKeepsRunning() {
        val action = GrantDecision.decide(grant(7), recording(7, RecordingState.RECORDING), runningRecordingId = 7, nowMillis = NOW)
        assertEquals(LinkGrantAction.KeepRecording, action)
    }

    @Test
    fun anInterruptedRecordingIsResumed() {
        val action = GrantDecision.decide(grant(7), recording(7, RecordingState.INTERRUPTED), runningRecordingId = null, nowMillis = NOW)
        assertEquals(LinkGrantAction.Resume(7), action)
    }

    @Test
    fun aRecordingLeftOverByADeadProcessIsResumedToo() {
        val action = GrantDecision.decide(grant(7), recording(7, RecordingState.RECORDING), runningRecordingId = null, nowMillis = NOW)
        assertEquals(LinkGrantAction.Resume(7), action)
    }

    @Test
    fun aStoppedRecordingIsUsedUp() {
        val action = GrantDecision.decide(grant(7), recording(7, RecordingState.FINISHED), runningRecordingId = null, nowMillis = NOW)
        assertEquals(LinkGrantAction.AlreadyUsed, action)
    }

    @Test
    fun aDeletedRecordingIsUsedUpToo() {
        assertEquals(LinkGrantAction.AlreadyUsed, GrantDecision.decide(grant(null), null, runningRecordingId = null, nowMillis = NOW))
        // The row was deleted between the two reads.
        assertEquals(LinkGrantAction.AlreadyUsed, GrantDecision.decide(grant(7), null, runningRecordingId = null, nowMillis = NOW))
    }

    @Test
    fun anInterruptionOlderThan12HoursCanNotBeResumed() {
        val old = RecordingEntity(id = 7, startedAtMillis = 1_000, endedAtMillis = 5_000, state = RecordingState.INTERRUPTED)
        val justInTime = GrantDecision.decide(grant(7), old, runningRecordingId = null, nowMillis = 5_000 + ResumeWindow.MILLIS)
        assertEquals(LinkGrantAction.Resume(7), justInTime)
        val tooLate = GrantDecision.decide(grant(7), old, runningRecordingId = null, nowMillis = 5_001 + ResumeWindow.MILLIS)
        assertEquals(LinkGrantAction.TooLateToResume, tooLate)
    }

    @Test
    fun aRecentSignOfTheRecorderKeepsTheLinkResumable() {
        // Ended long ago, but resumed since: the 12 hours count from the later sign of life.
        val resumedSince = RecordingEntity(
            id = 7,
            startedAtMillis = 1_000,
            endedAtMillis = 5_000,
            aliveAtMillis = 5_000 + ResumeWindow.MILLIS,
            state = RecordingState.INTERRUPTED,
        )
        val nowMillis = 5_001 + ResumeWindow.MILLIS
        assertEquals(LinkGrantAction.Resume(7), GrantDecision.decide(grant(7), resumedSince, runningRecordingId = null, nowMillis = nowMillis))
        assertEquals(
            LinkGrantAction.TooLateToResume,
            GrantDecision.decide(grant(7), resumedSince.copy(aliveAtMillis = null), runningRecordingId = null, nowMillis = nowMillis),
        )
    }

    @Test
    fun anotherRecordingRunningDoesNotChangeTheDecision() {
        val interrupted = GrantDecision.decide(grant(7), recording(7, RecordingState.INTERRUPTED), runningRecordingId = 9, nowMillis = NOW)
        assertEquals(LinkGrantAction.Resume(7), interrupted)
        val finished = GrantDecision.decide(grant(7), recording(7, RecordingState.FINISHED), runningRecordingId = 9, nowMillis = NOW)
        assertEquals(LinkGrantAction.AlreadyUsed, finished)
    }
}
