package io.github.markusnuo.routelog.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.markusnuo.routelog.recording.Recorder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RecordingStorageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: RoutelogDatabase
    private lateinit var dao: RecordingDao

    @Before
    fun open() {
        context.deleteDatabase(TEST_DB)
        database = RoutelogDatabase.build(context, TEST_DB)
        dao = database.recordingDao()
    }

    @After
    fun close() {
        database.close()
        context.deleteDatabase(TEST_DB)
    }

    private fun point(recordingId: Long, i: Int) = TrackPointEntity(
        recordingId = recordingId, segment = 0, timeMillis = 1_000L * i,
        latitude = 47.0 + i * 1e-4, longitude = 9.0, altitudeMeters = null, mslAltitudeMeters = null,
        accuracyMeters = 4f, speedMetersPerSecond = null, bearingDegrees = null,
    )

    @Test
    fun everyCommitIsSyncedAndDeletedDataIsOverwritten() {
        val db = database.openHelper.writableDatabase
        db.query("PRAGMA synchronous").use { it.moveToFirst(); assertEquals(2, it.getInt(0)) } // 2 = FULL
        db.query("PRAGMA secure_delete").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        db.query("PRAGMA journal_mode").use { it.moveToFirst(); assertEquals("wal", it.getString(0)) }
    }

    @Test
    fun deletingARecordingDeletesItsPoints() {
        val id = dao.insertRecording(RecordingEntity(startedAtMillis = 0, state = RecordingState.FINISHED))
        repeat(5) { dao.insertPoint(point(id, it)) }
        assertEquals(1, dao.deleteUnlessRecording(id))
        assertEquals(0, dao.pointsAfter(id, 0, 100).size)
        database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM track_points").use {
            it.moveToFirst()
            assertEquals(0, it.getInt(0))
        }
    }

    @Test
    fun aRunningRecordingCannotBeDeleted() {
        val id = dao.insertRecording(RecordingEntity(startedAtMillis = 0, state = RecordingState.RECORDING))
        assertEquals(0, dao.deleteUnlessRecording(id))
        assertEquals(RecordingState.RECORDING, dao.recording(id)?.state)
    }

    @Test
    fun pointsArePagedInRecordingOrder() {
        val id = dao.insertRecording(RecordingEntity(startedAtMillis = 0, state = RecordingState.FINISHED))
        repeat(25) { dao.insertPoint(point(id, it)) }
        val first = dao.pointsAfter(id, 0, 10)
        val second = dao.pointsAfter(id, first.last().id, 10)
        val third = dao.pointsAfter(id, second.last().id, 10)
        assertEquals((0 until 25).map { 1_000L * it }, (first + second + third).map { it.timeMillis })
    }

    @Test
    fun orphanedRecordingBecomesInterruptedAtItsLastPoint() {
        val id = dao.insertRecording(RecordingEntity(startedAtMillis = 100, state = RecordingState.RECORDING))
        dao.recordProgress(id, distanceMeters = 42.0, pointTimeMillis = 9_000)
        val recorder = Recorder(context, database)
        val latch = CountDownLatch(1)
        var swept: List<RecordingEntity> = emptyList()
        recorder.sweepOrphans {
            swept = it
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        recorder.quit()
        assertEquals(listOf(id), swept.map { it.id })
        val stored = dao.recording(id)!!
        assertEquals(RecordingState.INTERRUPTED, stored.state)
        assertEquals(9_000L, stored.endedAtMillis)
        assertEquals(1, stored.pointCount)
        assertEquals(42.0, stored.distanceMeters, 0.0)
    }

    @Test
    fun resumingMovesToTheNextSegment() {
        val id = dao.insertRecording(RecordingEntity(startedAtMillis = 0, state = RecordingState.INTERRUPTED, endedAtMillis = 5))
        dao.markResumed(id, segment = 1, nowMillis = 7)
        val stored = dao.recording(id)!!
        assertEquals(RecordingState.RECORDING, stored.state)
        assertNull(stored.endedAtMillis)
        assertEquals(1, stored.segment)
        assertEquals(7L, stored.aliveAtMillis)
    }

    @Test
    fun onlyARunningRecordingIsMarkedAlive() {
        val running = dao.insertRecording(RecordingEntity(startedAtMillis = 0, state = RecordingState.RECORDING))
        val interrupted = dao.insertRecording(RecordingEntity(startedAtMillis = 0, state = RecordingState.INTERRUPTED, endedAtMillis = 5))
        dao.markAlive(running, 60_000)
        dao.markAlive(interrupted, 60_000)
        assertEquals(60_000L, dao.recording(running)?.aliveAtMillis)
        assertNull(dao.recording(interrupted)?.aliveAtMillis)
    }

    @Test
    fun interruptingSetsTheEndAndTheAliveTime() {
        val id = dao.insertRecording(RecordingEntity(startedAtMillis = 0, state = RecordingState.RECORDING, aliveAtMillis = 60_000))
        // The shown end and the alive time are separate; null clears the alive time.
        dao.markInterrupted(id, endedAtMillis = 9_000, aliveAtMillis = 120_000)
        var stored = dao.recording(id)!!
        assertEquals(RecordingState.INTERRUPTED, stored.state)
        assertEquals(9_000L, stored.endedAtMillis)
        assertEquals(120_000L, stored.aliveAtMillis)

        dao.markInterrupted(id, endedAtMillis = 9_000, aliveAtMillis = null)
        stored = dao.recording(id)!!
        assertEquals(9_000L, stored.endedAtMillis)
        assertNull(stored.aliveAtMillis)
    }

    @Test
    fun aFinishedRecordingKeepsNoAliveTime() {
        val running = dao.insertRecording(RecordingEntity(startedAtMillis = 0, state = RecordingState.RECORDING, aliveAtMillis = 60_000))
        dao.markFinished(running, endedAtMillis = 70_000)
        assertEquals(RecordingState.FINISHED, dao.recording(running)?.state)
        assertEquals(70_000L, dao.recording(running)?.endedAtMillis)
        assertNull(dao.recording(running)?.aliveAtMillis)

        val interrupted = dao.insertRecording(
            RecordingEntity(startedAtMillis = 0, state = RecordingState.INTERRUPTED, endedAtMillis = 5, aliveAtMillis = 60_000),
        )
        assertEquals(1, dao.finishInterrupted(interrupted))
        assertEquals(RecordingState.FINISHED, dao.recording(interrupted)?.state)
        assertEquals(5L, dao.recording(interrupted)?.endedAtMillis)
        assertNull(dao.recording(interrupted)?.aliveAtMillis)
    }

    @Test
    fun resumingOnlyMatchesAnInterruptedRecording() {
        val finished = dao.insertRecording(RecordingEntity(startedAtMillis = 0, state = RecordingState.FINISHED, endedAtMillis = 5))
        assertEquals(0, dao.markResumed(finished, segment = 1, nowMillis = 7))
        assertEquals(RecordingState.FINISHED, dao.recording(finished)?.state)
        assertEquals(0, dao.recording(finished)?.segment)
        assertEquals(0, dao.markResumed(finished + 100, segment = 1, nowMillis = 7)) // no such recording

        val interrupted = dao.insertRecording(RecordingEntity(startedAtMillis = 0, state = RecordingState.INTERRUPTED, endedAtMillis = 5))
        assertEquals(1, dao.markResumed(interrupted, segment = 1, nowMillis = 7))
        // Now it is running: a second resume must not take it over again.
        assertEquals(0, dao.markResumed(interrupted, segment = 2, nowMillis = 8))
        assertEquals(1, dao.recording(interrupted)?.segment)
    }

    @Test
    fun afterACheckpointADeletedRouteIsGoneFromTheDatabaseFiles() {
        val id = dao.insertRecording(RecordingEntity(startedAtMillis = 0, state = RecordingState.FINISHED))
        val points = (0 until 300).map { point(id, it).copy(latitude = 47.123456 + it * 1e-5, longitude = 9.654321 + it * 1e-5) }
        database.runInTransaction(Runnable { points.forEach { dao.insertPoint(it) } })
        val files = listOf(context.getDatabasePath(TEST_DB), File(context.getDatabasePath(TEST_DB).path + "-wal"))
        // Sanity check of the scan itself: the route is on disk (database file or log) before the delete.
        assertTrue(occurrencesOf(points, files) > 0)

        assertEquals(1, dao.deleteUnlessRecording(id))
        database.checkpoint()

        assertEquals(0, occurrencesOf(points, files))
    }

    /** How often the raw 8 bytes of a stored latitude or longitude appear in [files]. */
    private fun occurrencesOf(points: List<TrackPointEntity>, files: List<File>): Int {
        val needles = points.flatMap { listOf(it.latitude, it.longitude) }.map { ByteBuffer.allocate(8).putDouble(it).array() }
        var found = 0
        for (file in files.filter { it.exists() }) {
            val bytes = file.readBytes()
            for (needle in needles) {
                for (i in 0..bytes.size - 8) {
                    if (bytes[i] == needle[0] && (1 until 8).all { bytes[i + it] == needle[it] }) found++
                }
            }
        }
        return found
    }

    private companion object {
        const val TEST_DB = "routelog-test.db"
    }
}
