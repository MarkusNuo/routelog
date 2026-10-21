package io.github.markusnuo.routelog.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * A grant (the permission of a link) that was used to start a recording. Kept until the grant
 * expires, so the same link can't start a second recording. Holds a hash of the token, never the
 * token itself.
 */
@Entity(
    tableName = "grants",
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recording_id"],
            // The grant outlives the recording: deleting a recording must not make its link usable again.
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("recording_id")],
)
data class GrantEntity(
    /** Lower-case hex SHA-256 of the token, see GrantHash. */
    @PrimaryKey @ColumnInfo(name = "token_hash") val tokenHash: String,
    /** The recording this grant started; null once that recording was deleted. */
    @ColumnInfo(name = "recording_id") val recordingId: Long?,
    /** The grant is valid before this instant, not at it. */
    @ColumnInfo(name = "expires_at_millis") val expiresAtMillis: Long,
) {
    override fun toString() = "GrantEntity(recordingId=$recordingId)"
}

/** Blocking calls; never on the main thread. */
@Dao
interface GrantDao {
    @Insert
    fun insert(grant: GrantEntity)

    @Query("SELECT * FROM grants WHERE token_hash = :tokenHash")
    fun grant(tokenHash: String): GrantEntity?

    /** For a recording that could not start after all: its link was not used. */
    @Query("DELETE FROM grants WHERE token_hash = :tokenHash")
    fun delete(tokenHash: String)

    /** Past its expiry a link is refused before the grant is looked up, so the row is of no use anymore. */
    @Query("DELETE FROM grants WHERE expires_at_millis <= :nowMillis")
    fun deleteExpired(nowMillis: Long): Int
}
