package io.github.markusnuo.routelog.link

import io.github.markusnuo.routelog.data.GrantEntity
import io.github.markusnuo.routelog.data.RecordingEntity
import io.github.markusnuo.routelog.data.RecordingState
import io.github.markusnuo.routelog.recording.ResumeWindow

/** What to do with a valid link, given what routelog remembers of its grant. */
sealed interface LinkGrantAction {
    /** The grant is new: the normal flow (for a link: confirmation, or start if the server is trusted). */
    data object NewLink : LinkGrantAction

    /** The recording of this grant is running right now: nothing to do. */
    data object KeepRecording : LinkGrantAction

    /** The recording of this grant was interrupted: continue exactly that one. */
    data class Resume(val recordingId: Long) : LinkGrantAction

    /** The recording of this grant is stopped, uploaded or deleted: nothing new may start. */
    data object AlreadyUsed : LinkGrantAction

    /** The recording of this grant was interrupted more than 12 hours ago; it can't continue. */
    data object TooLateToResume : LinkGrantAction
}

/** "One link = one recording" (docs/EXTERNAL_RECORDING.md); the same for a token a host app passes. */
object GrantDecision {
    /**
     * [recording] is the one [grant] points to (null if there is none), [runningRecordingId] the
     * one this process is recording right now, if any.
     */
    fun decide(grant: GrantEntity?, recording: RecordingEntity?, runningRecordingId: Long?, nowMillis: Long): LinkGrantAction {
        if (grant == null) return LinkGrantAction.NewLink
        if (recording == null) return LinkGrantAction.AlreadyUsed
        if (recording.id == runningRecordingId) return LinkGrantAction.KeepRecording
        return when (recording.state) {
            // RECORDING that this process doesn't run is left over from a dead process (it is
            // marked interrupted when the resume starts).
            RecordingState.INTERRUPTED, RecordingState.RECORDING ->
                if (ResumeWindow.isOpen(recording, nowMillis)) LinkGrantAction.Resume(recording.id) else LinkGrantAction.TooLateToResume
            RecordingState.FINISHED -> LinkGrantAction.AlreadyUsed
        }
    }
}
