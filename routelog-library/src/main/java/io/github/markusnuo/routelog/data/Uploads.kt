package io.github.markusnuo.routelog.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation

enum class UploadState {
    /** The recording is still running or interrupted; nothing to upload yet. */
    WAITING,

    /** Stopped; the upload is queued until it succeeds or the grant expires. */
    PENDING,

    /** The server took the route (2xx), or had it already (409). */
    DONE,

    /** The grant ran out before the upload succeeded. */
    EXPIRED,

    /** The server refused the grant (401, 403, 410). */
    REJECTED,

    /** The server answered something final other than a refusal (other 4xx, 3xx). */
    FAILED,

    /** The recording has no point at all, so nothing was sent. */
    NOTHING_TO_UPLOAD,

    /** This phone has no token for the upload (e.g. after a restore from a backup): it can't be sent from here. */
    NO_GRANT,
}

/**
 * Upload target of a recording started with one, from a link or a host app
 * (docs/EXTERNAL_RECORDING.md). The token is not stored here: it lives in the TokenStore, which is
 * left out of every backup.
 */
@Entity(
    tableName = "uploads",
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recording_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class UploadEntity(
    @PrimaryKey @ColumnInfo(name = "recording_id") val recordingId: Long,
    @ColumnInfo(name = "upload_url") val uploadUrl: String,
    val host: String,
    val label: String?,
    /** The grant is valid before this instant, not at it. */
    @ColumnInfo(name = "expires_at_millis") val expiresAtMillis: Long,
    val state: UploadState,
    val attempts: Int = 0,
    @ColumnInfo(name = "last_http_status") val lastHttpStatus: Int? = null,
    @ColumnInfo(name = "finished_at_millis") val finishedAtMillis: Long? = null,
    /** When the keep time ran out and the points of the uploaded route were deleted; the entry stays. */
    @ColumnInfo(name = "route_deleted_at_millis") val routeDeletedAtMillis: Long? = null,
) {
    override fun toString() = "UploadEntity(recordingId=$recordingId, host=$host, state=$state, attempts=$attempts)"
}

/** What the list shows about an upload; deliberately without URL and token. */
data class UploadStatus(
    @ColumnInfo(name = "recording_id") val recordingId: Long,
    /** Lower-case host of the upload address, without port: what the user is shown as the server. */
    val host: String,
    val label: String?,
    val state: UploadState,
    /**
     * The HTTP status the upload ended with or the latest attempt got; null if there was none (no
     * answer, or an end without an attempt, e.g. expired).
     */
    @ColumnInfo(name = "last_http_status") val lastHttpStatus: Int?,
    /** The grant is valid before this instant, not at it. */
    @ColumnInfo(name = "expires_at_millis") val expiresAtMillis: Long,
    /** When the keep time deleted the points of the uploaded route; null while they are on the phone. */
    @ColumnInfo(name = "route_deleted_at_millis") val routeDeletedAtMillis: Long?,
)

/**
 * One row of [io.github.markusnuo.routelog.Routelog.recordings]: the recording, and its upload if
 * it was started with one (null for a recording that stays on the phone).
 */
data class RecordingListItem(
    @Embedded val recording: RecordingEntity,
    @Relation(parentColumn = "id", entityColumn = "recording_id", entity = UploadEntity::class)
    val upload: UploadStatus?,
)

/** Blocking calls; never on the main thread. */
@Dao
interface UploadDao {
    @Insert
    fun insert(upload: UploadEntity)

    @Query("SELECT * FROM uploads WHERE recording_id = :recordingId")
    fun upload(recordingId: Long): UploadEntity?

    /** The recording was stopped: queue the upload. Returns 0 if it isn't waiting (anymore). */
    @Query("UPDATE uploads SET state = 'PENDING' WHERE recording_id = :recordingId AND state = 'WAITING'")
    fun markPending(recordingId: Long): Int

    @Query("UPDATE uploads SET attempts = attempts + 1, last_http_status = :httpStatus WHERE recording_id = :recordingId")
    fun recordAttempt(recordingId: Long, httpStatus: Int?)

    /** Ends an upload that is still open. Returns 0 if it was ended before, so the outcome is only reported once. */
    @Query(
        "UPDATE uploads SET state = :state, last_http_status = :httpStatus, finished_at_millis = :finishedAtMillis " +
            "WHERE recording_id = :recordingId AND state IN ('WAITING', 'PENDING')",
    )
    fun finish(recordingId: Long, state: UploadState, httpStatus: Int?, finishedAtMillis: Long): Int

    @Query("SELECT recording_id FROM uploads WHERE state IN ('WAITING', 'PENDING') AND expires_at_millis <= :nowMillis")
    fun openButExpired(nowMillis: Long): List<Long>

    @Query("SELECT * FROM uploads WHERE state IN ('WAITING', 'PENDING')")
    fun open(): List<UploadEntity>

    /** Uploaded long enough ago, and the points are still on the phone. */
    @Query("SELECT recording_id FROM uploads WHERE state = 'DONE' AND finished_at_millis <= :cutoffMillis AND route_deleted_at_millis IS NULL")
    fun doneBefore(cutoffMillis: Long): List<Long>

    /** Notes that the points of an uploaded recording were deleted. Returns 0 if that was noted before. */
    @Query("UPDATE uploads SET route_deleted_at_millis = :atMillis WHERE recording_id = :recordingId AND state = 'DONE' AND route_deleted_at_millis IS NULL")
    fun markRouteDeleted(recordingId: Long, atMillis: Long): Int
}
