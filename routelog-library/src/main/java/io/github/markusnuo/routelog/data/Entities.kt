package io.github.markusnuo.routelog.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class RecordingState {
    /**
     * GPS is (or should be) running for this recording. A row a dead process left in this state
     * counts as interrupted; [io.github.markusnuo.routelog.Routelog.recordings] marks it so.
     */
    RECORDING,

    /**
     * The recording ended without a stop (the process died, or the service was ended); it can be
     * resumed within 12 hours.
     */
    INTERRUPTED,

    /** Stopped, or finished after an interruption without being resumed. */
    FINISHED,
}

/** A recording as stored; its points are in `track_points`. Times are epoch milliseconds. */
@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "started_at_millis") val startedAtMillis: Long,
    /**
     * The end the list shows: the time of the stop; for an interrupted recording, and one finished
     * after an interruption, its last point (else its start); null while it records. A duration
     * from [startedAtMillis] to this includes the pauses between segments.
     */
    @ColumnInfo(name = "ended_at_millis") val endedAtMillis: Long? = null,
    val state: RecordingState,
    /** Driven distance over all segments, with GPS jitter filtered out; the stored points are not filtered. */
    @ColumnInfo(name = "distance_meters") val distanceMeters: Double = 0.0,
    /** Stored points over all segments; it stays when an upload's keep time deletes the points. */
    @ColumnInfo(name = "point_count") val pointCount: Int = 0,
    /** GPS time of the latest stored point; null before the first. */
    @ColumnInfo(name = "last_point_at_millis") val lastPointAtMillis: Long? = null,
    /** Current track segment; a resumed recording continues in a new segment. */
    val segment: Int = 0,
    /**
     * Of an unfinished recording: the latest time the recorder noted that it still ran. Written at
     * a resume (so at least that time is known) and then about every minute while the processor
     * runs, best effort: a sleeping device can skip ticks. Set to the moment of an interruption the
     * process notices itself. A dead process can't write when it ended, so the 12-hour resume
     * window counts from the later of this and the end; the shown duration does not use it.
     * Cleared when the recording is finished.
     */
    @ColumnInfo(name = "alive_at_millis") val aliveAtMillis: Long? = null,
)

@Entity(
    tableName = "track_points",
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recording_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("recording_id")],
)
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "recording_id") val recordingId: Long,
    val segment: Int,
    /** UTC time of the fix as reported by the GPS. */
    @ColumnInfo(name = "time_millis") val timeMillis: Long,
    val latitude: Double,
    val longitude: Double,
    /** Height above the WGS 84 ellipsoid, as Android reports it. */
    @ColumnInfo(name = "altitude_meters") val altitudeMeters: Double?,
    /** Height above mean sea level, only when the device provides it (Android 14+). */
    @ColumnInfo(name = "msl_altitude_meters") val mslAltitudeMeters: Double?,
    @ColumnInfo(name = "accuracy_meters") val accuracyMeters: Float?,
    @ColumnInfo(name = "speed_mps") val speedMetersPerSecond: Float?,
    @ColumnInfo(name = "bearing_degrees") val bearingDegrees: Float?,
)
