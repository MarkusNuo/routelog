package io.github.markusnuo.routelog.recording

/** What the GPS of a running recording delivers. */
sealed interface GpsStatus {
    /** No fix yet, or none in the last 10 seconds. */
    data object Searching : GpsStatus

    /** Location (the GPS provider) is turned off in the system settings. */
    data object Off : GpsStatus

    /** A fix came in the last 10 seconds; [accuracyMeters] is that of the latest one, null if unknown. */
    data class Fix(val accuracyMeters: Float?) : GpsStatus
}

/**
 * One-off happenings the user should hear about; unlike the state, they are not kept: only who
 * collects at the time gets them. Each says why a start or resume that the service was asked for
 * did not happen; except after [LinkBusy], [RecorderState] is back to [RecorderState.Idle] by then.
 */
sealed interface RecorderEvent {
    /**
     * The recording could not start: the local database or the token store failed, Android refused
     * the foreground service, or the GPS could not be started (permission revoked, no GPS provider).
     */
    data object StartFailed : RecorderEvent

    /** The recording to resume is gone or not interrupted (anymore). */
    data object NotResumable : RecorderEvent

    /** The recording was interrupted more than 12 hours ago. */
    data object ResumeTooLate : RecorderEvent

    /** A start with an upload target came while another recording was running; that one goes on. */
    data object LinkBusy : RecorderEvent

    /** The grant ran out before the recording could start. */
    data object LinkExpired : RecorderEvent

    /** The grant already started a recording. */
    data object LinkUsed : RecorderEvent
}

/** What the recorder does right now. */
sealed interface RecorderState {
    /** Nothing records. */
    data object Idle : RecorderState

    /** A start or resume was claimed and the service is on its way; it ends in [Recording] or [Idle]. */
    data object Starting : RecorderState

    /** Updated with every fix, when location is turned on or off, and every 5 seconds for the GPS status. */
    data class Recording(
        val recordingId: Long,
        /** Start of the recording; for a resumed one, the original start. */
        val startedAtMillis: Long,
        /** Driven distance over all segments, with GPS jitter filtered out. */
        val distanceMeters: Double,
        /** Stored points over all segments. */
        val pointCount: Int,
        val gps: GpsStatus,
        /** The last fix could not be written to the database. */
        val storageError: Boolean,
    ) : RecorderState
}
