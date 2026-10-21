package io.github.markusnuo.routelog.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Installs keep their recordings when version 2 adds the uploads and grants tables and when
 * version 3 adds `recordings.alive_at_millis`.
 *
 * Builds a version 1 or 2 database exactly as Room created it (statements and identity hash from
 * routelog-library/schemas/…/1.json and 2.json), then opens it with the current Room database. Room
 * runs the migration and checks the result against the current schema itself; a mismatch fails the
 * open. (room-testing can't be used: it needs a newer kotlinx-serialization than the app ships,
 * and Android aligns test dependencies with the app's.)
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun cleanUp() {
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun recordingsOfVersion1SurviveTheMigrationTo3() {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(TEST_DB), null).use { db ->
            VERSION_1_SCHEMA.forEach(db::execSQL)
            db.execSQL(
                "INSERT INTO recordings (id, started_at_millis, ended_at_millis, state, distance_meters, point_count, " +
                    "last_point_at_millis, segment) VALUES (1, 1000, 9000, 'INTERRUPTED', 123.5, 2, 2000, 1)",
            )
            db.execSQL(
                "INSERT INTO track_points (recording_id, segment, time_millis, latitude, longitude, altitude_meters, " +
                    "msl_altitude_meters, accuracy_meters, speed_mps, bearing_degrees) " +
                    "VALUES (1, 0, 1000, 47.4245, 9.3767, NULL, NULL, 4.0, 14.0, NULL), " +
                    "(1, 1, 2000, 47.4246, 9.3767, NULL, 670.0, 4.0, 14.0, NULL)",
            )
            db.version = 1
        }

        val database = RoutelogDatabase.build(context, TEST_DB)
        try {
            val recording = database.recordingDao().recording(1)!!
            assertEquals(RecordingState.INTERRUPTED, recording.state)
            assertEquals(123.5, recording.distanceMeters, 0.0)
            assertEquals(1, recording.segment)
            assertEquals(listOf(1000L, 2000L), database.recordingDao().pointsAfter(1, 0, 10).map { it.timeMillis })
            assertNull("a version 1 recording has no upload", database.uploadDao().upload(1))
            assertEquals(3, database.openHelper.writableDatabase.version)
            assertNull(recording.aliveAtMillis)

            // The new tables work: the upload goes with its recording, the grant stays without it.
            database.uploadDao().insert(
                UploadEntity(1, "https://a.example/r", "a.example", null, Long.MAX_VALUE, UploadState.WAITING),
            )
            database.grantDao().insert(GrantEntity("hash-1", 1, Long.MAX_VALUE))
            assertEquals(1L, database.grantDao().grant("hash-1")?.recordingId)
            assertEquals(1, database.recordingDao().deleteUnlessRecording(1))
            assertNull(database.uploadDao().upload(1))
            assertEquals("hash-1", database.grantDao().grant("hash-1")?.tokenHash)
            assertNull(database.grantDao().grant("hash-1")?.recordingId)
        } finally {
            database.close()
        }
    }

    @Test
    fun recordingsOfVersion2SurviveTheMigrationTo3() {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(TEST_DB), null).use { db ->
            VERSION_2_SCHEMA.forEach(db::execSQL)
            // A linked recording a dead version 2 process left running, with its upload and used grant.
            db.execSQL(
                "INSERT INTO recordings (id, started_at_millis, ended_at_millis, state, distance_meters, point_count, " +
                    "last_point_at_millis, segment) VALUES (1, 1000, NULL, 'RECORDING', 50.0, 1, 2000, 0)",
            )
            db.execSQL(
                "INSERT INTO track_points (recording_id, segment, time_millis, latitude, longitude, altitude_meters, " +
                    "msl_altitude_meters, accuracy_meters, speed_mps, bearing_degrees) " +
                    "VALUES (1, 0, 2000, 47.4245, 9.3767, NULL, NULL, 4.0, 14.0, NULL)",
            )
            db.execSQL(
                "INSERT INTO uploads (recording_id, upload_url, host, label, expires_at_millis, state, attempts, " +
                    "last_http_status, finished_at_millis, route_deleted_at_millis) " +
                    "VALUES (1, 'https://a.example/r', 'a.example', 'Lesson', 9000000000000, 'WAITING', 0, NULL, NULL, NULL)",
            )
            db.execSQL("INSERT INTO grants (token_hash, recording_id, expires_at_millis) VALUES ('hash-1', 1, 9000000000000)")
            db.version = 2
        }

        val database = RoutelogDatabase.build(context, TEST_DB)
        try {
            assertEquals(3, database.openHelper.writableDatabase.version)
            val recording = database.recordingDao().recording(1)!!
            assertEquals(RecordingState.RECORDING, recording.state)
            assertEquals(2000L, recording.lastPointAtMillis)
            assertNull("no sign of the recorder from before the column", recording.aliveAtMillis)
            assertEquals(listOf(2000L), database.recordingDao().pointsAfter(1, 0, 10).map { it.timeMillis })
            assertEquals(UploadState.WAITING, database.uploadDao().upload(1)?.state)
            assertEquals(1L, database.grantDao().grant("hash-1")?.recordingId)

            // The new column works.
            database.recordingDao().markAlive(1, 60_000)
            assertEquals(60_000L, database.recordingDao().recording(1)?.aliveAtMillis)
        } finally {
            database.close()
        }
    }

    private companion object {
        const val TEST_DB = "routelog-migration-test.db"

        /** From routelog-library/schemas/io.github.markusnuo.routelog.data.RoutelogDatabase/1.json. */
        val VERSION_1_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `recordings` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`started_at_millis` INTEGER NOT NULL, `ended_at_millis` INTEGER, `state` TEXT NOT NULL, " +
                "`distance_meters` REAL NOT NULL, `point_count` INTEGER NOT NULL, `last_point_at_millis` INTEGER, " +
                "`segment` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `track_points` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`recording_id` INTEGER NOT NULL, `segment` INTEGER NOT NULL, `time_millis` INTEGER NOT NULL, " +
                "`latitude` REAL NOT NULL, `longitude` REAL NOT NULL, `altitude_meters` REAL, " +
                "`msl_altitude_meters` REAL, `accuracy_meters` REAL, `speed_mps` REAL, `bearing_degrees` REAL, " +
                "FOREIGN KEY(`recording_id`) REFERENCES `recordings`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_track_points_recording_id` ON `track_points` (`recording_id`)",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '8b593b48ed9b611361000c03ef252568')",
        )

        /** From routelog-library/schemas/io.github.markusnuo.routelog.data.RoutelogDatabase/2.json. */
        val VERSION_2_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `recordings` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`started_at_millis` INTEGER NOT NULL, `ended_at_millis` INTEGER, `state` TEXT NOT NULL, " +
                "`distance_meters` REAL NOT NULL, `point_count` INTEGER NOT NULL, `last_point_at_millis` " +
                "INTEGER, `segment` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `track_points` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`recording_id` INTEGER NOT NULL, `segment` INTEGER NOT NULL, `time_millis` INTEGER NOT NULL, " +
                "`latitude` REAL NOT NULL, `longitude` REAL NOT NULL, `altitude_meters` REAL, " +
                "`msl_altitude_meters` REAL, `accuracy_meters` REAL, `speed_mps` REAL, `bearing_degrees` REAL, " +
                "FOREIGN KEY(`recording_id`) REFERENCES `recordings`(`id`) ON UPDATE NO ACTION ON DELETE " +
                "CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_track_points_recording_id` ON `track_points` (`recording_id`)",
            "CREATE TABLE IF NOT EXISTS `uploads` (`recording_id` INTEGER NOT NULL, `upload_url` TEXT NOT " +
                "NULL, `host` TEXT NOT NULL, `label` TEXT, `expires_at_millis` INTEGER NOT NULL, `state` TEXT " +
                "NOT NULL, `attempts` INTEGER NOT NULL, `last_http_status` INTEGER, `finished_at_millis` " +
                "INTEGER, `route_deleted_at_millis` INTEGER, PRIMARY KEY(`recording_id`), FOREIGN " +
                "KEY(`recording_id`) REFERENCES `recordings`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `grants` (`token_hash` TEXT NOT NULL, `recording_id` INTEGER, " +
                "`expires_at_millis` INTEGER NOT NULL, PRIMARY KEY(`token_hash`), FOREIGN KEY(`recording_id`) " +
                "REFERENCES `recordings`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
            "CREATE INDEX IF NOT EXISTS `index_grants_recording_id` ON `grants` (`recording_id`)",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, " +
                "'486bce7cc53f3d86c216ed6e9a497f10')",
        )
    }
}
