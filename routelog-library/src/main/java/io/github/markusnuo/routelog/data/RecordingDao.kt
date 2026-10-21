package io.github.markusnuo.routelog.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Blocking calls; the recorder thread and Dispatchers.IO use them, never the main thread. */
@Dao
interface RecordingDao {
    @Insert
    fun insertRecording(recording: RecordingEntity): Long

    @Insert
    fun insertPoint(point: TrackPointEntity): Long

    @Query(
        "UPDATE recordings SET distance_meters = :distanceMeters, point_count = point_count + 1, " +
            "last_point_at_millis = :pointTimeMillis WHERE id = :id",
    )
    fun recordProgress(id: Long, distanceMeters: Double, pointTimeMillis: Long)

    // Only an unfinished recording needs alive_at_millis: a finished one has no resume window.
    @Query("UPDATE recordings SET state = 'FINISHED', ended_at_millis = :endedAtMillis, alive_at_millis = NULL WHERE id = :id")
    fun markFinished(id: Long, endedAtMillis: Long)

    /**
     * [endedAtMillis] is the end the list shows; [aliveAtMillis] is what the 12-hour resume window
     * counts from besides it (see [RecordingEntity.aliveAtMillis]). It is always written, so a
     * caller that wants it unchanged passes the value it read.
     */
    @Query(
        "UPDATE recordings SET state = 'INTERRUPTED', ended_at_millis = :endedAtMillis, " +
            "alive_at_millis = :aliveAtMillis WHERE id = :id",
    )
    fun markInterrupted(id: Long, endedAtMillis: Long, aliveAtMillis: Long?)

    /** Returns 0 if the recording is gone or not interrupted (anymore); nothing is changed then. */
    @Query(
        "UPDATE recordings SET state = 'RECORDING', ended_at_millis = NULL, segment = :segment, " +
            "alive_at_millis = :nowMillis WHERE id = :id AND state = 'INTERRUPTED'",
    )
    fun markResumed(id: Long, segment: Int, nowMillis: Long): Int

    /** The recorder still runs this recording; see [RecordingEntity.aliveAtMillis]. */
    @Query("UPDATE recordings SET alive_at_millis = :nowMillis WHERE id = :id AND state = 'RECORDING'")
    fun markAlive(id: Long, nowMillis: Long)

    @Query("SELECT * FROM recordings WHERE id = :id")
    fun recording(id: Long): RecordingEntity?

    @Query("SELECT * FROM recordings WHERE state = 'RECORDING'")
    fun recordingsInProgress(): List<RecordingEntity>

    /**
     * Ends an interrupted recording without resuming it ("Finish & upload"). Returns 0 if it is
     * not interrupted (anymore), e.g. because it was resumed or deleted at the same moment.
     */
    @Query("UPDATE recordings SET state = 'FINISHED', alive_at_millis = NULL WHERE id = :id AND state = 'INTERRUPTED'")
    fun finishInterrupted(id: Long): Int

    @Transaction
    @Query("SELECT * FROM recordings ORDER BY started_at_millis DESC, id DESC")
    fun observeRecordingList(): Flow<List<RecordingListItem>>

    @Query("SELECT * FROM track_points WHERE recording_id = :recordingId AND id > :afterId ORDER BY id LIMIT :limit")
    fun pointsAfter(recordingId: Long, afterId: Long, limit: Int): List<TrackPointEntity>

    /** Deletes the route but keeps the recording (date, duration, distance), e.g. after an upload. */
    @Query("DELETE FROM track_points WHERE recording_id = :recordingId")
    fun deletePoints(recordingId: Long)

    /** Returns 0 if the recording is still running; a running recording can't be deleted. */
    @Query("DELETE FROM recordings WHERE id = :id AND state != 'RECORDING'")
    fun deleteUnlessRecording(id: Long): Int

    @Query("DELETE FROM recordings WHERE id = :id")
    fun delete(id: Long)
}
